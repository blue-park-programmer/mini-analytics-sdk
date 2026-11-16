#!/usr/bin/env python3
"""
Step 8 Microbenchmark(:benchmark, androidx.benchmark) 실행 자동화. SDK는 바꾸지 않는다.

  ./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository
  python3 tools/benchmark/run_micro.py --serial <serial> [--repeat 1]

순서: 테스트 APK 설치(gradle) → AOT 컴파일(`cmd package compile -f -m speed`, androidx.benchmark의
NOT-AOT-COMPILED 검사를 끄지 않고 만족시킨다) → `am instrument` 실행 → 기기의 결과 JSON 수집 → 요약 → 테스트 APK 제거.
결과: tools/benchmark/results/micro-<시각>/ (git 제외)
"""
import argparse
import glob
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
TEST_PKG = "io.github.dduddupark.minianalytics.benchmark.test"
RUNNER = TEST_PKG + "/androidx.benchmark.junit4.AndroidBenchmarkRunner"
DEVICE_OUT = "/sdcard/Android/media/" + TEST_PKG


def sh(cmd, env=None, timeout=1800):
    r = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=timeout)
    return r.returncode, r.stdout + r.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--repeat", type=int, default=1, help="전체 테스트 묶음을 몇 번 반복할지(각각 별도 JSON)")
    ap.add_argument("--test-class", default="io.github.dduddupark.minianalytics.benchmark.TrackBenchmark")
    args = ap.parse_args()
    adb = ["adb", "-s", args.serial]
    out_dir = os.path.join(HERE, "results", "micro-" + time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out_dir)

    code, out = sh(adb + ["shell", "dumpsys window | grep -oE 'isKeyguardShowing=[a-z]+' | head -1"])
    if "isKeyguardShowing=true" in out:
        sys.exit("기기가 잠겨 있습니다.")
    env = dict(os.environ, ANDROID_SERIAL=args.serial)
    code, out = sh([os.path.join(ROOT, "gradlew"), "-p", ROOT, "-q", ":benchmark:installReleaseAndroidTest"], env=env)
    if code != 0:
        sys.exit("설치 실패:\n" + out[-2000:])
    try:
        code, out = sh(adb + ["shell", "cmd package compile -f -m speed " + TEST_PKG])
        print("AOT 컴파일:", out.strip().splitlines()[-1] if out.strip() else code, flush=True)
        results = []
        for i in range(args.repeat):
            sh(adb + ["shell", "rm -rf " + DEVICE_OUT])
            thermal = sh(adb + ["shell", "dumpsys thermalservice | grep -m1 'Thermal Status'"])[1].strip()
            code, out = sh(adb + ["shell", "am instrument -w -e class %s %s" % (args.test_class, RUNNER)])
            with open(os.path.join(out_dir, "instrument-%d.txt" % i), "w") as f:
                f.write(out)
            ok = "OK (" in out
            pulled = os.path.join(out_dir, "device-%d" % i)
            sh(adb + ["pull", DEVICE_OUT, pulled])
            files = glob.glob(os.path.join(pulled, "**", "*benchmarkData.json"), recursive=True)
            print("반복 %d: %s, %s, JSON %d개" % (i, "OK" if ok else "실패", thermal, len(files)), flush=True)
            for path in files:
                data = json.load(open(path))
                for b in data.get("benchmarks", []):
                    m = b.get("metrics", {})
                    t = m.get("timeNs", {})
                    a = m.get("allocationCount", {})
                    results.append({"repeat": i, "name": b.get("name"), "thermalBefore": thermal,
                                    "time_median_ns": t.get("median"), "time_min_ns": t.get("minimum"),
                                    "time_max_ns": t.get("maximum"), "time_cv": t.get("coefficientOfVariation"),
                                    "alloc_median": a.get("median"), "repeatIterations": b.get("repeatIterations")})
        with open(os.path.join(out_dir, "summary.json"), "w") as f:
            json.dump(results, f, ensure_ascii=False, indent=1)
        lines = ["| 반복 | 테스트 | median ns | min ns | max ns | CV | 할당(median) |", "|---|---|---|---|---|---|---|"]
        for r in sorted(results, key=lambda r: (r["name"], r["repeat"])):
            lines.append("| %d | %s | %s | %s | %s | %s | %s |" % (
                r["repeat"], r["name"], r["time_median_ns"], r["time_min_ns"], r["time_max_ns"],
                ("%.3f" % r["time_cv"]) if isinstance(r["time_cv"], float) else r["time_cv"], r["alloc_median"]))
        with open(os.path.join(out_dir, "summary.md"), "w") as f:
            f.write("\n".join(lines) + "\n")
        print("\n".join(lines))
    finally:
        sh(adb + ["shell", "rm -rf " + DEVICE_OUT])
        sh(adb + ["uninstall", TEST_PKG])
        print("결과:", out_dir, flush=True)


if __name__ == "__main__":
    main()
