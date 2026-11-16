#!/usr/bin/env python3
"""
Step 8 2단계: SQLite 저장 처리량·commit 지연·배경 CPU 측정 실행기 (:benchmark-host, StoreBench). SDK는 바꾸지 않는다.

  ./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository
  python3 tools/benchmark/run_store.py --serial <serial> --runs 5

동작
1. 검증 서버용 임시 인증서를 만든다(LAN IP를 SAN에 추가, 원본은 백업). 끝나면 원래대로 복원한다.
2. benchmark-host release를 `-PbenchEndpoint=https://<LAN IP>:8443/v1/batch`로 빌드한다(그 호스트만 테스트 CA 신뢰).
3. 로컬 검증 서버(tools/device-verification/verify.py)를 띄운다.
4. 라운드마다 시나리오 순서를 섞어 실행한다(실행마다 pm clear, 새 프로세스).
   측정 중에는 업로드가 일어나지 않게 flushAt=10,000, flush 주기 1시간으로 둔다.
   store_evict만 cap=1,000(flushAt은 cap 이하여야 하므로 1,000)이라 측정 중 업로드 시도가 생길 수 있어,
   그동안 서버를 503으로 두고 앱이 업로드 단계에 들어가면 정상으로 바꾼다.
5. 측정 뒤 앱이 flush()를 반복해 모두 업로드하면, 서버가 받은 seq로 저장 건수를 독립 검증한다.
결과: tools/benchmark/results/store-<시각>/ (git 제외)
"""
import argparse
import json
import os
import random
import re
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "tools/device-verification"))
import verify  # noqa: E402  (같은 저장소의 검증 서버)
sys.path.insert(0, HERE)
from variants import build_variant_apks  # noqa: E402

PKG = "io.github.dduddupark.minianalytics.benchhost"
APK = os.path.join(ROOT, "benchmark-host/build/outputs/apk/release/benchmark-host-release.apk")
SCENARIOS = ["store_burst", "store_commit", "store_sparse_cpu", "store_evict", "store_drop"]


def adb(serial, *args, check=True, timeout=120):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError("adb failed: %s\n%s%s" % (" ".join(args), r.stdout, r.stderr))
    return r.stdout


def state(serial):
    out = adb(serial, "shell", "dumpsys window | grep -oE 'isKeyguardShowing=[a-z]+' | head -1; "
                               "dumpsys thermalservice | grep -m1 'Thermal Status'; "
                               "dumpsys battery | grep -E '^  temperature:'; df /data | tail -1", check=False)
    s = " ".join(out.split())
    m = re.search(r"Thermal Status: (\d+)", s)
    t = re.search(r"temperature: (\d+)", s)
    return {"locked": "isKeyguardShowing=true" in s, "thermal": int(m.group(1)) if m else None,
            "batteryTempC": int(t.group(1)) / 10 if t else None, "raw": s}


def server_pattern():
    seqs = []
    for e in verify.STATE.snapshot()["stored"].values():
        if e.get("event") == "bench_store":
            p = e.get("properties") or {}
            if "seq" in p:
                seqs.append(int(p["seq"]))
    s = sorted(set(seqs))
    if not s:
        return {"rows": 0}
    return {"rows": len(seqs), "distinct": len(s), "minSeq": s[0], "maxSeq": s[-1],
            "missingInterior": (s[-1] - s[0] + 1) - len(s)}


def run_one(serial, scenario, timeout=900):
    cap = 1_000 if scenario == "store_evict" else 10_000
    verify.STATE.reset()
    verify.STATE.mode = "unavailable" if scenario == "store_evict" else "normal"
    adb(serial, "shell", "pm clear %s" % PKG, check=False)
    adb(serial, "logcat", "-c", check=False)
    adb(serial, "shell", "am start -W -n %s/%s.BenchActivity --es scenario %s --es endpoint lan --ei cap %d "
                         "--ei flushAt %d --el flushIntervalMs 3600000" % (PKG, PKG, scenario, cap, cap))
    deadline = time.time() + timeout
    switched = False
    while time.time() < deadline:
        log = adb(serial, "logcat", "-d", "-s", "MiniAnalyticsBench:I", check=False)
        if not switched and "UPLOAD_PHASE" in log:
            verify.STATE.mode = "normal"
            switched = True
        if "DONE" in log:
            m = re.search(r"RESULT (\{.*\})", log)
            adb(serial, "shell", "am force-stop %s" % PKG, check=False)
            r = json.loads(m.group(1)) if m else {"error": "no RESULT line"}
            r["server"] = server_pattern()
            r["serverRequests"] = {str(k): v for k, v in _statuses().items()}
            return r
        time.sleep(1)
    adb(serial, "shell", "am force-stop %s" % PKG, check=False)
    return {"scenario": scenario, "error": "timeout", "server": server_pattern()}


def _statuses():
    out = {}
    for r in verify.STATE.snapshot()["requests"]:
        out[r["status"]] = out.get(r["status"], 0) + 1
    return out


def fit(xs, ys):
    """최소제곱 직선 y = a + b x."""
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx if sxx else 0
    return my - b * mx, b


def summarize(rows):
    ok = [r for r in rows if "error" not in r]
    med = lambda v: statistics.median(v) if v else None
    rng = lambda v: (min(v), max(v)) if v else None
    out = {}
    burst = [r for r in ok if r["scenario"] == "store_burst"]
    if burst:
        per_size = {}
        fits = []
        for r in burst:
            xs, ys = [], []
            for b in r["bursts"]:
                corr = max(1, b["processedNanos"] - b["mainPostLatencyNanos"])
                d = per_size.setdefault(b["n"], {"callPerSec": [], "processedMs": [], "processedPerSec": [],
                                                  "bgCpuUsPerEvent": [], "mainPostUs": []})
                d["callPerSec"].append(b["n"] / (b["callNanos"] / 1e9))
                d["processedMs"].append(b["processedNanos"] / 1e6)
                d["processedPerSec"].append(b["n"] / (corr / 1e9))
                d["bgCpuUsPerEvent"].append(b["bgCpuNanos"] / 1e3 / b["n"])
                d["mainPostUs"].append(b["mainPostLatencyNanos"] / 1e3)
                xs.append(b["n"]); ys.append(corr / 1e6)
            a, s = fit(xs, ys)
            fits.append({"interceptMs": a, "msPerEvent": s})
        out["store_burst"] = {
            "runs": len(burst),
            "bySize": {n: {k: {"median": med(v), "range": rng(v)} for k, v in d.items()} for n, d in sorted(per_size.items())},
            "fitPerRun": fits,
            "stored_vs_issued": [(r["db"].get("rows"), r["issued"]) for r in burst],
            "dbPattern": [r["db"] for r in burst], "serverPattern": [r["server"] for r in burst],
            "uploadRemaining": [r["upload"]["remainingTracks"] for r in burst]}
    commit = [r for r in ok if r["scenario"] == "store_commit"]
    if commit:
        out["store_commit"] = {gap: {k: {q: {"median": med([r["commit"][gap][k][q] / 1e3 for r in commit]),
                                             "range": rng([r["commit"][gap][k][q] / 1e3 for r in commit])}
                                         for q in ("p50", "p95", "p99")}
                                     for k in ("identifyToCallbackNanos", "mainPostLatencyNanos", "bgCpuPerCommitNanos")}
                               for gap in ("0", "100", "500")}
        out["store_commit"]["runs"] = len(commit)
    sp = [r for r in ok if r["scenario"] == "store_sparse_cpu"]
    if sp:
        out["store_sparse_cpu"] = {
            "runs": len(sp),
            "bgCpuUsPerEvent": {"median": med([r["sparseCpu"]["bgCpuPerEventNanos"] / 1e3 for r in sp]),
                                "range": rng([r["sparseCpu"]["bgCpuPerEventNanos"] / 1e3 for r in sp])},
            "callerCpuUsPerEvent": {"median": med([r["sparseCpu"]["callerCpuPerEventNanos"] / 1e3 for r in sp]),
                                    "range": rng([r["sparseCpu"]["callerCpuPerEventNanos"] / 1e3 for r in sp])},
            "idleBgCpuMsPer30s": [round(r["sparseCpu"]["idleBgCpuNanos"] / 1e6, 1) for r in sp],
            "dbPattern": [r["db"] for r in sp], "serverPattern": [r["server"] for r in sp]}
    for name in ("store_evict", "store_drop"):
        rs = [r for r in ok if r["scenario"] == name]
        if rs:
            out[name] = {"runs": len(rs), "dbPattern": [r["db"] for r in rs], "serverPattern": [r["server"] for r in rs],
                         "serverStatuses": [r["serverRequests"] for r in rs],
                         "uploadRemaining": [r["upload"]["remainingTracks"] for r in rs]}
            if name == "store_drop":
                out[name]["callPerSec"] = [round(r["drop"]["n"] / (r["drop"]["callNanos"] / 1e9)) for r in rs]
                out[name]["processedMs"] = [round(r["drop"]["processedNanos"] / 1e6, 1) for r in rs]
    out["errors"] = [r for r in rows if "error" in r]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--lan-ip", default=None)
    ap.add_argument("--seed", type=int, default=20261113)
    ap.add_argument("--cooldown", type=float, default=5.0)
    ap.add_argument("--only", action="append", choices=SCENARIOS)
    ap.add_argument("--variant", action="append", default=[],
                    help="개선 전후 비교: label=<SDK main 스냅샷을 담은 디렉터리> (여러 번)")
    ap.add_argument("--final-only", action="append", default=[], choices=SCENARIOS,
                    help="마지막 변형에서만 실행할 시나리오 (정확성 확인용)")
    args = ap.parse_args()
    ip = args.lan_ip or subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True).stdout.strip()
    if not ip:
        sys.exit("LAN IP를 찾지 못했습니다 (--lan-ip)")
    first = state(args.serial)
    if first["locked"]:
        sys.exit("기기가 잠겨 있습니다. 잠금을 해제한 뒤 다시 실행하세요.")
    scenarios = args.only or SCENARIOS
    out_dir = os.path.join(HERE, "results", "store-" + time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out_dir)
    meta = {"runs": args.runs, "seed": args.seed, "scenarios": scenarios, "start_state": first["raw"], "order": []}
    rows = []
    server = None
    original_stay = adb(args.serial, "shell", "settings get global stay_on_while_plugged_in", check=False).strip()
    meta["original_stay_on"] = original_stay
    # 잠금 확인 직후 바로 화면을 켜 둔다. 빌드하는 동안 화면이 꺼져 잠기면 모든 실행이 건너뛰어진다(1차 실행에서 발생).
    adb(args.serial, "shell", "svc power stayon usb", check=False)
    try:
        verify.ensure_certificate([ip])  # 임시 인증서 (원본 백업)
        if args.variant:
            variants = [tuple(v.split("=", 1)) for v in args.variant]
            apks = build_variant_apks(variants, out_dir, endpoint="https://%s:8443/v1/batch" % ip)
            meta["variants"] = {k: v["dexSha256"] for k, v in apks.items()}
            labels = [v[0] for v in variants]
        else:
            b = subprocess.run([os.path.join(ROOT, "gradlew"), "-p", ROOT, "-q", ":benchmark-host:assembleRelease",
                                "-PbenchEndpoint=https://%s:8443/v1/batch" % ip], capture_output=True, text=True)
            if b.returncode != 0:
                raise RuntimeError("빌드 실패:\n" + b.stdout[-2000:] + b.stderr[-2000:])
            apks = {"default": {"path": APK}}
            labels = ["default"]
        server = verify.start_server([ip])
        rng = random.Random(args.seed)
        installed = None
        for run in range(args.runs):
            order = [(lb, sc) for lb in labels for sc in scenarios]
            order += [(labels[-1], sc) for sc in args.final_only]
            rng.shuffle(order)  # 변형과 시나리오를 섞어 열 상태 편향을 줄인다
            meta["order"].append(["%s/%s" % o for o in order])
            for label, sc in order:
                st = state(args.serial)
                if st["locked"]:
                    rows.append({"scenario": sc, "variant": label, "run": run, "error": "기기 잠김", "stateBefore": st})
                    print("SKIP locked", flush=True)
                    continue
                if installed != label:
                    adb(args.serial, "install", "-r", apks[label]["path"], timeout=300)
                    installed = label
                r = run_one(args.serial, sc)
                r.update(run=run, variant=label, stateBefore=st)
                rows.append(r)
                print("r%d %-8s %-16s db=%s server=%s upload_remaining=%s thermal=%s %s" % (
                    run, label, sc, r.get("db"), r.get("server"), (r.get("upload") or {}).get("remainingTracks"),
                    st["thermal"], r.get("error", "")), flush=True)
                time.sleep(args.cooldown)
    finally:
        adb(args.serial, "uninstall", PKG, check=False)
        adb(args.serial, "shell", "settings put global stay_on_while_plugged_in %s" % (original_stay or "0"), check=False)
        meta["restored_stay_on"] = adb(args.serial, "shell", "settings get global stay_on_while_plugged_in", check=False).strip()
        if server:
            server.shutdown()
        verify.restore_certificate()  # 임시 인증서를 원래대로
        meta["end_state"] = state(args.serial)["raw"]
        with open(os.path.join(out_dir, "raw.jsonl"), "w") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        labels_seen = sorted({r.get("variant", "default") for r in rows})
        summary = {lb: summarize([r for r in rows if r.get("variant", "default") == lb]) for lb in labels_seen}
        with open(os.path.join(out_dir, "summary.json"), "w") as f:
            json.dump({"meta": meta, "summary": summary}, f, ensure_ascii=False, indent=1, default=str)
        print("결과:", out_dir, flush=True)


if __name__ == "__main__":
    main()
