#!/usr/bin/env python3
"""
mini-analytics 기기 검증 도구 (7단계).

하나의 프로세스에서 다음을 함께 실행한다.
  1) 로컬 HTTPS 계약 서버 (설계 §6.5 계약의 최소 구현, 테스트 전용. 운영 서버가 아님)
  2) adb로 sample 앱을 조작하는 시나리오

사용법 (에뮬레이터 또는 기기가 하나 연결된 상태):
  ./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository :sample:assembleDebug :sample:assembleRelease
  python3 tools/device-verification/verify.py --serial emulator-5554 --variant debug --variant release

서버만 띄우기 (수동 확인용):
  python3 tools/device-verification/verify.py --serve-only

물리 기기 (docs/design/v1-physical-device-report.md):
  # 인증서 SAN에 Mac의 LAN IP를 추가 (sample을 다시 빌드해야 함)
  python3 tools/device-verification/verify.py --extra-ip 192.168.x.y --prepare-cert-only
  # A안: adb reverse + 기본 시나리오
  ./gradlew :sample:assembleDebug :sample:assembleRelease -PsampleEndpoint=https://127.0.0.1:8443/v1/batch
  python3 tools/device-verification/verify.py --serial <serial> --reverse --suite basic --variant debug --variant release
  # B안: LAN 주소 + 네트워크·백그라운드 시나리오
  ./gradlew :sample:assembleDebug :sample:assembleRelease -PsampleEndpoint=https://192.168.x.y:8443/v1/batch
  python3 tools/device-verification/verify.py --serial <serial> --extra-ip 192.168.x.y --suite physical --variant debug --variant release
  # 시나리오 하나만: --only P1 (여러 번 지정 가능)
  # 끝나면 임시 인증서를 원래대로 (커밋 전에 반드시)
  python3 tools/device-verification/verify.py --restore-cert

sample은 -PsampleEndpoint의 호스트 하나에만 테스트 CA를 신뢰한다 (sample/build.gradle.kts).
판정: PASS, FAIL, ENV(네트워크 미복구 등 테스트 환경 문제), REVIEW(확인 불가 또는 미분류 StrictMode),
LIMIT(v1 설계 범위의 제약을 확인함. PASS가 아님. 예: P5에서 프로세스 freeze로 Doze 해제 후 재시도 없음).
시나리오별 증거: results/physical/<variant>-<ID>-{summary,logcat}.txt

에뮬레이터는 10.0.2.2로 호스트 PC에 접근한다. 인증서(mock_cert.pem)는 sample 앱이 신뢰하도록
sample/src/main/res/raw/mock_ca.pem에 같은 파일을 둔다. 키가 없으면 새로 만들고 sample에 복사한다
(이때는 sample을 다시 빌드해야 한다).
"""
import argparse
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CERT = os.path.join(HERE, "mock_cert.pem")
KEY = os.path.join(HERE, "mock_key.pem")
SAMPLE_CA = os.path.join(ROOT, "sample", "src", "main", "res", "raw", "mock_ca.pem")
PACKAGE = "io.github.dduddupark.minianalytics.sample"
ACTIVITY = PACKAGE + "/.MainActivity"
API_KEY = "pk_test"
PORT = 8443
LAN_HOST = None  # 물리 기기 B안의 Mac LAN 주소 (--extra-ip 첫 값)


# =====================================================================================
# 계약 서버
# =====================================================================================

class ServerState:
    def __init__(self):
        self.lock = threading.Lock()
        self.reset()

    def reset(self):
        with self.lock:
            self.mode = "normal"  # normal | unavailable | redirect | batch400
            self.flaky = False  # batch400에서 단건 요청에 가끔 503/429
            self.single_delay = 0.0
            self.requests = []  # {t, path, ids, status, bytes}
            self.stored = {}  # messageId -> event (중복 제거)
            self.links = set()
            self.single_counter = 0
            self.probes = []  # 기기 셸의 curl 연결 확인 (GET /probe). SDK 요청(POST /v1/batch)과 구분된다

    def snapshot(self):
        with self.lock:
            return {
                "requests": list(self.requests),
                "probes": list(self.probes),
                "stored": dict(self.stored),
                "links": set(self.links),
            }


STATE = ServerState()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        # TLS handshake를 연결마다 자기 스레드에서 한다. accept 루프에서 하면, handshake를 끝내지 않는
        # 연결 하나가 서버 전체를 멈춘다 (7단계 첫 실행에서 실제로 발생).
        self.request.do_handshake()
        super().setup()

    def log_message(self, *args):  # 조용히
        pass

    def _send(self, status, body=b"", headers=None):
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # 서버 도달 확인 전용. SDK는 GET을 보내지 않으므로 SDK 요청과 섞이지 않는다.
        if self.path.startswith("/probe"):
            with STATE.lock:
                STATE.probes.append({"t": time.time(), "path": self.path, "client": self.client_address[0]})
            return self._send(200, b"probe-ok")
        return self._send(404, b"")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        now = time.time()
        try:
            batch = json.loads(raw)["batch"]
            ids = [e["messageId"] for e in batch]
        except Exception:
            batch, ids = None, []

        def record(status):
            with STATE.lock:
                STATE.requests.append({"t": now, "path": self.path, "ids": ids, "status": status, "bytes": len(raw),
                                       "client": self.client_address[0],
                                       "names": [str(e.get("event") or e.get("name")) for e in (batch or [])]})

        if self.path != "/v1/batch":
            record(200)  # 리다이렉트를 따라왔다면 여기로 온다 (일어나면 안 됨)
            return self._send(200, b"{}")
        if self.headers.get("Authorization") != "Bearer " + API_KEY:
            record(401)
            return self._send(401, b'{"error":{"code":"unauthorized"}}')
        if batch is None:
            record(400)
            return self._send(400, b'{"error":{"code":"malformed_request"}}')

        with STATE.lock:
            mode, flaky, delay = STATE.mode, STATE.flaky, STATE.single_delay
        if mode == "unavailable":
            record(503)
            return self._send(503, b'{"error":{"code":"unavailable"}}')
        if mode == "redirect":
            record(307)
            host = self.headers.get("Host") or "10.0.2.2:%d" % PORT
            return self._send(307, b"", {"Location": "https://%s/v1/redirected" % host})

        invalid = [e for e in batch if isinstance(e.get("properties"), dict) and "__invalid" in e["properties"]]
        if mode == "batch400" and len(batch) == 1:
            if delay:
                time.sleep(delay)
            with STATE.lock:
                STATE.single_counter += 1
                n = STATE.single_counter
            if flaky and n % 7 == 3:
                record(503)
                return self._send(503, b"{}")
            if flaky and n % 11 == 5:
                record(429)
                return self._send(429, b"{}", {"Retry-After": "2"})
        if mode == "batch400" and invalid:
            if len(batch) > 1:
                record(400)
                return self._send(400, b'{"error":{"code":"invalid_request"}}')
            record(400)
            body = json.dumps({"error": {"code": "invalid_event", "messageId": ids[0]}}).encode()
            return self._send(400, body)

        with STATE.lock:
            for e in batch:
                if e in invalid:
                    continue
                STATE.stored.setdefault(e["messageId"], e)  # 계약 4: messageId 중복 제거
                if e.get("type") == "identify":
                    STATE.links.add((e.get("anonymousId"), e.get("userId")))  # 계약 5
        record(200)
        rejected = [{"messageId": e["messageId"], "reason": "invalid"} for e in invalid]
        return self._send(200, json.dumps({"rejected": rejected}).encode(), {"Content-Type": "application/json"})


def cert_has_ips(ips):
    if not (os.path.exists(KEY) and os.path.exists(CERT)):
        return False
    text = subprocess.run(["openssl", "x509", "-in", CERT, "-noout", "-ext", "subjectAltName"],
                          capture_output=True, text=True).stdout
    return all(("IP Address:" + ip) in text for ip in ips)


CERT_BACKUP = os.path.join(HERE, "results", "cert-backup")  # results/는 git에서 제외됨


def restore_certificate():
    """--extra-ip로 만든 임시 인증서를 백업해 둔 원래 인증서·키로 되돌린다 (커밋 전에 실행)."""
    names = {"mock_cert.pem": CERT, "mock_key.pem": KEY, "mock_ca.pem": SAMPLE_CA}
    if not all(os.path.exists(os.path.join(CERT_BACKUP, n)) for n in names):
        print("복원할 백업이 없습니다:", CERT_BACKUP)
        return
    for n, dst in names.items():
        shutil.copyfile(os.path.join(CERT_BACKUP, n), dst)
    os.chmod(KEY, 0o600)
    shutil.rmtree(CERT_BACKUP)
    print("원래 인증서로 복원했습니다. sample 앱을 다시 빌드하세요.")


def ensure_certificate(extra_ips=()):
    if cert_has_ips(["10.0.2.2", "127.0.0.1", *extra_ips]):
        return
    if extra_ips and all(os.path.exists(f) for f in (CERT, KEY)) and not os.path.exists(CERT_BACKUP):
        # 저장소에 들어 있는 인증서(mock_cert.pem, sample의 mock_ca.pem)를 덮어쓰므로 원본을 백업한다.
        os.makedirs(CERT_BACKUP)
        for src, n in ((CERT, "mock_cert.pem"), (KEY, "mock_key.pem"), (SAMPLE_CA, "mock_ca.pem")):
            shutil.copyfile(src, os.path.join(CERT_BACKUP, n))
    san = ",".join(["IP:10.0.2.2", "IP:127.0.0.1", "DNS:localhost"] + ["IP:" + ip for ip in extra_ips])
    subprocess.run([
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
        "-subj", "/CN=mini-analytics local test",
        "-addext", "subjectAltName=" + san,
        "-addext", "basicConstraints=critical,CA:TRUE",
        "-keyout", KEY, "-out", CERT,
    ], check=True, capture_output=True)
    shutil.copyfile(CERT, SAMPLE_CA)
    print("새 인증서를 만들었습니다. sample 앱을 다시 빌드하세요:", SAMPLE_CA)
    if extra_ips:
        print("임시 인증서입니다. 커밋하기 전에 --restore-cert로 원래 인증서를 복원하세요.")


class TlsServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, context):
        super().__init__(address, handler)
        self.context = context

    def get_request(self):
        sock, address = self.socket.accept()
        sock.settimeout(30)  # 멈춘 연결은 30초 뒤 정리된다
        return self.context.wrap_socket(sock, server_side=True, do_handshake_on_connect=False), address

    def handle_error(self, request, client_address):
        pass  # handshake 실패나 연결 끊김은 조용히 무시한다


def start_server(extra_ips=()):
    ensure_certificate(extra_ips)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT, KEY)
    server = TlsServer(("0.0.0.0", PORT), Handler, context)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


# =====================================================================================
# StrictMode 위반 분류 (물리 기기 7장, API 28+ sample의 penaltyListener 로그)
#   OURS          스택에 SDK·sample(io.github.dduddupark.minianalytics) 프레임이 하나라도 있음 → 시나리오 FAIL
#                 (SDK가 동기적으로 호출한 외부 라이브러리의 위반도 아래쪽에 SDK 프레임이 남으므로 여기에 포함)
#   FRAMEWORK     모든 프레임이 플랫폼 패키지 → 기록만 하고 판정에 영향 없음
#   UNCLASSIFIED  그 밖(알 수 없는 패키지, 로그 누락으로 스택이 불완전 등) → 정상으로 판정하지 않음(REVIEW)
# =====================================================================================

OURS_PREFIX = "io.github.dduddupark.minianalytics"
FRAMEWORK_PREFIXES = ("android.", "com.android.", "java.", "javax.", "dalvik.", "libcore.", "sun.", "jdk.",
                      "org.json.", "org.apache.harmony.", "com.samsung.", "com.sec.")
FRAME_RE = re.compile(r"^(?P<cls>[\w$.]+)\.(?P<method>[^.(]+)\((?P<loc>.*)\)$")


def load_r8_mapping(path):
    """R8 mapping.txt에서 '난독화 이름 -> 원래 클래스 이름'을 만든다 (release 스택 분류용)."""
    mapping = {}
    if not path or not os.path.exists(path):
        return mapping
    for line in open(path, encoding="utf-8"):
        if line.startswith((" ", "#")) or " -> " not in line:
            continue
        original, obfuscated = line.rstrip().rstrip(":").split(" -> ")
        mapping[obfuscated] = original
    return mapping


def classify_stack(frames, mapping):
    classes = []
    for frame in frames:
        m = FRAME_RE.match(frame.strip())
        if not m:
            return "UNCLASSIFIED", frame
        classes.append(mapping.get(m.group("cls"), m.group("cls")))
    if not classes:
        return "UNCLASSIFIED", "(empty stack)"
    if any(c.startswith(OURS_PREFIX) for c in classes):
        return "OURS", next(c for c in classes if c.startswith(OURS_PREFIX))
    if all(c.startswith(FRAMEWORK_PREFIXES) for c in classes):
        plumbing = ("android.os.StrictMode", "libcore.", "java.io.", "dalvik.", "android.app.AndroidForwardingOs",
                    "android.app.ContextImpl", "android.content.ContextWrapper", "android.app.SharedPreferencesImpl")
        top = next((c for c in classes if not c.startswith(plumbing)), classes[0])
        return "FRAMEWORK", top
    return "UNCLASSIFIED", next(c for c in classes if not c.startswith(FRAMEWORK_PREFIXES))


def classify_violations(lines, mapping):
    """`#N begin/at/end` 로그를 위반 단위로 묶어 분류한다. end가 없으면(로그 누락) UNCLASSIFIED."""
    groups, ended, kinds = {}, set(), {}
    for line in lines:
        m = re.search(r"MiniAnalyticsStrict\(\s*\d+\): #(\d+) (begin|at|end) ?(.*)$", line)
        if not m:
            continue
        n, kind, rest = m.group(1), m.group(2), m.group(3)
        if kind == "begin":
            groups[n], kinds[n] = [], rest
        elif kind == "at":
            groups.setdefault(n, []).append(rest)
        else:
            ended.add(n)
    result = []
    for n, frames in groups.items():
        cls, where = classify_stack(frames, mapping) if n in ended else ("UNCLASSIFIED", "incomplete log")
        result.append({"id": n, "type": kinds.get(n, "?").split(".")[-1], "class": cls, "where": where,
                       "frames": frames})  # 보고서 근거용으로 스택 전체를 보존
    return result


# =====================================================================================
# adb 시나리오
# =====================================================================================

class Device:
    def __init__(self, serial):
        self.serial = serial
        self.api = int(self.shell("getprop ro.build.version.sdk").strip())

    def adb(self, *args, check=True, timeout=120):
        cmd = ["adb", "-s", self.serial, *args]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError("adb failed: %s\n%s%s" % (" ".join(cmd), result.stdout, result.stderr))
        return result.stdout

    def shell(self, command):
        return self.adb("shell", command)

    def install(self, apk):
        self.adb("uninstall", PACKAGE, check=False)
        self.adb("install", "-r", apk, timeout=300)

    def start(self, **extras):
        # 이미 떠 있는 Activity에도 intent가 전달되도록 single-top으로 보낸다 (onNewIntent).
        # 그렇지 않으면 Android는 task만 앞으로 가져오고 action을 실행하지 않는다 (7단계 첫 실행에서 발생).
        args = ["shell", "am", "start", "-W", "--activity-single-top", "-n", ACTIVITY]
        for k, v in extras.items():
            args += (["--ei", k, str(v)] if isinstance(v, int) else ["--es", k, str(v)])
        self.adb(*args)
        if "action" in extras:
            marker = "action=%s prefix=%s" % (extras["action"], extras.get("prefix", ""))
            if not wait_until(lambda: marker in self.log(), 15):
                # 물리 기기에서는 사람의 HOME 입력 등으로 Activity가 action 전에 밀려날 수 있다 (P1 재실행 1차).
                # 한 번만 다시 보낸다. 이벤트 이름이 같으므로 서버의 이름 집합 판정에는 영향이 없다.
                print("    [start] action=%s 미확인 → intent 재전송" % extras["action"], flush=True)
                self.adb(*args)
                if not wait_until(lambda: marker in self.log(), 15):
                    raise RuntimeError("앱이 action=%s를 실행하지 않았습니다" % extras["action"])

    def force_stop(self):
        self.shell("am force-stop " + PACKAGE)

    def alive(self):
        # API 23의 `ps -A`는 헤더만 출력하고 성공(0)으로 끝난다. 그래서 `ps`와 `ps -A`를 모두 보고 합친다
        # (7단계 API 23 첫 실행에서 생존 확인이 항상 거짓으로 나왔다).
        out = self.shell("ps; ps -A 2>/dev/null")
        return any(line.split()[-1] == PACKAGE for line in out.splitlines() if line.strip())

    def home(self):
        self.shell("input keyevent KEYCODE_HOME")

    def airplane(self, on):
        self.shell("cmd connectivity airplane-mode " + ("enable" if on else "disable"))

    def wifi(self, on):
        self.shell("svc wifi " + ("enable" if on else "disable"))

    def default_network(self):
        out = self.shell("dumpsys connectivity | grep 'Active default network'")
        return out.strip().split(":")[-1].strip() if out.strip() else "none"

    def wifi_connected(self):
        return "inet " in self.shell("ip -4 addr show wlan0 2>/dev/null")

    def setting(self, key):
        return self.shell("settings get global " + key).strip()

    def net_state(self):
        """네트워크 타임라인 한 줄: 비행기 모드, Wi-Fi 설정, wlan0 주소, 기본 네트워크."""
        out = self.adb("shell", "settings get global airplane_mode_on; settings get global wifi_on; "
                       "ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*'; "
                       "dumpsys connectivity | grep 'Active default network'", check=False, timeout=30)
        lines = [l.strip() for l in out.splitlines() if l.strip()]
        ip = next((l.split()[1] for l in lines if l.startswith("inet ")), None)
        default = next((l.split(":")[-1].strip() for l in lines if "Active default network" in l), "none")
        return {"airplane": lines[0] if lines else "?", "wifi_on": lines[1] if len(lines) > 1 else "?",
                "wlan0": ip, "default": default}

    def probe(self, host, tag):
        """SDK와 별개의 연결 확인: 기기 셸의 curl이 기기의 실제 네트워크로 Mac 서버에 GET /probe를 보낸다."""
        out = self.adb("shell", "if command -v curl >/dev/null; then "
                       "curl -sk -m 5 -o /dev/null -w '%%{http_code}' 'https://%s:%d/probe?%s' 2>&1; echo \" rc=$?\"; "
                       # curl이 없는 기기(일부 에뮬레이터 이미지): TCP 연결만 확인한다. HTTP 200과 구분해 tcp-ok로 표시
                       "else nc -w 5 %s %d </dev/null >/dev/null 2>&1 && echo tcp-ok || echo \"tcp-fail rc=$?\"; fi"
                       % (host, PORT, tag, host, PORT), check=False, timeout=30)
        return out.strip()

    def clear_log(self):
        self.adb("logcat", "-b", "all", "-c", check=False)

    def log(self):
        return self.adb("logcat", "-d", "-v", "brief", check=False)


def wait_until(condition, timeout, interval=0.3):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if condition():
            return True
        time.sleep(interval)
    return condition()


def stored_ids(prefix):
    return {k for k, e in STATE.snapshot()["stored"].items() if str(e.get("event") or e.get("name") or "").startswith(prefix)}


def stored_names(prefix):
    return {str(e.get("event") or e.get("name")) for e in STATE.snapshot()["stored"].values()
            if str(e.get("event") or e.get("name") or "").startswith(prefix)}


def requests_since(t0):
    return [r for r in STATE.snapshot()["requests"] if r["t"] >= t0]


class EnvProblem(Exception):
    """테스트 환경 문제(네트워크 미복구 등). SDK 실패와 구분해 ENV로 판정한다."""


class NetTimeline:
    """시나리오 동안 5초마다 네트워크 상태와 서버 도달 여부를 기록하고, 바뀐 순간을 출력한다."""

    def __init__(self, device, host, interval=5):
        self.device, self.host, self.interval = device, host, interval
        self.samples, self._stop = [], threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread.is_alive():
            self._thread.join(40)

    def mark(self, event):
        self.samples.append({"t": time.time(), "event": event})

    def _run(self):
        last = None
        n = 0
        while not self._stop.is_set():
            try:
                state = self.device.net_state()
                if self.host:
                    n += 1
                    state["probe"] = self.device.probe(self.host, "timeline-%d" % n)
            except Exception as e:
                state = {"error": repr(e)}
            state["t"] = time.time()
            self.samples.append(state)
            key = {k: v for k, v in state.items() if k != "t"}
            if key != last:
                print("    [net %s] %s" % (time.strftime("%H:%M:%S"), key), flush=True)
                last = key
            self._stop.wait(self.interval)


def fmt_t(t):
    return time.strftime("%H:%M:%S", time.localtime(t)) + ".%03d" % int((t % 1) * 1000)


def save_artifacts(base, name, status, detail, started, samples, snap, log_path):
    """시나리오별 증거: 네트워크 타임라인, 서버 수신 기록(SDK 요청과 probe 구분), SDK 로그 요약 (T3)."""
    with open(base + "-summary.txt", "w") as f:
        f.write("%s\nstatus: %s\nstarted: %s\ndetail: %s\n\n" % (name, status, fmt_t(started), detail))
        f.write("== network timeline (5s, probe = 기기 셸 curl GET /probe, http_code)\n")
        for x in samples:
            f.write("%s %s\n" % (fmt_t(x["t"]), {k: v for k, v in x.items() if k != "t"}))
        f.write("\n== server: SDK requests (POST /v1/batch)\n")
        for r in snap["requests"]:
            f.write("%s %s %s status=%s n=%d client=%s names=%s\n" % (
                fmt_t(r["t"]), "POST", r["path"], r["status"], len(r["ids"]), r.get("client"), r.get("names")))
        f.write("\n== server: probes (GET /probe, SDK와 별개)\n")
        probes = snap["probes"]
        if probes:
            f.write("count=%d first=%s last=%s clients=%s\n" % (len(probes), fmt_t(probes[0]["t"]),
                    fmt_t(probes[-1]["t"]), sorted({p["client"] for p in probes})))
        f.write("\n== logcat: MiniAnalytics / MiniAnalyticsSample tags (full: %s)\n" % os.path.basename(log_path))
        with open(log_path, errors="replace") as lf:
            for line in lf:
                if " MiniAnalytics" in line and "MiniAnalyticsStrict" not in line:
                    f.write(line)


def run_scenarios(device, variant, suite="basic", only=None, out_dir=None):
    results = []
    mapping = load_r8_mapping(os.path.join(ROOT, "sample/build/outputs/mapping/release/mapping.txt")) if variant == "release" else {}
    tag = "%s-api%d" % (variant, device.api)

    artifacts = os.path.join(out_dir, "physical" if suite == "physical" else suite)
    os.makedirs(artifacts, exist_ok=True)

    ctx = {}

    def scenario(name, fn):
        sid = name.split()[0]
        if only and sid not in only:
            return
        STATE.reset()
        device.clear_log()
        started = time.time()
        timeline = NetTimeline(device, LAN_HOST if suite == "physical" else None)
        log_path = os.path.join(artifacts, "%s-%s-logcat.txt" % (variant, sid))
        log_file = open(log_path, "w")
        # 버퍼가 순환해도 증거가 남도록 시나리오 동안 logcat을 파일로 계속 받는다 (T3)
        streamer = subprocess.Popen(["adb", "-s", device.serial, "logcat", "-v", "threadtime", "-b", "main",
                                     "-b", "system", "-b", "crash"], stdout=log_file, stderr=subprocess.STDOUT)
        ctx["timeline"] = timeline
        ctx["log_path"] = log_path
        if suite == "physical":
            timeline.start()
        env_problem = None
        override = None
        try:
            outcome = fn()
            ok, detail = outcome[0], outcome[1]
            override = outcome[2] if len(outcome) > 2 else None  # PASS로 표시하면 안 되는 결과 (LIMIT, REVIEW)
        except EnvProblem as e:  # 네트워크가 복구되지 않음 → SDK 실패가 아니라 테스트 환경 문제
            ok, detail, env_problem = False, "ENV: %s" % e, str(e)
        except Exception as e:  # 시나리오 오류는 실패로 기록
            ok, detail = False, "error: %r" % (e,)
        timeline.stop()
        time.sleep(1)
        streamer.terminate()
        streamer.wait()
        log_file.close()
        log = device.log()
        crash_log = device.adb("logcat", "-d", "-b", "crash", "-v", "brief", check=False)
        # 샘플 앱 프로세스의 로그만 본다 (시스템 프로세스의 StrictMode 로그는 제외).
        pids = set(re.findall(r"I/MiniAnalyticsSample\(\s*(\d+)\)", log))
        own = [l for l in log.splitlines() if any(re.search(r"\(\s*%s\):" % pid, l) for pid in pids)]
        violations = classify_violations(own, mapping)
        counts = {k: sum(1 for v in violations if v["class"] == k) for k in ("OURS", "FRAMEWORK", "UNCLASSIFIED")}
        legacy_strict = [l for l in own if "StrictMode policy violation" in l]  # API 23~27 (penaltyDeath)
        crashes = [l for l in crash_log.splitlines() if "Process: " + PACKAGE in l]
        crashes += [l for l in own if "FATAL EXCEPTION" in l]
        anrs = [l for l in log.splitlines() if "ANR in " + PACKAGE in l]
        status = "PASS" if ok else "FAIL"
        if crashes or anrs or legacy_strict or counts["OURS"]:
            status = "FAIL"
        elif env_problem:
            status = "ENV"
        elif override:
            status = override
        elif counts["UNCLASSIFIED"] and status == "PASS":
            status = "REVIEW"
        framework_where = sorted({v["where"] for v in violations if v["class"] == "FRAMEWORK"})
        other_where = [(v["class"], v["type"], v["where"]) for v in violations if v["class"] != "FRAMEWORK"]
        detail += " | crash=%d anr=%d strict(OURS=%d, FRAMEWORK=%d, UNCLASSIFIED=%d)" % (
            len(crashes), len(anrs), counts["OURS"], counts["FRAMEWORK"], counts["UNCLASSIFIED"])
        if framework_where:
            detail += " framework@%s" % framework_where
        if other_where:
            detail += " other=%s" % other_where[:3]
        if legacy_strict:
            detail += " legacyStrictMode=%s" % legacy_strict[:1]
        if crashes:
            detail += " crash=%s" % crashes[:1]
        save_artifacts(os.path.join(artifacts, "%s-%s" % (variant, sid)), name, status, detail, started,
                       timeline.samples, STATE.snapshot(), log_path)
        results.append({"variant": variant, "api": device.api, "scenario": name, "ok": status == "PASS",
                        "status": status, "crashes": len(crashes), "anrs": len(anrs), "strict": counts,
                        "violations": violations, "seconds": round(time.time() - started, 1), "detail": detail})
        print("  %-6s %s — %s" % (status, name, detail), flush=True)

    p = tag + "-"

    def s1_tls_delivery():
        device.start(action="track", prefix=p + "s1", count=3)
        ok = wait_until(lambda: len(stored_names(p + "s1")) == 3, 30)
        return ok, "HTTPS(로컬 CA)로 3건 전달: %s" % sorted(stored_names(p + "s1"))

    def s2_java_api():
        device.start(action="java", prefix=p + "s2")
        ok = wait_until(lambda: {p + "s2-java", p + "s2-java-noprops", p + "s2-java-screen"} <= stored_names(p + "s2"), 30)
        ok2 = wait_until(lambda: "java identify(null) -> INVALID_ARGUMENT" in device.log(), 10)
        return ok and ok2, "Java 호출 3건 전달=%s, identify(null)->INVALID_ARGUMENT=%s" % (ok, ok2)

    def s3_identify_reset():
        device.start(action="identify", userId="u-" + tag)
        ok1 = wait_until(lambda: any(l[1] == "u-" + tag for l in STATE.snapshot()["links"]), 30)
        ok2 = wait_until(lambda: "identify callback -> Success on main=true" in device.log(), 10)
        device.start(action="reset")
        ok3 = wait_until(lambda: "logout proceeded (callback)" in device.log(), 10)
        ok4 = "reset callback -> Success on main=true" in device.log()
        return all([ok1, ok2, ok3, ok4]), "identify 연결=%s, identify 콜백(main)=%s, 로그아웃 진행=%s, reset 콜백=%s" % (ok1, ok2, ok3, ok4)

    def s4_process_death():
        STATE.mode = "unavailable"
        device.start(action="track", prefix=p + "s4", count=5)
        attempted = lambda: {i for r in STATE.snapshot()["requests"] if r["status"] == 503 for i in r["ids"]}
        ok1 = wait_until(lambda: len(attempted()) >= 5, 30)
        first_attempt = attempted()
        device.force_stop()
        time.sleep(1)
        alive_after_stop = device.alive()
        STATE.mode = "normal"
        device.start()  # 액션 없이 재시작: 시작 시 확인으로 남은 이벤트를 전송
        ok2 = wait_until(lambda: len(stored_names(p + "s4")) == 5, 40)
        same_ids = stored_ids(p + "s4") == first_attempt
        return ok1 and ok2 and same_ids and not alive_after_stop, (
            "503 중 시도한 messageId 5건 → 강제 종료(프로세스 종료=%s) → 재시작 후 5건 전달=%s, 같은 messageId=%s"
            % (not alive_after_stop, ok2, same_ids))

    def s5_redirect():
        STATE.mode = "redirect"
        device.start(action="track", prefix=p + "s5", count=1)
        ok1 = wait_until(lambda: any(r["status"] == 307 for r in STATE.snapshot()["requests"]), 30)
        time.sleep(4)
        followed = [r for r in STATE.snapshot()["requests"] if r["path"] != "/v1/batch"]
        device.force_stop()
        STATE.mode = "normal"
        device.start()
        ok2 = wait_until(lambda: len(stored_names(p + "s5")) == 1, 40)
        return ok1 and not followed and ok2, "307 수신=%s, Location 요청=%d건(0이어야 함), 재시작 후 보존·전달=%s" % (ok1, len(followed), ok2)

    def accumulate(prefix, count, invalid_index):
        """503 상태에서 저장만 시켜 두고 강제 종료한다. 재시작 시 한 배치로 나가게 하기 위해서다."""
        STATE.mode = "unavailable"
        device.start(action="track", prefix=prefix, count=count, invalidIndex=invalid_index)
        last = "%s-%d" % (prefix, count - 1)
        wait_until(lambda: any(i for r in STATE.snapshot()["requests"] for i in r["ids"]), 30)
        time.sleep(3)  # 모든 track()이 commit될 시간
        device.force_stop()
        return last

    def isolation(count, invalid_index, flaky=False, delay=0.0, late=0):
        """
        격리 한 번에 단건 요청은 최대 20번이다 (P7-2b). 상한에 도달하면 SDK는 5분 뒤 다음 회차를 시작하므로,
        검증에서는 요청이 멈추면 앱을 다시 시작해서 다음 회차를 바로 진행시킨다 (회차 = 앱 실행 한 번).
        """
        prefix = p + ("s6" if not flaky and not late else ("s7" if flaky else "s8"))
        accumulate(prefix, count, invalid_index)
        STATE.reset()
        STATE.mode, STATE.flaky, STATE.single_delay = "batch400", flaky, delay
        t0 = time.time()
        expected = count - 1
        done = lambda: len(stored_names(prefix + "-")) == expected and (not late or len(stored_names(prefix + "late")) == late)
        episodes = []
        for episode in range(12):
            e0 = time.time()
            device.start()
            if late and episode == 0:
                wait_until(lambda: len(requests_since(e0)) >= 4, 30)  # 격리가 시작된 뒤
                device.start(action="track", prefix=prefix + "late", count=late)
            # 요청이 4초 동안 없거나 완료될 때까지 기다린다
            wait_until(lambda: done() or (requests_since(e0) and time.time() - requests_since(e0)[-1]["t"] > 4), 120)
            reqs = requests_since(e0)
            episodes.append({"requests": len(reqs), "singles": sum(1 for r in reqs if len(r["ids"]) == 1)})
            if done():
                break
            device.force_stop()
        reqs = requests_since(t0)
        elapsed = (reqs[-1]["t"] - reqs[0]["t"]) if reqs else 0
        statuses = {}
        for r in reqs:
            statuses[r["status"]] = statuses.get(r["status"], 0) + 1
        invalid_stored = any(n.endswith("-%d" % invalid_index) for n in stored_names(prefix + "-"))
        max_singles = max(e["singles"] for e in episodes)
        detail = ("이벤트 %d건(잘못된 1건) → 회차 %d번, 회차별 단건 %s (상한 20), 전체 요청 %d번, 소요 %.1f초, 상태 %s, 정상 전달 %d/%d, 잘못된 이벤트 저장=%s"
                  % (count, len(episodes), [e["singles"] for e in episodes], len(reqs), elapsed, statuses,
                     len(stored_names(prefix + "-")), expected, invalid_stored))
        if late:
            detail += ", 격리 중 추가된 %d건 전달=%d" % (late, len(stored_names(prefix + "late")))
        return done() and not invalid_stored and max_singles <= 20, detail

    def s9_strictmode_alive():
        device.start(action="track", prefix=p + "s9", count=2)
        wait_until(lambda: len(stored_names(p + "s9")) == 2, 30)
        return device.alive(), "모든 호출을 메인 스레드에서 한 뒤 프로세스 생존(StrictMode penaltyDeath)=%s" % device.alive()

    # ------------------------------------------------------------------
    # 물리 기기 시나리오 (B안: LAN 주소로 접속해야 기기의 실제 네트워크를 거친다)
    # ------------------------------------------------------------------
    WIFI_WAIT = 120  # 조건 1: 비행기 모드 해제 후 Wi-Fi 연결을 최대 120초 기다린다
    REACH_WAIT = 60  # Wi-Fi 연결 뒤 Mac 서버 도달 확인을 기다리는 시간
    DELIVERY_WAIT = 300 + 60  # 조건 3: SDK 최대 backoff 300초 + 여유 60초 (서버 도달 확인 시점부터)

    def mark(event):
        ctx["timeline"].mark(event)
        print("    [event %s] %s" % (time.strftime("%H:%M:%S"), event), flush=True)

    def reachable():
        return device.probe(LAN_HOST, "check").startswith(("200", "tcp-ok"))

    def precheck():
        if not reachable():
            raise EnvProblem("시나리오 시작 전 기기→Mac 서버(%s:%d) 도달 불가: %s" % (LAN_HOST, PORT, device.net_state()))

    def recover_network(t_restore):
        """Wi-Fi 연결(최대 120초)과 서버 도달(최대 60초 추가)을 확인하고, 도달이 확인된 시각을 돌려준다."""
        connected = wait_until(lambda: device.wifi_connected() and device.default_network() != "none", WIFI_WAIT, 2)
        if not connected:
            raise EnvProblem("복구 지시 후 %d초 안에 Wi-Fi 미연결: %s" % (WIFI_WAIT, device.net_state()))
        mark("Wi-Fi 연결 확인 (+%.0fs)" % (time.time() - t_restore))
        if not wait_until(reachable, REACH_WAIT, 2):
            raise EnvProblem("Wi-Fi 연결 후 %d초 안에 Mac 서버 도달 불가: %s" % (REACH_WAIT, device.net_state()))
        t_reach = time.time()
        mark("서버 도달 확인 (probe 200, +%.0fs)" % (t_reach - t_restore))
        return t_reach

    def wait_delivery(prefix, count, t_reach):
        remaining = max(0, DELIVERY_WAIT - (time.time() - t_reach))
        delivered = wait_until(lambda: len(stored_names(prefix)) == count, remaining, 1)
        after = [r for r in STATE.snapshot()["requests"] if r["t"] >= t_reach]
        done_at = next((r["t"] for r in after if any(n.startswith(prefix) for n in r.get("names", []))
                        and r["status"] == 200), None)
        info = "도달 확인 후 SDK 요청 %d건, 첫 전달 +%s" % (
            len(after), ("%.0fs" % (done_at - t_reach)) if done_at else "없음")
        if not delivered:
            diagnose(prefix)
        return delivered, info

    def diagnose(prefix):
        """네트워크는 복구됐는데 전달되지 않은 경우의 근거 수집 (SDK 코드는 건드리지 않는다)."""
        # grep이 아무것도 못 찾아도(종료 코드 1) 진단을 멈추지 않는다 (API 36 에뮬레이터에서 발생)
        uid = device.adb("shell", "dumpsys package %s | grep -m1 -oE '(userId|appId)=[0-9]+'" % PACKAGE,
                         check=False).strip()
        path = os.path.join(artifacts, "%s-diagnose-%d.txt" % (variant, int(time.time())))
        with open(path, "w") as f:
            f.write("prefix=%s\n%s\nalive=%s\n\n" % (prefix, uid, device.alive()))
            for cmd in ["dumpsys netpolicy | grep -iE 'restrict|uid=%s' | head -40" % uid.split("=")[-1] if uid else "true",
                        "dumpsys connectivity | grep -E 'Active default network|NetworkAgentInfo' | head -20",
                        "dumpsys deviceidle | grep -E 'mState|mLightState|mScreenOn' | head",
                        "dumpsys activity processes %s | grep -E 'curProcState|setProcState|state=' | head" % PACKAGE]:
                f.write("$ %s\n%s\n" % (cmd, device.adb("shell", cmd, check=False)))
        print("    [diagnose] %s" % path, flush=True)

    def received_count_by_name(prefix):
        counts = {}
        for r in STATE.snapshot()["requests"]:
            for i in r["ids"]:
                counts[i] = counts.get(i, 0) + 1
        names = {k: str(e.get("event") or e.get("name")) for k, e in STATE.snapshot()["stored"].items()}
        return {names[i]: c for i, c in counts.items() if i in names and names[i].startswith(prefix)}

    def p1_airplane():
        precheck()
        device.start(action="track", prefix=p + "p1warm", count=2)
        ok0 = wait_until(lambda: len(stored_names(p + "p1warm-")) == 2, 30)
        device.airplane(True)
        mark("비행기 모드 켬")
        offline = wait_until(lambda: device.default_network() == "none", 15, 1)
        wifi_off_by_us = False
        if not offline:  # 비행기 모드에서도 Wi-Fi를 유지하는 기기 설정이면 Wi-Fi도 끈다
            device.wifi(False)
            wifi_off_by_us = True
            mark("Wi-Fi 끔 (비행기 모드에서도 연결 유지됨)")
            offline = wait_until(lambda: device.default_network() == "none", 15, 1)
        if not offline:
            raise EnvProblem("비행기 모드에서 오프라인이 되지 않음: %s" % device.net_state())
        offline_probe = device.probe(LAN_HOST, "offline")
        device.start(action="track", prefix=p + "p1", count=10)
        time.sleep(8)
        device.force_stop()
        device.start()  # 오프라인 상태로 재시작 (영속성)
        mark("오프라인 재시작")
        time.sleep(5)
        leaked = len(stored_names(p + "p1-"))
        t_restore = time.time()
        device.airplane(False)
        mark("비행기 모드 끔")
        wait_until(lambda: device.setting("airplane_mode_on") == "0", 15, 1)
        # T1: 시스템이 Wi-Fi를 스스로 되살릴 시간을 준 뒤, 그래도 꺼져 있을 때만 켠다
        restored_by_system = wait_until(lambda: device.setting("wifi_on") != "0", 10, 1)
        if wifi_off_by_us or not restored_by_system:
            device.wifi(True)
            mark("Wi-Fi 켬 (도구가 직접)")
        else:
            mark("Wi-Fi는 시스템이 복원 (wifi_on=%s)" % device.setting("wifi_on"))
        t_reach = recover_network(t_restore)
        delivered, info = wait_delivery(p + "p1-", 10, t_reach)
        counts = received_count_by_name(p + "p1-")
        dup = {k: v for k, v in counts.items() if v > 1}
        return (ok0 and leaked == 0 and delivered), (
            "LAN 경로=%s, 오프라인 probe=%s, 오프라인 중 서버 도착=%d건(0이어야 함), 10건 전달=%s (%s), 재전송 중복 도착=%s"
            % (ok0, offline_probe, leaked, delivered, info, dup or "없음"))

    def p2_wifi_to_mobile():
        precheck()
        device.wifi(False)
        mark("Wi-Fi 끔")
        on_mobile = wait_until(lambda: not device.wifi_connected(), 30, 1)
        time.sleep(3)
        network = device.default_network()
        mobile_probe = device.probe(LAN_HOST, "mobile")
        device.start(action="track", prefix=p + "p2", count=5)
        time.sleep(10)
        leaked = len(stored_names(p + "p2-"))
        t_restore = time.time()
        device.wifi(True)
        mark("Wi-Fi 켬")
        t_reach = recover_network(t_restore)
        delivered, info = wait_delivery(p + "p2-", 5, t_reach)
        return (on_mobile and leaked == 0 and delivered), (
            "Wi-Fi 끔→기본 네트워크=%s, LTE probe=%s, LTE에서 로컬 서버 도달=%d건(0이어야 함), 5건 전달=%s (%s)"
            % (network, mobile_probe, leaked, delivered, info))

    def p3_background_upload():
        precheck()
        STATE.mode = "unavailable"
        device.start(action="track", prefix=p + "p3", count=5)
        wait_until(lambda: any(r["status"] == 503 for r in STATE.snapshot()["requests"]), 30)
        device.home()
        time.sleep(1)
        STATE.mode = "normal"
        delivered = wait_until(lambda: len(stored_names(p + "p3-")) == 5, 120, 1)
        alive = device.alive()
        return delivered and alive, "홈으로 보낸 뒤(프로세스 생존=%s) 백그라운드에서 재시도로 5건 전달=%s" % (alive, delivered)

    def p4_background_kill():
        precheck()
        STATE.mode = "unavailable"
        device.start(action="track", prefix=p + "p4", count=5)
        attempted = lambda: {i for r in STATE.snapshot()["requests"] if r["status"] == 503 for i in r["ids"]}
        wait_until(lambda: len(attempted()) >= 5, 30)
        first = attempted()
        device.home()
        time.sleep(2)
        device.shell("am kill " + PACKAGE)  # 백그라운드 프로세스만 종료 (저메모리 종료 모사)
        dead = wait_until(lambda: not device.alive(), 10)
        STATE.mode = "normal"
        device.start()
        delivered = wait_until(lambda: len(stored_names(p + "p4-")) == 5, 60)
        same = stored_ids(p + "p4-") == first
        return dead and delivered and same, "백그라운드에서 am kill(종료=%s) → 재실행 후 5건 전달=%s, 같은 messageId=%s" % (dead, delivered, same)

    def p5_doze():
        """
        Doze 중 대기와 해제 후 재전송 (물리 기기 보고서 10.4, 11장).

        - 서버는 Doze 진입 후 SETTLE초까지 503을 유지한다. 진입 순간에 이미 나간 요청이 저장되어
          "Doze 중 전달"로 잘못 집계되는 경쟁을 없애기 위해서다 (10.4 debug에서 실제로 발생).
        - Doze 동안의 서버 수신과 SDK 네트워크 실패 로그는 **관찰**로만 기록한다 (판정에 쓰지 않음).
        - PASS 조건: Doze 해제 뒤, 앱을 다시 실행하지 않은 같은 프로세스가 5건을 **해제 이후의 요청으로**
          전달하고, messageId가 503 때 시도한 것과 같다.
        - 해제 후 재전송이 없으면: 프로세스 freeze가 기록되어 있고 재실행 후 같은 messageId로 전달되면 LIMIT
          (v1 설계 범위: 프로세스가 실행되지 않으면 재시도 타이머도 실행되지 않음), 그 밖은 FAIL.
        - Doze 중에 이미 모두 전달되어 해제 후 재전송을 확인할 수 없으면 REVIEW (PASS 아님).
        """
        SETTLE = 5
        precheck()
        STATE.mode = "unavailable"
        device.start(action="track", prefix=p + "p5", count=5)
        attempted = lambda: {i for r in STATE.snapshot()["requests"] if r["status"] == 503 for i in r["ids"]}
        wait_until(lambda: len(attempted()) >= 5, 30)
        first = attempted()
        device.home()
        idle_offset = os.path.getsize(ctx["log_path"])
        device.shell("dumpsys battery unplug")
        device.shell("dumpsys deviceidle force-idle")
        t_idle = time.time()
        mark("Doze 강제 진입 (서버는 %d초 동안 503 유지)" % SETTLE)
        idle_state = device.shell("dumpsys deviceidle get deep").strip()
        if idle_state != "IDLE":
            raise EnvProblem("Doze(deep)에 들어가지 않음: %s" % idle_state)
        time.sleep(SETTLE)
        STATE.mode = "normal"
        mark("서버 정상 응답으로 전환 (Doze 유지 중)")
        time.sleep(40 - SETTLE)
        log_offset = os.path.getsize(ctx["log_path"])
        device.shell("dumpsys deviceidle unforce")
        device.shell("dumpsys battery reset")
        t_release = time.time()
        mark("Doze 해제")

        def ok_requests(t_from, t_to=None):
            return [r for r in STATE.snapshot()["requests"]
                    if r["status"] == 200 and r["t"] >= t_from and (t_to is None or r["t"] < t_to)
                    and any(n.startswith(p + "p5-") for n in r.get("names", []))]

        during = {i for r in ok_requests(t_idle, t_release) for i in r["ids"]}
        with open(ctx["log_path"], "rb") as f:  # Doze 진입부터 해제까지의 SDK 로그 (바이트 오프셋)
            f.seek(idle_offset)
            doze_log = [l for l in f.read(log_offset - idle_offset).decode("utf-8", "replace").splitlines()
                        if "MiniAnalytics" in l and "Upload failed (network)" in l]
        blocked = len(doze_log)

        resent = wait_until(lambda: {i for r in ok_requests(t_release) for i in r["ids"]} >= first, DELIVERY_WAIT, 1)
        after = {i for r in ok_requests(t_release) for i in r["ids"]}
        same = stored_ids(p + "p5-") == first
        base = ("Doze(deep=%s) 진입 후 %d초 503, Doze 중 서버 수신(200) %d/5 (관찰), Doze 중 SDK 네트워크 실패 로그 %d줄 "
                "(관찰), 해제 후 같은 프로세스의 재전송 %d/5" % (idle_state, SETTLE, len(during), blocked, len(after & first)))
        if resent:
            done_at = min(r["t"] for r in ok_requests(t_release)) - t_release
            return same, base + " (+%.0fs), 같은 messageId=%s" % (done_at, same)

        with open(ctx["log_path"], "rb") as f:  # Doze 해제 이후의 로그
            f.seek(log_offset)
            frozen = re.findall(r"freezing (\d+) " + re.escape(PACKAGE), f.read().decode("utf-8", "replace"))
        if during >= first:
            return False, base + " → Doze 중 이미 전달되어 해제 후 재전송을 확인할 수 없음", "REVIEW"
        diagnose(p + "p5-")
        device.start()
        relaunched = wait_until(lambda: len(stored_names(p + "p5-")) == 5, 60)
        same = stored_ids(p + "p5-") == first
        detail = base + " → %d초 안에 재전송 없음, 해제 후 프로세스 freeze 기록 %s, 앱 재실행 후 전달=%s, 같은 messageId=%s" % (
            DELIVERY_WAIT, ("있음(pid %s)" % ",".join(sorted(set(frozen)))) if frozen else "없음", relaunched, same)
        if frozen and relaunched and same:
            return False, detail + " (v1 설계 범위: freeze 중 재시도 타이머 미실행)", "LIMIT"
        return False, detail

    if suite == "physical":
        scenario("P1 비행기 모드: 끊김 중 저장·오프라인 재시작·복구 후 전달", p1_airplane)
        scenario("P2 Wi-Fi→LTE 전환 후 Wi-Fi 복구", p2_wifi_to_mobile)
        scenario("P3 백그라운드 전환 중 재시도 전송", p3_background_upload)
        scenario("P4 백그라운드 프로세스 종료 후 복원", p4_background_kill)
        scenario("P5 Doze 중 대기와 해제 후 전달", p5_doze)
        scenario("S9 StrictMode", s9_strictmode_alive)
        device.force_stop()
        return results

    scenario("S1 TLS(Android HttpURLConnection) 전달", s1_tls_delivery)
    scenario("S2 Java API", s2_java_api)
    scenario("S3 identify/reset 콜백과 로그아웃 흐름", s3_identify_reset)
    scenario("S4 프로세스 종료 후 SQLite 복원과 같은 messageId", s4_process_death)
    scenario("S5 리다이렉트 미추적(Android)", s5_redirect)
    scenario("S6 단건 격리 100건", lambda: isolation(100, 50))
    scenario("S7 격리 중 429/503", lambda: isolation(30, 10, flaky=True))
    scenario("S8 격리 중 추가된 이벤트 보존", lambda: isolation(20, 5, delay=0.3, late=5))
    scenario("S9 StrictMode", s9_strictmode_alive)
    device.force_stop()
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial")
    parser.add_argument("--variant", action="append", choices=["debug", "release"])
    parser.add_argument("--serve-only", action="store_true")
    parser.add_argument("--out", default=os.path.join(HERE, "results"))
    parser.add_argument("--extra-ip", action="append", default=[], help="인증서 SAN에 추가할 호스트 IP (물리 기기 LAN 방식)")
    parser.add_argument("--prepare-cert-only", action="store_true")
    parser.add_argument("--restore-cert", action="store_true", help="--extra-ip로 만든 임시 인증서를 원래대로 복원")
    parser.add_argument("--reverse", action="store_true", help="adb reverse tcp:8443 (물리 기기 A안)")
    parser.add_argument("--suite", choices=["basic", "physical"], default="basic")
    parser.add_argument("--only", action="append", help="실행할 시나리오 ID (예: --only P1)")
    args = parser.parse_args()

    if args.restore_cert:
        restore_certificate()
        return
    if args.prepare_cert_only:
        ensure_certificate(args.extra_ip)
        print("인증서 SAN:", subprocess.run(["openssl", "x509", "-in", CERT, "-noout", "-ext", "subjectAltName"],
                                            capture_output=True, text=True).stdout.strip())
        return

    global LAN_HOST
    LAN_HOST = args.extra_ip[0] if args.extra_ip else None
    server = start_server(args.extra_ip)
    print("계약 서버: https://0.0.0.0:%d (에뮬레이터: https://10.0.2.2:%d)" % (PORT, PORT), flush=True)
    if args.serve_only:
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
        return

    device = Device(args.serial)
    apks = {
        "debug": os.path.join(ROOT, "sample/build/outputs/apk/debug/sample-debug.apk"),
        "release": os.path.join(ROOT, "sample/build/outputs/apk/release/sample-release.apk"),
    }
    original = {
        "airplane_mode_on": device.shell("settings get global airplane_mode_on").strip(),
        "wifi_on": device.shell("settings get global wifi_on").strip(),
        "mobile_data": device.shell("settings get global mobile_data").strip(),
        "stay_on_while_plugged_in": device.shell("settings get global stay_on_while_plugged_in").strip(),
    }
    print("기기 원래 상태:", original, flush=True)
    if args.reverse:
        device.adb("reverse", "tcp:%d" % PORT, "tcp:%d" % PORT)
    if args.suite == "physical":  # 긴 대기 중 화면 꺼짐·잠금으로 시나리오가 흔들리지 않도록 (종료 시 원래 값으로)
        device.shell("svc power stayon usb")
        device.shell("input keyevent KEYCODE_WAKEUP")
    all_results = []
    try:
        for variant in args.variant or ["debug"]:
            print("== API %d / %s / %s" % (device.api, variant, args.suite), flush=True)
            device.install(apks[variant])
            all_results += run_scenarios(device, variant, args.suite, args.only, args.out)
    finally:
        device.adb("uninstall", PACKAGE, check=False)
        if args.reverse:
            device.adb("reverse", "--remove", "tcp:%d" % PORT, check=False)
        if args.suite == "physical":  # 네트워크·배터리 상태를 원래대로 되돌린다
            device.shell("cmd connectivity airplane-mode " + ("enable" if original["airplane_mode_on"] == "1" else "disable"))
            device.shell("svc wifi " + ("disable" if original["wifi_on"] == "0" else "enable"))
            device.shell("dumpsys deviceidle unforce")
            device.shell("dumpsys battery reset")
            device.shell("settings put global stay_on_while_plugged_in " + original["stay_on_while_plugged_in"])
            restored = {k: device.shell("settings get global " + k).strip() for k in original}
            print("기기 복원 상태:", restored, "(원래와 같음)" if restored == original else "(다름! 확인 필요)", flush=True)
        server.shutdown()

    os.makedirs(args.out, exist_ok=True)
    name = "api%d" % device.api if device.serial.startswith("emulator-") else "physical-%s-api%d" % (device.serial, device.api)
    path = os.path.join(args.out, "%s-%s.json" % (name, args.suite) if args.suite != "basic" or not device.serial.startswith("emulator-") else name + ".json")
    with open(path, "w") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)
    failed = [r for r in all_results if not r["ok"]]
    by_status = {k: sum(1 for r in all_results if r.get("status") == k) for k in ("PASS", "FAIL", "REVIEW", "ENV", "LIMIT")}
    print("결과: %d개 중 %d개 통과 %s → %s" % (len(all_results), len(all_results) - len(failed), by_status, path), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
