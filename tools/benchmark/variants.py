"""
개선 전후 비교용 변형 빌드 (Step 8). run_store.py와 run_host.py가 함께 쓴다.

변형마다 SDK main 소스를 스냅샷 디렉터리(<dir>/main)로 바꿔 AAR을 배포하고 benchmark-host release를 빌드해
APK를 따로 보관한다. 끝나면 작업 트리의 SDK main 소스를 원래대로 되돌리고 AAR을 다시 배포한다.
"""
import hashlib
import os
import shutil
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SDK_MAIN = os.path.join(ROOT, "mini-analytics/src/main")
HOST_APK = os.path.join(ROOT, "benchmark-host/build/outputs/apk/release/benchmark-host-release.apk")


def _gradle(*args):
    r = subprocess.run([os.path.join(ROOT, "gradlew"), "-p", ROOT, "-q", *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("gradle %s 실패:\n%s%s" % (" ".join(args), r.stdout[-2000:], r.stderr[-2000:]))


def build_variant_apks(variants, out_dir, endpoint=None):
    """variants: [(label, snapshot_dir)]. endpoint가 있으면 -PbenchEndpoint로 빌드한다(저장 검증 서버용)."""
    apks = {}
    backup = os.path.join(out_dir, "_worktree_main_backup")
    shutil.copytree(SDK_MAIN, backup)
    try:
        for label, src in variants:
            shutil.rmtree(SDK_MAIN)
            shutil.copytree(os.path.join(src, "main"), SDK_MAIN)
            _gradle(":mini-analytics:publishReleasePublicationToLocalBuildRepository")
            host = [":benchmark-host:assembleRelease", "--refresh-dependencies"]
            if endpoint:
                host.append("-PbenchEndpoint=" + endpoint)
            _gradle(*host)
            dst = os.path.join(out_dir, "apk-%s.apk" % label)
            shutil.copyfile(HOST_APK, dst)
            dex = subprocess.run(["unzip", "-p", dst, "classes.dex"], capture_output=True).stdout
            apks[label] = {"path": dst, "dexSha256": hashlib.sha256(dex).hexdigest()[:16]}
            print("변형 %s 빌드: dex %s" % (label, apks[label]["dexSha256"]), flush=True)
    finally:
        shutil.rmtree(SDK_MAIN)
        shutil.copytree(backup, SDK_MAIN)
        shutil.rmtree(backup)
        subprocess.run([os.path.join(ROOT, "gradlew"), "-p", ROOT, "-q",
                        ":mini-analytics:publishReleasePublicationToLocalBuildRepository"], capture_output=True)
    return apks
