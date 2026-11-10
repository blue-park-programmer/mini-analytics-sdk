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

    def snapshot(self):
        with self.lock:
            return {
                "requests": list(self.requests),
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
                STATE.requests.append({"t": now, "path": self.path, "ids": ids, "status": status, "bytes": len(raw)})

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
            return self._send(307, b"", {"Location": "https://10.0.2.2:%d/v1/redirected" % PORT})

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


def ensure_certificate():
    if os.path.exists(KEY) and os.path.exists(CERT):
        return
    subprocess.run([
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
        "-subj", "/CN=mini-analytics local test",
        "-addext", "subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost",
        "-addext", "basicConstraints=critical,CA:TRUE",
        "-keyout", KEY, "-out", CERT,
    ], check=True, capture_output=True)
    shutil.copyfile(CERT, SAMPLE_CA)
    print("새 인증서를 만들었습니다. sample 앱을 다시 빌드하세요:", SAMPLE_CA)


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


def start_server():
    ensure_certificate()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT, KEY)
    server = TlsServer(("0.0.0.0", PORT), Handler, context)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


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
            marker = "action=%s prefix=" % extras["action"]
            if not wait_until(lambda: marker in self.log(), 15):
                raise RuntimeError("앱이 action=%s를 실행하지 않았습니다" % extras["action"])

    def force_stop(self):
        self.shell("am force-stop " + PACKAGE)

    def alive(self):
        # API 23의 `ps -A`는 헤더만 출력하고 성공(0)으로 끝난다. 그래서 `ps`와 `ps -A`를 모두 보고 합친다
        # (7단계 API 23 첫 실행에서 생존 확인이 항상 거짓으로 나왔다).
        out = self.shell("ps; ps -A 2>/dev/null")
        return any(line.split()[-1] == PACKAGE for line in out.splitlines() if line.strip())

    def clear_log(self):
        self.adb("logcat", "-c", check=False)

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


def run_scenarios(device, variant):
    results = []
    tag = "%s-api%d" % (variant, device.api)

    def scenario(name, fn):
        STATE.reset()
        device.clear_log()
        started = time.time()
        try:
            ok, detail = fn()
        except Exception as e:  # 시나리오 오류는 실패로 기록
            ok, detail = False, "error: %r" % (e,)
        log = device.log()
        # 샘플 앱 프로세스의 로그만 본다 (시스템 프로세스의 StrictMode 로그는 제외).
        pids = set(re.findall(r"I/MiniAnalyticsSample\(\s*(\d+)\)", log))
        own = [l for l in log.splitlines() if any(re.search(r"\(\s*%s\):" % pid, l) for pid in pids)]
        strict = [l for l in own if "StrictMode" in l and "violation" in l.lower()]
        crash = [l for l in own if "FATAL EXCEPTION" in l]
        crash += [l for l in log.splitlines() if "Process: " + PACKAGE in l]
        if strict or crash:
            ok = False
            detail += " | StrictMode/crash: %s" % ((strict + crash)[:3],)
        results.append({"variant": variant, "api": device.api, "scenario": name, "ok": ok,
                        "seconds": round(time.time() - started, 1), "detail": detail})
        print(("  PASS " if ok else "  FAIL ") + name + " — " + detail, flush=True)

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
    args = parser.parse_args()

    server = start_server()
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
    all_results = []
    for variant in args.variant or ["debug"]:
        print("== API %d / %s" % (device.api, variant), flush=True)
        device.install(apks[variant])
        all_results += run_scenarios(device, variant)
    device.adb("uninstall", PACKAGE, check=False)
    server.shutdown()

    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, "api%d.json" % device.api)
    with open(path, "w") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)
    failed = [r for r in all_results if not r["ok"]]
    print("결과: %d개 중 %d개 통과 → %s" % (len(all_results), len(all_results) - len(failed), path), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
