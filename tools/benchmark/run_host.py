#!/usr/bin/env python3
"""
Step 8 track() 호출 지연·할당 측정 실행기 (:benchmark-host). SDK는 바꾸지 않는다.

  ./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository
  ./gradlew :benchmark-host:assembleDebug :benchmark-host:assembleRelease
  python3 tools/benchmark/run_host.py --serial <serial> --runs 5            # 1차 본 측정 기본 조건
  python3 tools/benchmark/run_host.py --serial <serial> --runs 1 --quick    # 동작 확인

- debug(패키지 접미사 .debug)와 release를 함께 설치하고, 매 라운드 조건 순서를 섞어 실행한다(열 상태 편향 감소).
- 실행마다 앱 데이터를 지우고(`pm clear`) 새 프로세스에서 조건 하나를 실행한다. 표본은 실행(프로세스) 단위로만
  백분위를 계산하고, 여러 실행의 원시 표본을 합치지 않는다. 요약은 "실행별 백분위의 실행 간 중앙값과 범위"다.
- 기기가 잠겨 있으면 측정하지 않는다(물리 기기 보고서 12장).
- 결과: tools/benchmark/results/<시각>/raw.jsonl, summary.json, summary.md (git 제외)
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
BASE_PKG = "io.github.dduddupark.minianalytics.benchhost"
PKG = {"release": BASE_PKG, "debug": BASE_PKG + ".debug"}
APK = os.path.join(ROOT, "benchmark-host/build/outputs/apk/%s/benchmark-host-%s.apk")

# (variant, scenario, props): 1차 본 측정 조건. debug는 대표 조건만.
DEFAULT_CONDITIONS = (
    [("release", "normal", p) for p in ("none", "p5", "p20", "p4k")]
    + [("release", "saturated", p) for p in ("none", "p5", "p20", "p4k")]
    + [("release", "overhead", "none"), ("release", "normal_spin", "p5"), ("release", "normal_main", "p5"),
       ("release", "normal_prewake", "p5"), ("release", "normal_cpuprobe", "p5"),
       ("debug", "normal", "p5"), ("debug", "saturated", "p5")]
)
# 간헐 호출 계획: (variant, scenario, props, intervalMs). 같은 세션 비교용으로 normal·saturated를 함께 넣는다.
SPARSE_CONDITIONS = [("release", "sparse", "p5", 100), ("release", "sparse", "p5", 500), ("release", "sparse", "p5", 2000),
                     ("release", "sparse_cold", "p5", 500), ("release", "sparse_aot", "p5", 500),
                     ("release", "sparse_control", "p5", 500),
                     ("release", "normal", "p5", 0), ("release", "saturated", "p5", 0)]
SPARSE_SIZES = {100: (20, 1_000), 500: (5, 400), 2000: (3, 150)}  # interval → (warmup, measured)
SPARSE_COLD_SIZE = (0, 100)
# 메인 스레드 간헐 호출: (variant, scenario, props, intervalMs, ui). SDK 호출과 대조군을 같은 조건으로.
MAINSPARSE_CONDITIONS = [("release", sc, "p5", iv, ui) for sc in ("main_sparse", "main_sparse_control")
                         for iv in (100, 500) for ui in ("idle", "anim")]
MAINSPARSE_SIZES = {100: (20, 500), 500: (5, 120)}
SPARSE_DIAG_SIZE = (3, 100)  # sparse_aot, sparse_control
# 조건 이름 → (앱에 넘길 시나리오, 실행 전 AOT 컴파일 여부)
LABELS = {"sparse_aot": ("sparse", True)}

# 한 프로세스의 track 호출은 9,000건 이하(maxQueuedEvents 10,000): DB 상한 정리가 생기지 않게.
SIZES = {"normal": (500, 5_000), "normal_spin": (500, 5_000), "normal_main": (500, 5_000),
         "normal_prewake": (500, 5_000), "normal_cpuprobe": (500, 5_000),
         "saturated": (2_000, 5_000), "overhead": (2_000, 20_000)}


# 개선 전후 비교 (메인 스레드 간헐 호출): (label, scenario, props, intervalMs, ui). label은 --variant로 준다.
COMPARE_CONDITIONS = [("main_sparse", "p5", 100, "idle"), ("main_sparse", "p5", 100, "anim")]


def adb(serial, *args, check=True, timeout=120):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError("adb failed: %s\n%s%s" % (" ".join(args), r.stdout, r.stderr))
    return r.stdout


def device_state(serial):
    out = adb(serial, "shell", "dumpsys window | grep -oE 'isKeyguardShowing=[a-z]+' | head -1; "
                               "dumpsys thermalservice | grep -m1 'Thermal Status'; "
                               "dumpsys battery | grep -E '^  (level|temperature|AC powered|USB powered):'",
              check=False)
    s = " ".join(out.split())
    m = re.search(r"Thermal Status: (\d+)", s)
    t = re.search(r"temperature: (\d+)", s)
    return {"locked": "isKeyguardShowing=true" in s, "thermal": int(m.group(1)) if m else None,
            "batteryTempC": int(t.group(1)) / 10 if t else None, "raw": s}


def run_one(serial, variant, scenario, props, warmup, measured, interval=0, ui="none", timeout=900):
    pkg = PKG.get(variant, BASE_PKG)  # 비교 변형은 모두 release 패키지
    adb(serial, "shell", "pm clear %s" % pkg, check=False)
    adb(serial, "logcat", "-c", check=False)
    adb(serial, "shell", "am start -W -n %s/%s.BenchActivity --es scenario %s --es props %s --ei measured %d --ei warmup %d"
        " --ei interval %d --es ui %s" % (pkg, BASE_PKG, scenario, props, measured, warmup, interval, ui))
    deadline = time.time() + timeout
    while time.time() < deadline:
        log = adb(serial, "logcat", "-d", "-s", "MiniAnalyticsBench:I", check=False)
        if "DONE" in log:
            m = re.search(r"RESULT (\{.*\})", log)
            adb(serial, "shell", "am force-stop %s" % pkg, check=False)
            return json.loads(m.group(1)) if m else {"error": "no RESULT line"}
        time.sleep(1)
    adb(serial, "shell", "am force-stop %s" % pkg, check=False)
    return {"error": "timeout"}


def across(rows, path):
    vals = []
    for r in rows:
        v = r
        for k in path:
            v = v.get(k, {}) if isinstance(v, dict) else {}
        if isinstance(v, (int, float)):
            vals.append(v)
    if not vals:
        return None
    return {"median": statistics.median(vals), "min": min(vals), "max": max(vals), "runs": len(vals)}


def summarize(rows):
    groups = {}
    for r in rows:
        if "error" not in r:
            name = r["scenario"] + ("@%dms" % r["intervalMs"] if r.get("intervalMs") else "") \
                + ("/ui=%s" % r["ui"] if r.get("ui") not in (None, "none") else "")
            groups.setdefault((r["variant"], name, r["props"]), []).append(r)
    out = []
    for key, rs in sorted(groups.items()):
        item = {"variant": key[0], "scenario": key[1], "props": key[2], "runs": len(rs),
                "n_per_run": [x["latencyNanos"]["n"] for x in rs]}
        for q in ("p50", "p95", "p99", "max"):
            item[q] = across(rs, ["latencyNanos", q])
        item["perRun"] = [{k: x.get(k) for k in ("run", "notStored", "trackCalls", "allocPerCall", "allocBytesPerCall",
                                                 "gcCountDelta", "blockingGcDelta", "firstCallNanos", "initializeNanos")}
                          | {"thermalBefore": x["stateBefore"]["thermal"], "batteryTempC": x["stateBefore"]["batteryTempC"],
                             "p50": x["latencyNanos"]["p50"], "p95": x["latencyNanos"]["p95"], "p99": x["latencyNanos"]["p99"]}
                          for x in rs]
        if "byPosition" in rs[0]:
            item["byPosition"] = {pos: {q: across(rs, ["byPosition", pos, q]) for q in ("p50", "p95", "p99")}
                                  for pos in ("pos0", "pos1to9", "pos10to49")}
            item["top1pct"] = [x["top1pct"] for x in rs]
            item["gapNanos"] = across(rs, ["gapNanos", "p50"])
            item["cpu"] = [x["cpu"] for x in rs]
        if "mainLatenessNanos" in rs[0]:
            item["mainLateness"] = {q: across(rs, ["mainLatenessNanos", q]) for q in ("p50", "p95", "p99")}
        if "frames" in rs[0]:
            item["frames"] = {"totalDuration": {q: across(rs, ["frames", "totalDuration", q]) for q in ("p50", "p95", "p99")},
                              "overBudget": [x["frames"]["overBudget"] for x in rs],
                              "frameCount": [x["frames"]["totalDuration"]["n"] for x in rs],
                              "refreshHz": rs[0]["frames"]["refreshHz"]}
        if "threadCpuNanos" in rs[0]:
            item["threadCpu"] = {q: across(rs, ["threadCpuNanos", q]) for q in ("p50", "p95", "p99")}
            item["wallMinusCpu"] = {q: across(rs, ["wallMinusCpuNanos", q]) for q in ("p50", "p95", "p99")}
        if "cpuProbe" in rs[0]:
            item["cpuProbe"] = {k: {q: across(rs, ["cpuProbe", k, q]) for q in ("p50", "p95", "p99")}
                                for k in ("pos0Wall", "pos0ThreadCpu", "pos25Wall", "pos25ThreadCpu")}
        out.append(item)
    return out


def to_md(summary, meta):
    us = lambda ns: "%.1f" % (ns / 1000.0)
    cell = lambda a: "%s (%s–%s)" % (us(a["median"]), us(a["min"]), us(a["max"])) if a else "-"
    lines = ["# track() 1차 본 측정 요약", "", "```", json.dumps(meta, ensure_ascii=False, indent=1), "```", "",
             "실행별 백분위의 **실행 간 중앙값 (최소–최대)**, 단위 µs. 여러 실행의 원시 표본은 합치지 않았다.", "",
             "| variant | scenario | props | 실행 | 실행당 n | p50 | p95 | p99 | 할당/호출 | bytes/호출 | 미저장/호출 | GC(blocking) | 열 상태(실행 전) |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for s in summary:
        pr = s["perRun"]
        lines.append("| %s | %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            s["variant"], s["scenario"], s["props"], s["runs"], sorted(set(s["n_per_run"])),
            cell(s["p50"]), cell(s["p95"]), cell(s["p99"]),
            sorted({round(x["allocPerCall"], 1) for x in pr if x["allocPerCall"] is not None}) or "-",
            sorted({round(x["allocBytesPerCall"]) for x in pr if x["allocBytesPerCall"] is not None}) or "-",
            ["%s/%s" % (x["notStored"], x["trackCalls"]) for x in pr] if pr[0]["notStored"] is not None else "-",
            ["%s(%s)" % (x["gcCountDelta"], x["blockingGcDelta"]) for x in pr],
            sorted({x["thermalBefore"] for x in pr})))
    pos = [s for s in summary if "byPosition" in s]
    if pos:
        lines += ["", "## 묶음 안 순번별 (normal 계열, 실행 간 중앙값 µs)", "",
                  "| variant | scenario | props | pos0 p50/p95/p99 | pos1–9 p50/p95/p99 | pos10–49 p50/p95/p99 | 상위1% 중 pos0 / pos<10 (균등 기대) | gap p50 µs |",
                  "|---|---|---|---|---|---|---|---|"]
        for s in pos:
            bp = s["byPosition"]
            f = lambda p: "/".join(us(bp[p][q]["median"]) for q in ("p50", "p95", "p99"))
            t = s["top1pct"]
            lines.append("| %s | %s | %s | %s | %s | %s | %s / %s (%.0f / %.0f) | %s |" % (
                s["variant"], s["scenario"], s["props"], f("pos0"), f("pos1to9"), f("pos10to49"),
                sum(x["pos0"] for x in t), sum(x["posLt10"] for x in t),
                sum(x["expectedPos0IfUniform"] for x in t), sum(x["expectedPosLt10IfUniform"] for x in t),
                us(s["gapNanos"]["median"]) if s["gapNanos"] else "-"))
    sp = [s for s in summary if "threadCpu" in s]
    if sp:
        lines += ["", "## 간헐 호출: 호출 스레드 CPU 시간과 실행되지 않은 시간 (실행 간 중앙값 µs)", "",
                  "| scenario | 경과 p50/p95/p99 | 스레드 CPU p50/p95/p99 | 경과−CPU p50/p95/p99 |", "|---|---|---|---|"]
        for s in sp:
            g = lambda d: "/".join(us(d[q]["median"]) for q in ("p50", "p95", "p99"))
            lines.append("| %s | %s | %s | %s |" % (s["scenario"], g({q: s[q] for q in ("p50", "p95", "p99")}),
                                                  g(s["threadCpu"]), g(s["wallMinusCpu"])))
    ms = [s for s in summary if "mainLateness" in s]
    if ms:
        lines += ["", "## 메인 스레드 간헐 호출: 메인 스레드 지연과 프레임 (실행 간 중앙값 µs)", "",
                  "| 조건 | 호출 p50/p95/p99 | 메인 지연 p50/p95/p99 | 프레임 시간 p50/p95/p99 | 기준 초과 프레임/전체 (실행별) |",
                  "|---|---|---|---|---|"]
        for s in ms:
            g = lambda d: "/".join(us(d[q]["median"]) if d.get(q) else "-" for q in ("p50", "p95", "p99"))
            fr = s.get("frames")
            lines.append("| %s | %s | %s | %s | %s |" % (
                s["scenario"], g({q: s[q] for q in ("p50", "p95", "p99")}), g(s["mainLateness"]),
                g(fr["totalDuration"]) if fr else "-",
                ["%d/%d" % (o, n) for o, n in zip(fr["overBudget"], fr["frameCount"])] if fr else "-"))
    probes = [s for s in summary if "cpuProbe" in s]
    if probes:
        lines += ["", "## 스레드 CPU 시간 대 경과 시간 (normal_cpuprobe, 실행 간 중앙값 µs)", "",
                  "| variant | props | pos0 경과 p50/p95/p99 | pos0 스레드 CPU p50/p95/p99 | pos25 경과 p50/p95/p99 | pos25 스레드 CPU p50/p95/p99 |",
                  "|---|---|---|---|---|---|"]
        for s in probes:
            cp = s["cpuProbe"]
            f = lambda k: "/".join(us(cp[k][q]["median"]) for q in ("p50", "p95", "p99"))
            lines.append("| %s | %s | %s | %s | %s | %s |" % (s["variant"], s["props"], f("pos0Wall"),
                                                               f("pos0ThreadCpu"), f("pos25Wall"), f("pos25ThreadCpu")))
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--cooldown", type=float, default=5.0)
    ap.add_argument("--seed", type=int, default=20261112)
    ap.add_argument("--quick", action="store_true", help="표본 수를 1/10로 줄여 동작만 확인")
    ap.add_argument("--plan", choices=["main", "sparse", "mainsparse", "compare"], default="main")
    ap.add_argument("--variant", action="append", default=[], help="--plan compare: label=<SDK main 스냅샷 디렉터리>")
    args = ap.parse_args()
    apks = {v: {"path": APK % (v, v)} for v in ("release", "debug")}
    if args.plan == "sparse":
        conditions = [c + ("none",) for c in SPARSE_CONDITIONS]
    elif args.plan == "mainsparse":
        conditions = list(MAINSPARSE_CONDITIONS)
    elif args.plan == "compare":
        if not args.variant:
            sys.exit("--plan compare에는 --variant label=dir가 필요합니다")
        variants = [tuple(v.split("=", 1)) for v in args.variant]
        conditions = [(lb,) + c for lb, _ in variants for c in COMPARE_CONDITIONS]
    else:
        conditions = [c + (0, "none") for c in DEFAULT_CONDITIONS]
    sizes = {k: (w, (m // 10 // 50) * 50 if args.quick else m) for k, (w, m) in SIZES.items()}

    first = device_state(args.serial)
    if first["locked"]:
        sys.exit("기기가 잠겨 있습니다. 잠금을 해제한 뒤 다시 실행하세요: " + first["raw"])
    out_dir = os.path.join(HERE, "results", time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out_dir)
    if args.plan == "compare":
        sys.path.insert(0, HERE)
        from variants import build_variant_apks
        built = build_variant_apks(variants, out_dir)
        apks = {k: {"path": v["path"]} for k, v in built.items()}
    meta = {"serial_masked": args.serial[:3] + "…", "plan": args.plan, "runs": args.runs, "seed": args.seed,
            "variants": {k: v.get("path", "").rsplit("/", 1)[-1] for k, v in apks.items()},
            "sizes(warmup,measured)": sizes, "sparseSizes": SPARSE_SIZES if args.plan == "sparse" else None,
            "start_state": first["raw"], "order": []}
    rows = []
    rng = random.Random(args.seed)
    original_stay = adb(args.serial, "shell", "settings get global stay_on_while_plugged_in", check=False).strip()
    meta["original_stay_on"] = original_stay
    try:
        # 측정이 길어도 화면 꺼짐·잠금으로 앱 상태가 바뀌지 않게 한다 (끝나면 원래 값으로)
        adb(args.serial, "shell", "svc power stayon usb", check=False)
        installed = None
        if args.plan != "compare":
            for variant in sorted({c[0] for c in conditions}):
                adb(args.serial, "install", "-r", apks[variant]["path"], timeout=300)
        for run in range(args.runs):
            order = conditions[:]
            rng.shuffle(order)  # 라운드마다 조건 순서를 섞는다
            meta["order"].append(["%s/%s/%s/%s/%s" % c for c in order])
            for variant, scenario, props, interval, ui in order:
                st = device_state(args.serial)
                if st["locked"]:
                    rows.append({"variant": variant, "scenario": scenario, "props": props, "run": run,
                                 "error": "기기 잠김: 실행하지 않음", "stateBefore": st})
                    print("SKIP locked", flush=True)
                    continue
                if args.plan == "compare" and installed != variant:
                    adb(args.serial, "install", "-r", apks[variant]["path"], timeout=300)  # 실행 전에 그 변형을 설치
                    installed = variant
                app_scenario, aot = LABELS.get(scenario, (scenario, False))
                if scenario == "sparse":
                    warmup, measured = SPARSE_SIZES[interval]
                elif scenario == "sparse_cold":
                    warmup, measured = SPARSE_COLD_SIZE
                elif scenario in ("sparse_aot", "sparse_control"):
                    warmup, measured = SPARSE_DIAG_SIZE
                elif scenario.startswith("main_sparse"):
                    warmup, measured = MAINSPARSE_SIZES[interval]
                else:
                    warmup, measured = sizes[scenario]
                st["dexopt"] = " ".join(adb(args.serial, "shell", "dumpsys package dexopt | grep -A2 '\\[%s\\]' | "
                                            "grep -oE 'status=[a-z-]+' | head -1" % PKG.get(variant, BASE_PKG), check=False).split())
                if aot:  # 이 실행만 AOT(speed) 컴파일하고, 끝나면 설치 상태로 되돌린다
                    adb(args.serial, "shell", "cmd package compile -f -m speed %s" % PKG.get(variant, BASE_PKG), check=False)
                    st["dexopt"] = " ".join(adb(args.serial, "shell", "dumpsys package dexopt | grep -A2 '\\[%s\\]' | "
                                                "grep -oE 'status=[a-z-]+' | head -1" % PKG.get(variant, BASE_PKG), check=False).split())
                try:
                    r = run_one(args.serial, variant, app_scenario, props, warmup, measured, interval, ui)
                finally:
                    if aot:
                        adb(args.serial, "shell", "cmd package compile --reset %s" % PKG.get(variant, BASE_PKG), check=False)
                r["scenario"] = scenario
                r.update(variant=variant, run=run, stateBefore=st)
                rows.append(r)
                lat = r.get("latencyNanos", {})
                print("r%d %-7s %-11s@%-4s %-4s n=%s p50=%s p95=%s p99=%s notStored=%s gc=%s thermal=%s %s" % (
                    run, variant, scenario, interval, props, lat.get("n"), lat.get("p50"), lat.get("p95"), lat.get("p99"),
                    r.get("notStored", "-"), r.get("gcCountDelta"), st["thermal"], r.get("error", "")), flush=True)
                time.sleep(args.cooldown)
    finally:
        for pkg in PKG.values():
            adb(args.serial, "uninstall", pkg, check=False)
        adb(args.serial, "shell", "settings put global stay_on_while_plugged_in %s" % (original_stay or "0"), check=False)
        meta["restored_stay_on"] = adb(args.serial, "shell", "settings get global stay_on_while_plugged_in", check=False).strip()
        meta["end_state"] = device_state(args.serial)["raw"]
        with open(os.path.join(out_dir, "raw.jsonl"), "w") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        summary = summarize(rows)
        with open(os.path.join(out_dir, "summary.json"), "w") as f:
            json.dump({"meta": meta, "summary": summary}, f, ensure_ascii=False, indent=1)
        with open(os.path.join(out_dir, "summary.md"), "w") as f:
            f.write(to_md(summary, meta))
        print("결과:", out_dir, flush=True)


if __name__ == "__main__":
    main()
