# v1 물리 기기 검증 보고서

- 작성일: 2026-11-10
- 기준 커밋: `9d99539` (feat: implement mini analytics SDK v1)
- 이 문서의 LAN 주소(`192.168.0.10` = 검증 서버를 띄운 Mac, `192.168.0.21`·`192.168.0.22` = 실기기)는 **예시 주소**입니다. 실제 사설 주소는 공개 전에 바꿨으며, 주소 사이의 관계(같은 서브넷, 실행마다 기기 주소가 달랐던 점)는 실제와 같습니다.
- **상태: 물리 기기 검증 완료 (Galaxy S23, Android 16 / API 36).**
  - A안 기본 시나리오: debug 9/9, R8 release 9/9 (8장)
  - B안 네트워크·백그라운드 시나리오: P1~P4·S9는 debug·R8 release 모두 PASS (10장)
  - **P5(Doze)는 PASS가 아닙니다.** 새 P5 도구로 실기기에서 다시 실행한 결과(11.8, 2026-11-12) debug·R8 release 모두 **LIMIT**입니다. Doze 해제 30초 뒤 백그라운드 프로세스가 freeze되어 재시도가 없었고, 앱 재실행 후 같은 messageId로 전달되었습니다(v1 설계 범위의 제약, 유실 없음). 2026-11-10 실행의 재판정은 debug REVIEW, release LIMIT입니다(11.1)
  - **미해결 (12장)**: 2026-11-12 P1 재실행 1회가 FAIL했습니다. 네트워크가 복구되고 앱이 전면에 있었는데도 약 4분 동안 SDK 요청이 서버에 도달하지 않았습니다. **원인 미확정**이며 SDK 코드는 바꾸지 않았습니다.
  - 7장과 9장은 중단 당시의 기록입니다. 9장의 P1 실패 원인은 10장에서 확정했습니다.

## 1. 연결 상태 확인 결과 (2026-11-10)
| 확인 | 결과 |
|---|---|
| `adb devices -l` | 연결된 기기 없음 |
| USB 장치 목록 (`system_profiler SPUSBDataType`) | Android 기기 없음 |
| 무선 디버깅 mDNS (`adb mdns services`) | `adb-EMULATOR…` 2건. 이름과 주소(10.0.2.15/17, 에뮬레이터 내부망)로 보아 **에뮬레이터가 남긴 항목**이며 물리 기기가 아님 |
| 실행 중인 에뮬레이터 | 없음 |

## 2. 물리 기기 연결 방법

### USB 디버깅
1. 기기에서 **설정 → 휴대전화 정보 → 빌드 번호**를 7번 눌러 개발자 옵션을 켭니다.
2. **설정 → 개발자 옵션 → USB 디버깅**을 켭니다.
3. USB 케이블로 Mac에 연결하고, 기기에 뜨는 "USB 디버깅 허용" 대화상자에서 이 컴퓨터를 허용합니다.
4. 연결을 확인합니다.
   ```bash
   export PATH=~/Library/Android/sdk/platform-tools:$PATH
   adb devices -l          # 상태가 "device"여야 함. "unauthorized"면 기기에서 허용
   ```

### 무선 디버깅 (Android 11 / API 30 이상)
1. Mac과 기기를 **같은 Wi-Fi**에 연결합니다.
2. **개발자 옵션 → 무선 디버깅**을 켜고 **페어링 코드로 기기 페어링**을 누릅니다.
3. Mac에서 페어링한 뒤 연결합니다.
   ```bash
   adb pair <기기IP>:<페어링 포트>      # 화면의 6자리 코드 입력
   adb connect <기기IP>:<연결 포트>     # 무선 디버깅 화면에 표시된 포트
   adb devices -l
   ```
- 주의: 무선 디버깅은 Wi-Fi를 끄면 adb 연결도 끊깁니다. **네트워크 끊김 시나리오는 USB 연결로 진행**해야 합니다(4.4절).

### 물리 기기인지 확인
```bash
adb -s <serial> shell getprop ro.kernel.qemu            # 물리 기기는 비어 있음 (에뮬레이터는 1)
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.release  # Android 버전
adb -s <serial> shell getprop ro.build.version.sdk      # API 레벨
```

## 3. 검증 도구 준비 (실행 전에 필요한 조정, 아직 적용하지 않음)
현재 `sample`과 `tools/device-verification/verify.py`는 **에뮬레이터 전용**입니다.
- endpoint가 `https://10.0.2.2:8443`(에뮬레이터에서 보이는 호스트 주소)로 고정되어 있습니다.
- network security config도 `10.0.2.2`만 신뢰합니다.

물리 기기에서는 이 주소가 동작하지 않습니다. 아래 두 방법 중 하나가 필요합니다. 둘 다 **검증 도구와 sample 앱의 변경**이며, SDK 코드는 바꾸지 않습니다. 적용하기 전에 승인을 받겠습니다.

| 방법 | 내용 | 장점 | 한계 |
|---|---|---|---|
| A. `adb reverse` | `adb -s <serial> reverse tcp:8443 tcp:8443`. sample endpoint를 `https://127.0.0.1:8443/v1/batch`로 바꾸고, network security config의 domain에 `127.0.0.1`을 추가. 인증서 SAN에는 이미 `127.0.0.1`이 있음 | 같은 Wi-Fi가 필요 없고, 사내망·방화벽 영향이 없음 | 트래픽이 USB(adb)를 통과하므로 **기기의 Wi-Fi·모바일 네트워크를 끊어도 서버에 계속 연결됨** → 네트워크 끊김 시나리오에는 쓸 수 없음 |
| B. LAN 주소 | Mac의 Wi-Fi IP(예: `192.168.x.y`)를 endpoint로 쓰고, 인증서 SAN과 network security config에 그 IP를 추가 (인증서 재생성 후 sample 재빌드) | 실제 기기 네트워크를 거침 → 네트워크 끊김과 복구를 검증할 수 있음 | 같은 Wi-Fi 필요. 공유기의 기기 간 격리나 macOS 방화벽(8443 수신 허용)을 확인해야 함 |

**권장**: 기본 시나리오는 A로, 네트워크 끊김과 복구는 B로 진행합니다. endpoint를 빌드할 때 바꿀 수 있도록 sample `build.gradle.kts`에 `-PsampleEndpoint=...` 같은 속성을 추가하는 정도의 변경이 필요합니다.

## 4. 검증 절차

### 4.1 공통 준비
```bash
cd mini-analytics-sdk
./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository
./gradlew :sample:assembleDebug :sample:assembleRelease --refresh-dependencies
adb -s <serial> logcat -c
adb -s <serial> logcat -b crash -c
```
기기 정보를 기록합니다: 모델, Android 버전, API 레벨, 제조사 스킨(One UI 등), 배터리 최적화 설정.

### 4.2 기본 시나리오 (`verify.py` 재사용)
3장의 조정을 적용한 뒤 실행합니다.
```bash
python3 tools/device-verification/verify.py --serial <serial> --variant debug --variant release
```
| 항목 | 해당 시나리오 |
|---|---|
| 기본 이벤트 수집, TLS | S1, S2, S3 |
| SQLite 영속성, 프로세스 종료 후 복원 | S4 (`am force-stop` 뒤 재시작해서 같은 messageId 전송) |
| 리다이렉트 | S5 |
| 단건 격리 | S6 ~ S8 (sample은 `trustInvalidEventResponses(true)`) |
| StrictMode | S1 ~ S9 전체 (`penaltyDeath`) |
| **R8 release** | 위 전체를 `--variant release`로 |

### 4.3 백그라운드 전환 (수동 + adb)
1. 서버를 정상 상태로 두고 앱을 실행한 뒤 `track` 3건을 호출합니다.
2. 홈으로 보냅니다: `adb shell input keyevent KEYCODE_HOME`
3. 백그라운드에서 이벤트를 추가합니다(서비스 없이 Activity로만 가능하므로, 백그라운드 직전에 `track`을 여러 건 호출한 뒤 전환).
4. 확인 항목:
   - 프로세스가 살아 있는 동안 Uploader가 `flushInterval`(5초) 주기로 계속 전송하는지
   - 앱이 백그라운드에서 종료된 뒤 다시 실행하면 남은 이벤트를 전송하는지
5. Doze 강제 (선택):
   ```bash
   adb shell dumpsys battery unplug
   adb shell dumpsys deviceidle force-idle
   # 앱이 살아 있는 동안 전송이 늦어지거나 멈추는지 관찰 → 해제
   adb shell dumpsys deviceidle unforce
   adb shell dumpsys battery reset
   ```
   - 설계상 SDK는 백그라운드 전송을 보장하지 않습니다(Q22). 확인 대상은 "유실 없이 다음 실행에서 전송되는지"입니다.

### 4.4 네트워크 끊김과 복구 (USB 연결 + 3장 B 방식 필수)
1. 앱을 실행하고 `track` 5건을 호출해서 정상 전송을 확인합니다.
2. 네트워크를 끊습니다.
   ```bash
   adb shell cmd connectivity airplane-mode enable   # API 30 이상
   # 또는: adb shell svc wifi disable; adb shell svc data disable
   ```
3. `track` 10건을 호출합니다 → 서버에 도착하지 않아야 하고, 기기에는 저장되어야 합니다(logcat의 `Upload failed (network)`).
4. (선택) 이 상태에서 `am force-stop` → 다시 실행해서 영속성도 함께 확인합니다.
5. 네트워크를 복구합니다.
   ```bash
   adb shell cmd connectivity airplane-mode disable
   ```
6. 확인 항목:
   - backoff(최대 5분) 뒤 또는 `flush()`/주기 trigger로 10건이 **같은 messageId로** 전달되는지
   - 중복 없이 한 번씩 저장되는지
   - Wi-Fi ↔ 모바일 데이터 전환 중 요청 실패가 일시적 실패로 처리되는지

### 4.5 StrictMode, Crash, ANR 확인
```bash
PID=$(adb shell pidof io.github.dduddupark.minianalytics.sample)
adb logcat -d --pid=$PID | grep -E "StrictMode|policy violation"   # 앱 PID만
adb logcat -d -b crash                                               # Crash 버퍼
adb logcat -d | grep -E "ANR in io.github.dduddupark.minianalytics.sample|FATAL EXCEPTION"
adb shell dumpsys activity anr 2>/dev/null | head -50                # API 30 이상
adb bugreport ./bugreport-<serial>.zip                               # ANR 추적이 필요할 때 (/data/anr는 일반 권한으로 읽을 수 없음)
```
- `penaltyDeath` 위반이 있으면 프로세스가 종료되므로, 시나리오가 실패하고 crash 버퍼에 기록이 남습니다.

### 4.6 문제가 발견되면
- SDK 코드를 바꾸지 않고, **재현 절차, 기기 정보, logcat(앱 PID), 서버 요청 기록**을 이 문서에 먼저 기록해서 보고합니다.

## 5. 결과 (미실시)
| 영역 | 항목 | 결과 |
|---|---|---|
| 기본 | 이벤트 수집과 TLS 전달 (debug) | **미검증** |
| 영속성 | SQLite 저장, 프로세스 종료 후 같은 messageId 복원 | **미검증** |
| 네트워크 | 끊김 중 저장, 복구 후 전달, 중복 없음 | **미검증** |
| 백그라운드 | 홈 전환, 백그라운드 종료 후 재실행, Doze | **미검증** |
| R8 | release 빌드 전체 시나리오 | **미검증** |
| 안정성 | StrictMode 위반, Crash, ANR | **미검증** |

에뮬레이터(API 23·33·36)에서 검증한 결과는 `v1-step7-report.md`와 `v1-final-report.md`에 있습니다. 그 결과는 물리 기기 검증을 대신하지 않습니다.

## 6. 다음 단계
1. 물리 기기를 연결합니다 (2장).
2. 3장의 검증 도구 조정(A, 그리고 네트워크 시나리오용 B)을 승인받아 적용합니다. 이 조정은 sample 앱과 검증 도구에만 해당하며, SDK 코드는 바꾸지 않습니다.
3. 4장 절차를 실행하고 5장을 채웁니다.

---

## 7. 중단: 물리 기기에서 sample 앱 반복 종료 분석 (2026-11-10)

### 7.1 상황
| 항목 | 값 |
|---|---|
| 기기 | Samsung Galaxy S23 **SM-S911N** (물리 기기, `ro.kernel.qemu=0`), Android 16 (**API 36**), One UI 8.5 (`ro.build.version.oneui=80500`), 빌드 `BP4A.251205.006.S911NKSS8FZI1`, 보안 패치 2026-09-05 |
| 연결 | USB (`adb devices -l`: `<serial> device usb:… model:SM_S911N`) |
| 실행 | A안(`adb reverse tcp:8443`), `verify.py --suite basic --variant debug --variant release` |
| 중단 시점 | **debug** 변형의 S1~S8 진행 중 (release는 시작하지 않음). 사용자 요청으로 도구를 중지함 |
| SDK 코드 | 커밋 `9d99539`와 동일 (`git diff --quiet HEAD -- mini-analytics/src` 확인) |

### 7.2 Crash 기록
logcat crash 버퍼에서 확인한 내용입니다(새로 실행하지 않고 기존 버퍼를 덤프함).
```
11-10 12:12:23.184  6858  6858 E AndroidRuntime: FATAL EXCEPTION: main
Process: io.github.dduddupark.minianalytics.sample, PID: 6858
java.lang.RuntimeException: StrictMode ThreadPolicy violation
  at android.os.StrictMode$AndroidBlockGuardPolicy.onThreadPolicyViolation(StrictMode.java:1977)
  …
Caused by: android.os.strictmode.DiskReadViolation
  at android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1760)
  at libcore.io.BlockGuardOs.access(BlockGuardOs.java:74)
  at java.io.File.exists(File.java:829)
  at android.app.ContextImpl.getDataDir(ContextImpl.java:3635)
  at android.app.ContextImpl.getPreferencesDir(ContextImpl.java:824)
  at android.app.ContextImpl.getSharedPreferences(ContextImpl.java:655)
  at android.content.ContextWrapper.getSharedPreferences(ContextWrapper.java:243)
  at android.app.IdsController.getIdsSharedPreference(IdsController.java:172)   ← Samsung 프레임워크
  at android.app.IdsController.doIds(IdsController.java:138)
  at android.app.ActivityThread.scheduleVsyncSS(ActivityThread.java:6665)
  at android.app.ActivityThread.handleResumeActivity(ActivityThread.java:6835)  ← Activity resume
  at android.app.servertransaction.ResumeActivityItem.execute(…)
  …
11-10 12:12:23.209 ActivityManager: Process io.github.dduddupark.minianalytics.sample (pid 6858) has died: fg TOP
```
- 확인 결과
  - `Fatal signal`(네이티브 크래시): 0건
  - `ANR in io.github.dduddupark…`: 0건
  - 스택에 SDK(`io.github.dduddupark.minianalytics`)나 sample 코드의 프레임: **0개**
- 증거의 범위: `verify.py`가 시나리오마다 `logcat -c`로 버퍼를 비우기 때문에, **스택 전체는 마지막 1건만** 남아 있습니다. 앞선 시나리오 S1~S8의 실패 기록에도 매번 같은 위반(`DiskReadViolation`, `~duration=1~2 ms`)이 앱 PID에서 기록되어 있습니다. 그래서 같은 원인으로 판단했습니다(추정이며, 각 건의 스택 전체는 확보하지 못함).

### 7.3 원인
1. sample 앱은 `SampleApplication`에서 StrictMode ThreadPolicy(`detectDiskReads/Writes/Network` + **`penaltyDeath`**)를 켭니다. SDK가 메인 스레드에서 I/O를 하지 않는지 검증하기 위해서입니다.
2. 이 기기(Samsung One UI 8.5)는 **Activity가 resume될 때마다** 프레임워크의 `android.app.IdsController.doIds()`(Samsung 전용, AOSP에는 없음)가 메인 스레드에서 `getSharedPreferences()`를 호출합니다. 이 과정에서 `File.exists()` 디스크 읽기가 일어납니다.
3. 그 결과 StrictMode가 위반을 감지하고 `penaltyDeath`로 **앱 프로세스를 종료**합니다.
   - 검증 도구가 `am start`로 Activity를 띄울 때마다 같은 일이 반복됩니다 → 사용자가 본 "반복 강제 종료"입니다.
   - Samsung UI의 "앱이 계속 중지됨"도 같은 원인입니다.
4. **에뮬레이터(API 23/33/36)에서 재현되지 않은 이유**: AOSP 기반 system image에는 `IdsController`가 없습니다.

### 7.4 분류
| 구분 | 판단 | 근거 |
|---|---|---|
| SDK 내부 문제 | **아님** | 스택에 SDK 프레임이 없음. 위반은 Activity resume 중 Samsung 프레임워크 코드에서 발생. SDK 소스는 커밋과 동일 |
| sample 앱 문제 | **검증 설정 문제** | sample의 StrictMode `penaltyDeath`가 **OEM 프레임워크의 위반까지** 앱 종료로 만들어서, SDK 검증 목적을 넘어섬 |
| 테스트 도구 문제 | **부분적** | `verify.py`의 StrictMode 판정이 위반의 **스택을 보지 않고** 앱 PID의 모든 위반을 실패로 처리함. 그래서 프레임워크 위반과 SDK 위반을 구분하지 못함 |
| Samsung 프레임워크 | 위반의 발생 지점 | 앱이 제어할 수 없음. StrictMode를 엄격하게 쓰는 어떤 앱이든 이 기기에서는 같은 위반이 생길 수 있음 (SDK 사용 여부와 무관) |

### 7.5 의도적 종료와 실제 Crash의 구분
| 종류 | 발생 위치 | logcat 흔적 | 이번 버퍼 |
|---|---|---|---|
| **의도적 종료** | `verify.py`의 `device.force_stop()` (`am force-stop`): S4(종료 후 복원), S5(리다이렉트 후 재시작), S6~S8(격리 이벤트 누적 후, 그리고 회차마다), 각 변형 종료 시 | `ActivityManager: Force stopping …`. `FATAL EXCEPTION` 없음 | 남은 버퍼(약 40초)에는 0건 |
| **실제 Crash** | StrictMode `penaltyDeath` | `AndroidRuntime: FATAL EXCEPTION` + crash 버퍼 + `has died: fg TOP` | 1건 (위 7.2) |

### 7.6 debug와 R8 release
- 발생: **debug**에서 확인(설치된 패키지가 `DEBUGGABLE`, 실행 기록 `== API 36 / debug / basic`).
- release: 실행 전에 중단했으므로 **미확인**입니다. 위반이 프레임워크 코드에서 생기고 앱 코드(R8 대상)와 무관하므로, 같은 기기에서는 release에서도 같은 종료가 일어날 것으로 **예상**합니다(미검증).

### 7.7 재현 절차
1. Samsung One UI 8.5 (Android 16) 기기를 USB로 연결합니다.
2. 커밋 `9d99539`의 sample을 빌드하고 설치합니다(`-PsampleEndpoint`와 무관하게 재현됨).
3. `adb shell am start -n io.github.dduddupark.minianalytics.sample/.MainActivity`
4. → Activity resume 직후 `FATAL EXCEPTION: main … DiskReadViolation … IdsController.getIdsSharedPreference`
- (예상) SDK를 초기화하지 않고 StrictMode `penaltyDeath`만 켠 빈 Activity에서도 같은 일이 일어납니다. **아직 확인하지 않았습니다.**

### 7.8 중단 시점의 시나리오 결과 해석 (유효하지 않음)
- S1~S8이 모두 FAIL로 기록되었지만, 앱이 Activity resume 때마다 종료되었으므로 **SDK 동작의 판단 근거가 되지 않습니다.**
  - S2, S3의 콜백 로그 누락: 콜백이 post된 뒤 프로세스가 종료되었습니다.
  - S4의 "같은 messageId=False": 종료가 반복되면서 시도 기록이 섞였습니다.
  - S6~S8 미완료: 회차마다 앱이 죽었습니다.
- 다만 S1과 S5에서는 이벤트가 **HTTPS(adb reverse)로 서버에 전달**되었고, S5에서는 리다이렉트를 따라가지 않았습니다. 이것은 앱이 죽기 전의 동작이라 참고만 할 수 있습니다.

### 7.9 수정 방안 (SDK는 수정하지 않음, 승인 대기)
| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **F1 (권장)** | sample의 StrictMode를 **API 28+에서 `penaltyListener`**로 바꿉니다. 위반 스택에 `io.github.dduddupark.minianalytics`(SDK 또는 sample) 프레임이 있으면 앱을 종료(기존 `penaltyDeath`와 같은 효과)하고, 없으면(OEM·프레임워크) 로그에 `framework-violation`으로만 기록합니다. API 23~27은 지금처럼 `penaltyDeath`를 유지합니다. `verify.py`는 SDK 위반과 프레임워크 위반을 구분해서 보고합니다 | SDK가 메인 스레드에서 I/O를 하는지 검증하는 엄격함은 유지하고, 기기 고유의 소음만 분리 | sample 코드가 조금 늘어남. 스택으로 판단하므로, SDK가 프레임워크 API를 거쳐 간접적으로 I/O를 일으키는 경우에도 SDK 프레임이 스택에 남아 잡힘 |
| F2 | 물리 기기 실행에서만 `penaltyDeath`를 끄고 `penaltyLog`로 바꾼 뒤, 도구가 로그 스택을 분석 | 변경이 가장 작음 | 판정이 로그 파싱에 의존해서, 로그 버퍼가 순환하면 놓칠 수 있음 |
| F3 | StrictMode를 끄고 검증 | — | 메인 스레드 I/O 검증이 사라짐. 권장하지 않음 |

- 추가 확인 (승인 후): F1을 적용하기 전에 "SDK 없이 StrictMode만 켠 빈 Activity"로 7.7의 예상을 한 번 확인해서, SDK와 무관하다는 것을 직접 증명할 것을 제안합니다.

### 7.10 현재 기기 상태
- sample **debug** APK가 기기에 설치되어 있습니다. 도구가 중지되면서 정리 단계(`finally`)가 실행되지 않았습니다.
- `adb reverse tcp:8443 tcp:8443`이 아직 설정되어 있습니다.
- 비행기 모드, Wi-Fi, Doze, 배터리 설정은 **바꾸지 않았습니다**(physical 시나리오는 실행하지 않음).
- 정리는 `adb uninstall io.github.dduddupark.minianalytics.sample`, `adb reverse --remove tcp:8443`로 할 수 있습니다. 승인을 받은 뒤 진행합니다.

### 7.11 작업 트리 상태 (커밋하지 않음)
| 파일 | 변경 | 목적 |
|---|---|---|
| `sample/build.gradle.kts` | endpoint를 `-PsampleEndpoint`로 지정할 수 있게 함 | 물리 기기 A·B안 |
| `sample/src/main/res/xml/network_security_config.xml` | 테스트 CA 신뢰 범위를 `10.0.2.2` 도메인에서 sample 앱 전체(`base-config`)로 넓힘. 평문은 여전히 금지 | 127.0.0.1, LAN IP 사용 |
| `sample/src/main/res/raw/mock_ca.pem`, `tools/device-verification/mock_cert.pem` | SAN에 `192.168.0.10` 추가해서 재생성 (원본은 백업해 둠) | B안 |
| `tools/device-verification/verify.py` | `--extra-ip`, `--prepare-cert-only`, `--reverse`, `--suite physical`(P1~P5), 리다이렉트 Location에 `Host` 사용, ANR 감지 | 물리 기기 |
| `docs/design/v1-physical-device-report.md` | 이 문서 | — |
- 인증서와 키는 검증을 마친 뒤 원본으로 복원할 계획입니다(이 머신의 LAN IP가 커밋에 들어가지 않게 하기 위함).

---

## 8. 재개: 독립 재현, StrictMode 검증 개선, 기본 시나리오 결과 (2026-11-10)

### 8.1 독립 재현 실험 (SDK 없음) → **재현됨**
- 저장소 밖에 **SDK 의존성이 전혀 없는** 최소 앱(`io.github.dduddupark.strictprobe`, Java만 사용, APK 9KB, SDK·Kotlin 클래스 0개)을 만들었습니다. sample과 같은 ThreadPolicy(`detectDiskReads/Writes/Network + penaltyLog + penaltyDeath`)를 Application에서 켜고, 빈 Activity 하나만 있습니다.
- Galaxy S23에서 `am start`를 실행한 결과
  - 순서: `application created (no SDK)` → `onCreate` → `onResume` → 약 130ms 뒤 **`FATAL EXCEPTION: main … DiskReadViolation`**
  - 스택: `IdsController.getIdsSharedPreference ← IdsController.doIds ← ActivityThread.scheduleVsyncSS ← handleResumeActivity` (7.2와 같음)
- 결론: **SDK와 무관하게 이 기기에서 Activity resume 때마다 발생하는 Samsung 프레임워크의 위반**임이 확인되었습니다. 7장의 원인 분석이 맞았습니다.
- 증거: `tools/device-verification/results/physical/probe-crash.txt`, `probe-main.txt` (git 제외 경로)

### 8.2 StrictMode 검증 방식 개선 (sample과 검증 도구만, SDK 변경 없음)
| 항목 | 내용 |
|---|---|
| API 28 이상 | sample이 `penaltyDeath` 대신 **`penaltyListener`(OnThreadViolationListener)**를 씁니다. 위반마다 `MiniAnalyticsStrict` 태그로 `#N begin <위반 타입>`, 스택 프레임 전부(`#N at …`), `#N end`를 기록하고, 앱은 종료하지 않습니다 |
| API 23~27 | 리스너 API가 없으므로 **기존 정책(`penaltyLog + penaltyDeath`)을 유지**합니다. 에뮬레이터 API 23에서는 위반이 0건이었습니다. 같은 범위의 OEM 기기에서 같은 문제가 생기면 쓸 대안: `penaltyDeath` 대신 `penaltyLog`만 켜고, 검증 도구가 `StrictMode` 로그의 스택을 같은 분류기로 판정 |
| 분류 (`verify.py`) | **OURS**: 스택에 `io.github.dduddupark.minianalytics` 프레임이 하나라도 있음(SDK·sample. SDK가 동기적으로 호출한 외부 라이브러리의 위반도 SDK 프레임이 남으므로 포함) → **시나리오 FAIL**. **FRAMEWORK**: 모든 프레임이 플랫폼 패키지(`android.`, `com.android.`, `java.`, `javax.`, `dalvik.`, `libcore.`, `sun.`, `jdk.`, `org.json.`, `org.apache.harmony.`, `com.samsung.`, `com.sec.`) → 기록만 하고 판정에 영향 없음. **UNCLASSIFIED**: 알 수 없는 패키지, 또는 `end`가 없는 불완전한 로그 → **REVIEW**(통과로 보지 않음) |
| R8 release | 난독화된 클래스명은 `sample/build/outputs/mapping/release/mapping.txt`로 원래 이름을 되살린 뒤 분류합니다. mapping이 없으면 UNCLASSIFIED |
| 판정 | 시나리오 상태를 PASS / FAIL / REVIEW 세 가지로 나눔. Crash(crash 버퍼의 `Process: <sample>`), ANR(`ANR in <sample>`), API 27 이하의 StrictMode 종료는 FAIL. 시나리오마다 `logcat -b all -c`로 crash 버퍼까지 비움 |
| 분류기 확인 | 실제 프로브 스택 → FRAMEWORK(`IdsController`). 합성 사례: SDK 프레임 포함 → OURS, SDK를 거친 외부 라이브러리 → OURS, 알 수 없는 라이브러리만 → UNCLASSIFIED, 로그 누락 → UNCLASSIFIED, R8 난독화된 SDK 프레임과 mapping → OURS, mapping 없음 → UNCLASSIFIED |

### 8.3 기본 시나리오 결과 (A안: `adb reverse`, Galaxy S23, Android 16 / API 36)
| 시나리오 | debug | R8 release |
|---|---|---|
| S1 TLS(HTTPS, 로컬 CA) 전달 | PASS | PASS |
| S2 Java API | PASS | PASS |
| S3 identify/reset 콜백, 로그아웃 흐름 | PASS | PASS |
| S4 **프로세스 종료 후 SQLite 복원, 같은 messageId** | PASS | PASS |
| S5 리다이렉트 미추적 | PASS | PASS |
| S6 단건 격리 100건 (회차별 단건 20·20·20·0, 정상 99/99, 잘못된 1건만 삭제) | PASS | PASS |
| S7 격리 중 429/503 (정상 29/29) | PASS | PASS (주 1) |
| S8 격리 중 추가된 이벤트 보존 (19/19 + 새 이벤트 5/5) | PASS | PASS |
| S9 StrictMode (메인 스레드 호출 후 생존) | PASS | PASS |
| **합계** | **9/9** | **9/9** |

- **Crash 0, ANR 0, StrictMode OURS 0, UNCLASSIFIED 0** (18개 시나리오 전체)
- StrictMode **FRAMEWORK** 위반: 시나리오당 3~5건. 출처는 `android.app.IdsController`(Samsung, 매번)와 `android.system.Os`(S1에서만, 설치 직후 첫 실행)입니다. `android.system.Os` 쪽은 그 당시 도구가 스택 전체를 보존하지 않아서 프레임 목록은 확보하지 못했습니다. 분류 결과(프레임 전부가 플랫폼 패키지)만 확인했습니다. 이후 도구는 스택 전체를 결과 JSON에 저장하도록 고쳤습니다.
- 주 1: release S7의 첫 회차 단건 수가 10에서 끝났습니다. SDK가 상한에 도달한 것이 아니라, 검증 도구가 "4초 동안 요청 없음"을 회차 종료로 보고 backoff(`Retry-After`) 대기 중인 앱을 다시 시작했기 때문입니다. 결과(전달·삭제)에는 영향이 없습니다.
- 결과 파일: `tools/device-verification/results/physical-<serial>-api36-basic.json`
- **이 결과는 중단 전(7장)의 S1~S8 실패 기록과 무관하게 새로 검증한 것입니다.**

## 9. B안(네트워크·백그라운드) 실행 중 P1 실패 → 중단 (2026-11-10)

### 9.1 무엇이 일어났나
- `--suite physical` (endpoint `https://192.168.0.10:8443`, Mac의 LAN IP. 기기와 같은 서브넷, macOS 방화벽 꺼짐) debug에서 첫 시나리오 **P1이 FAIL**했습니다.
  ```
  P1 비행기 모드: LAN 경로 확인=True, 비행기 모드 진입=True, 오프라인 중 서버 도착=0건(정상),
     오프라인 재시작 후 복구=False, 10건 전달=False, 재전송 중복 도착=없음 | crash=0 anr=0 strict(OURS=0, FRAMEWORK=5)
  ```
- 지시에 따라 P2 진행 중에 **실행을 중단**했습니다(P3~P5와 release는 실행하지 않음).

### 9.2 확인된 사실
| 사실 | 근거 |
|---|---|
| 비행기 모드 진입, 오프라인 저장, 오프라인 재시작 자체는 정상 | 오프라인 동안 서버 도착 0건, Crash·ANR 0 |
| **비행기 모드를 끈 뒤 60초 안에 Wi-Fi가 연결되지 않음** | `오프라인 재시작 후 복구=False` = 도구의 `wait_online()`(wlan0 IPv4와 기본 네트워크 확인)이 60초 안에 성립하지 않음 |
| 그 뒤 330초 동안 10건이 서버에 도착하지 않음 | `10건 전달=False` |
| 이어진 P2에서 도구가 Wi-Fi를 끄고(`svc wifi disable`, 12:30:35) 다시 켠 뒤로는 Wi-Fi가 정상 연결됨 | 중단 직후 `wifi_on=1`, wlan0 `192.168.0.21`, 연결됨 |
| 중단 직후 기기 설정은 원래와 같음 | `airplane_mode_on=0, wifi_on=1, mobile_data=1` (시작 전 기록과 일치), Doze 아님(deviceidle 상태 `ACTIVE` = 정상 상태), `mForceIdle=false` |
| P1 구간의 앱과 SDK 로그는 남아 있지 않음 | P2 시작 때 도구가 `logcat -b all -c`로 버퍼를 비움. 서버 요청 기록도 도구 종료와 함께 사라짐 |

### 9.3 원인 판단 (확정 아님)
- **가장 유력 (테스트 절차와 기기 동작)**: 도구는 비행기 모드를 끈(`cmd connectivity airplane-mode disable`) **직후 곧바로** `svc wifi enable`을 호출합니다. 이 기기에서는 이 순서로 Wi-Fi가 60초 넘게 다시 연결되지 않은 것으로 보입니다.
  - 그동안 기본 네트워크는 LTE였을 것이고, LTE로는 사설 LAN 주소(192.168.0.10)의 서버에 도달할 수 없으므로 전달이 불가능했습니다.
  - P2의 Wi-Fi 끄기·켜기 이후에야 정상 연결되었다는 점이 이 가설과 맞습니다.
- **함께 작용했을 가능성 (SDK 설계, 결함 아님)**: SDK는 네트워크 상태를 감지하지 않습니다(설계 Q22). 오프라인 동안 쌓인 연속 실패로 backoff가 최대 5분(full jitter)까지 늘어나므로, 네트워크가 복구된 뒤에도 **같은 프로세스 안에서는 최대 약 5분 뒤에** 재시도할 수 있습니다. 도구의 330초 대기는 이 상한에 가깝습니다.
- **배제하지 못한 가능성 (SDK)**: 네트워크가 복구된 뒤 같은 프로세스의 `HttpURLConnection`이 새 네트워크로 연결하지 못하는 경우. 로그가 남아 있지 않아 **확인하지 못했습니다.** 에뮬레이터에서는 이런 현상이 없었습니다.

### 9.4 수정 방안 (검증 도구만, 승인 대기)
| 안 | 내용 |
|---|---|
| T1 | 비행기 모드를 끈 뒤 **airplane 상태가 반영될 때까지 기다린 다음**, Wi-Fi가 꺼져 있을 때만 켭니다. wlan0 연결을 **최대 120초** 기다리고, 그동안의 네트워크 상태(기본 네트워크, wlan0 IP)를 5초 간격으로 결과에 기록합니다 |
| T2 | 전달 대기를 "네트워크 복구가 **확인된 시점부터** 최대 backoff(300초) + 여유"로 다시 정의합니다. 그리고 시간대별 서버 요청 기록(시도·응답)을 결과에 남겨, 재시도 간격이 backoff 설계대로인지 확인합니다 |
| T3 | **증거 보존**: 시나리오마다 앱 PID의 logcat(SDK `MiniAnalytics` 태그 포함)과 서버 요청 타임라인을 `results/physical/<시나리오>.log`에 저장한 뒤에 다음 시나리오로 넘어갑니다 |
| T4 | P1을 **단독으로 1회** 실행해서(T1~T3 적용) 원인을 확정한 뒤 P2~P5와 release로 진행합니다 |
- 9.3의 "배제하지 못한 가능성"이 재현되면, SDK를 수정하지 않고 재현 절차와 로그를 먼저 보고합니다.

### 9.5 현재 상태
- 기기
  - 설정은 원래와 같습니다(비행기 모드 꺼짐, Wi-Fi 켜짐·연결됨, 모바일 데이터 켜짐, Doze 아님).
  - sample 앱은 강제 종료했습니다(설치는 유지). 프로브 앱(`io.github.dduddupark.strictprobe`)도 설치되어 있습니다.
  - `adb reverse` 항목은 없습니다(A안 실행 후 도구가 정리함).
- 작업 트리 (커밋하지 않음)
  - sample: endpoint 속성화, network security config, StrictMode 리스너
  - `verify.py`: 물리 기기 옵션, 분류기, 상태 복원
  - LAN IP가 들어간 테스트 인증서: 원본은 백업해 두었고, 검증을 마치면 복원합니다.

## 10. T1~T4 적용 후 B안 재실행 (2026-11-10) → P1~P4·S9 PASS, P5는 11장에서 재판정

### 10.1 검증 도구 변경 (SDK 변경 없음)
| 조건 | 반영 내용 (`tools/device-verification/verify.py`) |
|---|---|
| 1. Wi-Fi 연결을 최대 120초 대기, 상태 변화 기록 | 비행기 모드를 끈 뒤 `airplane_mode_on=0` 반영을 확인하고, **시스템이 Wi-Fi를 스스로 복원할 시간(10초)을 준 뒤에도 꺼져 있을 때만** `svc wifi enable`을 호출. wlan0 IPv4와 기본 네트워크를 최대 120초 기다림. `NetTimeline`이 5초마다 비행기 모드, `wifi_on`, wlan0 주소, 기본 네트워크 ID, probe 결과를 기록하고 값이 바뀔 때 출력 |
| 2. SDK와 별개의 서버 도달 확인 | 기기 셸의 `curl -k https://<Mac>:8443/probe`(기기의 실제 네트워크 사용). 서버는 `GET /probe`를 `probes`에 따로 기록. SDK는 `POST /v1/batch`만 보내므로 섞이지 않음. 연결 후 최대 60초 안에 200이 나와야 "도달" |
| 3. 도달 시점부터 300초 + 여유 | 전달 대기 = **probe 200 확인 시점부터 360초** (최대 backoff 300초 + 60초) |
| 4. 네트워크 미복구는 ENV | Wi-Fi 미연결, 서버 도달 불가, 시나리오 시작 전 도달 불가는 `EnvProblem` → 판정 **ENV** (SDK의 PASS/FAIL과 구분) |
| 5. 시나리오별 증거 저장 | 시나리오 동안 `adb logcat -v threadtime`을 파일로 **계속 받음**(버퍼 순환·다음 시나리오의 clear와 무관). 끝나면 `results/physical/<variant>-<ID>-summary.txt`에 네트워크 타임라인, 서버 SDK 요청(시각·상태·건수·클라이언트 주소·이벤트 이름), probe 기록, SDK 로그를 저장한 뒤 다음 시나리오로 넘어감. 전체 로그는 `<variant>-<ID>-logcat.txt` |
| 8. 미전달 시 분석 | 복구 후에도 전달되지 않으면 앱 UID의 netpolicy, 기본 네트워크, Doze 상태, 프로세스 상태를 `<variant>-diagnose-*.txt`에 저장 (이번 실행에서는 발생하지 않음) |
| 기타 | `--only <ID>`로 단일 시나리오 실행. 물리 suite 동안 `svc power stayon usb`로 화면을 켜 두고 종료 시 `stay_on_while_plugged_in`을 원래 값으로 복원. action이 15초 안에 확인되지 않으면 intent를 한 번 재전송 |

### 10.2 P1 단독 재실행
**1차: 시나리오 시작 전 중단 (환경 요인).** 앱이 시작된 직후(12:45:37.719) 기기에서 **HOME 키 입력**(`launchHomeFromHotKey`)이 발생해 런처가 앞으로 나왔습니다. 도구는 P1에서 HOME을 보내지 않고, adb 입력 주입 기록도 없습니다. 당시 전면 앱이 KakaoTalk이었으므로 기기를 직접 조작한 입력으로 판단합니다. 앱은 action 전에 백그라운드로 밀려났고 10초 뒤 시스템이 프로세스를 freeze했습니다. 비행기 모드는 켜지 않았습니다. 이후 10.1의 intent 재전송과 화면 유지를 추가했습니다.

**2차: PASS (debug).**
| 시각 | 사건 | 근거 |
|---|---|---|
| 12:46:58 | warm-up 2건 전달 | 서버 200, client `192.168.0.21` (LAN 경로) |
| 12:46:59 | 비행기 모드 켬 | |
| 12:47:03 | `airplane=1, wifi_on=3, wlan0=없음, 기본 네트워크=none`, probe `000 rc=7`(연결 실패) | 타임라인 |
| 12:47:00, 12:47:08 | SDK `Upload failed (network): ConnectException` (강제 종료 전·후 프로세스 각각 1회 기록. 같은 키는 1시간 억제) | logcat |
| 12:47:09 | 오프라인 재시작 | |
| 12:47:14 | 비행기 모드 끔 → **시스템이 Wi-Fi를 스스로 복원** (`wifi_on=1`, 도구는 `svc wifi`를 호출하지 않음) | |
| 12:47:19 / 20 | Wi-Fi 연결 (+6초) / **probe 200 (+6초)**. 기본 네트워크 ID 271 → 276 | |
| 12:47:30 | **10건이 한 배치로 전달** (도달 확인 후 +10초). `Upload succeeded after 5 failed attempt(s)` | 서버 200, logcat |

- 오프라인 중 서버 도착 0건, 재전송 중복 도착 없음, Crash 0, ANR 0, StrictMode OURS 0
- **9장 P1 실패의 원인 확정: 검증 도구의 절차.** 비행기 모드 해제 직후 `svc wifi enable`을 바로 호출한 것이 Wi-Fi 재연결을 방해한 것으로 판단합니다. 시스템에 맡기자 2회 모두(debug, release) 6초 만에 연결됐습니다.
- **9.3의 "배제하지 못한 가능성"(복구 후 같은 프로세스의 HTTP 연결 실패)은 재현되지 않았습니다.** 같은 프로세스가 네트워크 ID가 바뀐 뒤에도 정상 연결했습니다.
- backoff: 오프라인 동안 실패가 5회 쌓였지만 다음 시도는 도달 확인 후 10초 안에 일어났습니다. 최대 5분은 상한이며, 이번에는 그 상한에 걸리지 않았습니다.

### 10.3 B안 전체 결과 (Galaxy S23, endpoint `https://192.168.0.10:8443`)
| 시나리오 | debug | R8 release | 비고 |
|---|---|---|---|
| P1 비행기 모드: 오프라인 저장·오프라인 재시작·복구 후 전달 | PASS (+10초) | PASS (+1초) | 괄호 = 서버 도달 확인 후 첫 전달까지 |
| P2 Wi-Fi → LTE → Wi-Fi | PASS (+1초) | PASS (+1초) | LTE에서 probe `000 rc=28`(timeout), 서버 도착 0건. Wi-Fi 복구 +4초, 도달 +5초 |
| P3 백그라운드 전환 중 재시도 전송 | PASS | PASS | HOME 후 프로세스 생존, 503 → 정상 전환 후 5건 전달 |
| P4 백그라운드 `am kill` 후 복원 | PASS | PASS | 재실행 후 5건, **같은 messageId** |
| P5 Doze 중 대기와 해제 후 전달 | ~~PASS~~ → **REVIEW** (11장) | ~~PASS~~ → **LIMIT** (11장) | 당시 도구는 PASS로 표시했으나 판정 기준이 잘못됨 (10.4, 11장) |
| S9 StrictMode | PASS | PASS | |
| **합계** | PASS 5 (P1 단독 + P2~P4·S9), REVIEW 1 | PASS 5, LIMIT 1 | ENV 0. 결과 JSON에는 당시 판정(PASS)이 그대로 남아 있음 |

- 12개 시나리오 전체: **Crash 0, ANR 0, StrictMode OURS 0, UNCLASSIFIED 0.** FRAMEWORK 위반은 `android.app.IdsController`, `android.system.Os`만 있습니다(8장과 같음).
- 모든 SDK 요청의 client 주소는 기기의 Wi-Fi 주소(`192.168.0.21`)였습니다. 즉 adb 터널이 아니라 실제 네트워크를 거쳤습니다.
- 결과 파일
  - JSON: `results/physical-<serial>-api36-physical-debug-p2p5.json`, `…-physical-release.json`
  - 시나리오별: `results/physical/{debug,release}-P{1..5}|S9-{summary,logcat}.txt`
  - 위 파일들은 모두 gitignore 대상입니다.

### 10.4 P5 Doze: 당시 도구 판정(PASS)이 틀린 이유 (SDK 결함 아님)
**debug: "Doze 중 5/5 전달"은 Doze 동작을 증명하지 않습니다.**
| 시각 | 사건 |
|---|---|
| 12:48:41.26 | `force-idle` (DEEP IDLE 진입) |
| 12:48:41.577 | 서버가 5건을 받고 **200** 응답 (진입 후 0.3초, 차단 적용 전) |
| 12:48:41.857 | SDK는 응답을 받지 못함: `SocketException: Software caused connection abort` |
| 12:48:46 / 12:49:02 | `ConnectException` / `SocketTimeoutException(15000ms)` → **Doze의 앱 네트워크 차단이 실제로 적용됨** (같은 시간 셸 probe는 200. 앱 단위 차단) |

- 서버에는 저장됐지만 SDK는 실패로 보고 보존했습니다. at-least-once 설계대로의 동작이며, 해제 후 같은 messageId로 재전송되면 서버가 중복을 제거합니다.
- 다만 도구는 "서버 저장 5건"만 보고 바로 종료해서, **해제 후의 재전송은 기록하지 못했습니다.**
- 도구의 "Doze 중 전달" 문구는 실제로는 "Doze 진입 순간 이미 나간 요청"을 센 것이므로 오해의 소지가 있습니다.

**release: Doze 해제 후 전달이 앱 재실행까지 늦어짐.**
| 시각 | 사건 |
|---|---|
| 12:50:44.8 | 503 (1회차 실패), 앱은 백그라운드 |
| 12:50:45 | `force-idle` |
| 12:50:50 / 12:51:07 | `connection abort` / `connect timeout` (Doze 차단) |
| 12:51:25 | Doze 해제 (`IDLE to ACTIVE, reason=exit-force`) |
| **12:51:55** | **`ActivityManager: freezing 7359` (sample 프로세스)** — 백그라운드 cached 앱 freezer |
| 12:51:55 ~ 12:57:25 | 서버 요청 없음 (probe는 계속 200) |
| 12:57:25.77 | 도구가 360초 대기 후 앱 재실행 → `quick sync unfreeze 7359` |
| 12:57:25.78 / 12:57:33 | 첫 시도 `ConnectException`, 8초 뒤 **200**, `Upload succeeded after 6 failed attempt(s)` |

- **원인: Android의 cached app freezer.** Doze가 풀린 뒤 백그라운드 앱 프로세스가 30초 만에 freeze되었습니다. freeze된 프로세스에서는 SDK의 backoff 타이머(코루틴 `delay`)가 실행되지 않습니다. 실패 3회 뒤의 backoff 상한은 수 초이므로, 6분 공백은 backoff가 아니라 freeze 때문입니다.
- 네트워크는 복구되어 있었습니다(probe 200). Uploader 상태는 "재시도 대기, 이벤트 보존"이었고, freeze가 풀리자 같은 프로세스가 재시도해 전달했습니다. **유실 없음.**
- 이는 설계상 범위입니다. v1은 WorkManager나 foreground service를 쓰지 않으며, 프로세스가 실행 중일 때만 전송하고, 네트워크 상태를 감지하지 않습니다(9.3에서 언급한 설계 Q22).
- debug P3·P4에서는 freeze 전에(30초 안에) 재시도가 끝나 이 현상이 드러나지 않았습니다.
- 운영 영향: **앱이 백그라운드에서 freeze된 동안 쌓인 이벤트는 다음 실행 또는 freeze 해제 때 전송됩니다.** README의 "전송 시점" 설명에 이 내용을 명시할지는 사용자가 결정해야 합니다(아래 10.6).
- unfreeze 0.01초 뒤의 첫 시도가 `ConnectException`으로 실패했고, 다음 재시도에서 성공했습니다(관찰된 사실). **이 실패의 원인은 확정하지 않았습니다.** 가설로는 앱의 네트워크 접근 제한이 프로세스 상태 갱신보다 늦게 풀리는 경우를 생각할 수 있으나 검증하지 않았습니다.

### 10.5 정리 상태
| 항목 | 상태 |
|---|---|
| 기기 설정 | `airplane_mode_on=0, wifi_on=1, mobile_data=1, stay_on_while_plugged_in=0` → 시작 전 기록과 같음. Doze 아님(`deviceidle unforce` 후 상태 `ACTIVE` = 정상 상태), 배터리 상태 reset(USB 전원으로 표시) |
| 앱 | sample(도구가 종료 시 삭제), `io.github.dduddupark.strictprobe`(삭제) → 기기에 남은 테스트 앱 없음 |
| `adb reverse` | 항목 없음 |
| 테스트 인증서 | `tools/device-verification/mock_cert.pem`, `mock_key.pem`, `sample/src/main/res/raw/mock_ca.pem`을 백업(= HEAD)에서 복원. git diff 없음, 키와 인증서 공개키 일치 확인 |
| `__pycache__` | 삭제 |
| 빌드 산출물 | `sample/build/`의 APK는 LAN endpoint로 빌드된 상태(gitignore 대상). 기본값으로 다시 빌드하면 `10.0.2.2`로 돌아가며, 인증서가 복원되었으므로 LAN 주소로는 더 이상 TLS 검증을 통과하지 못합니다 |

### 10.6 작업 트리와 결정이 필요했던 점 (2026-11-10 기준. 결정은 11장에 반영)
| 파일 | 변경 | 비고 |
|---|---|---|
| `sample/build.gradle.kts` | endpoint를 `-PsampleEndpoint`로 지정 가능 (기본값은 기존과 같은 `10.0.2.2`) | |
| `sample/.../SampleApplication.kt` | API 28+ StrictMode `penaltyListener` 로그(8.2) | |
| `sample/src/main/res/xml/network_security_config.xml` | `domain-config(10.0.2.2)` → `base-config`에서 system + `@raw/mock_ca` 신뢰 | sample 전용. 127.0.0.1·LAN 주소에서도 쓰기 위해 도메인 제한을 없앰. **원래처럼 도메인을 제한할지 결정 필요** |
| `tools/device-verification/verify.py` | 물리 기기 옵션, StrictMode 분류기, physical suite, 10.1 | |
| `docs/design/v1-physical-device-report.md` | 이 문서 (새 파일) | |
| SDK (`mini-analytics/`) | **변경 없음** (`git diff` 0줄) | |

결정이 필요한 점:
1. 위 작업 트리 변경을 커밋할지, 어느 범위까지 커밋할지
2. 10.4 freeze 동작을 README "전송 시점"에 명시할지 (SDK 코드 변경 없이 문서만)
3. P5 도구 개선: "Doze 중 전달" 문구 수정, 해제 후 같은 messageId 재전송까지 기록 (검증 도구만)

## 11. P5 판정 기준 수정, 테스트 인증서 신뢰 범위 제한, 백그라운드 전송 정책 문서화 (2026-11-12)

### 11.1 P5(Doze) 실기기 관찰 결과 정리
10장의 실기기 실행(Galaxy S23, Android 16 / API 36, 2026-11-10)에서 **실제로 관찰한 것**과 **관찰하지 못한 것**을 나눠 적습니다. 판정은 11.2의 기준으로 다시 매겼습니다.

| 항목 | debug | R8 release |
|---|---|---|
| Doze 강제 진입 (`deviceidle force-idle`, deep `IDLE`) | 확인 | 확인 |
| Doze 중 앱의 네트워크 차단 | **확인**: 진입 뒤 `SocketException: Software caused connection abort`, `ConnectException`, 15초 `SocketTimeoutException`. 같은 시간 기기 셸의 probe는 200 (앱 단위 차단) | **확인**: 같은 패턴 |
| Doze 중 서버 수신 | 진입 0.3초 뒤 200 1건. **차단 적용 전에 이미 나간 요청**이며, SDK는 응답을 받지 못함(`connection abort`). "Doze 중 정상 전송"이 아님 | 없음 |
| Doze 해제 후 같은 프로세스의 재전송 | **관찰하지 못함.** 당시 도구가 "서버 저장 5건"만 보고 해제 직후 시나리오를 끝냄 | **없음.** 해제 30초 뒤 `ActivityManager: freezing <pid>` → 약 6분 동안 요청 없음 |
| 앱 재실행 후 전달 | 해당 없음 (서버에 이미 저장됨) | 재실행 시 `quick sync unfreeze` → 첫 시도 `ConnectException`, 8초 뒤 200 (`Upload succeeded after 6 failed attempt(s)`) |
| 유실 | 없음 (서버 저장 5/5) | 없음 (5/5) |
| 같은 messageId | 확인하지 않음 | 확인하지 않음 (당시 도구는 P5에서 비교하지 않음) |
| **재판정** | **REVIEW** (해제 후 재전송 미확인. PASS 아님) | **LIMIT** (v1 설계 범위의 제약. PASS 아님) |

- 결과 JSON(`results/physical-…-physical-*.json`)에는 당시 판정(PASS)이 그대로 남아 있습니다. 이 문서의 재판정이 우선합니다.
- 이 절을 쓸 때는 실기기가 연결되어 있지 않아 11.3을 에뮬레이터에서 실행했습니다. 같은 날 실기기를 다시 연결해 새 도구로 실행한 결과는 11.8에 있습니다.

### 11.2 P5 검증 도구 수정 (`verify.py`, SDK 변경 없음)
| 변경 | 내용 |
|---|---|
| 진입 순간 경쟁 제거 | 서버가 **Doze 진입 후 5초 동안 503**을 유지한 뒤 정상 응답으로 바뀝니다. 진입 직전에 나간 요청이 저장되어 "Doze 중 전달"로 집계되는 일이 없습니다 |
| Doze 중 기록은 관찰만 | "Doze 중 서버 수신(200) n/5", "Doze 중 SDK 네트워크 실패 로그 n줄"을 **관찰**로 표시하고 판정에 쓰지 않습니다. "Doze 중 정상 전송" 같은 표현은 쓰지 않습니다 |
| 해제 후 재전송을 별도 검증 | **PASS 조건**: Doze 해제 뒤, 앱을 다시 실행하지 않은 같은 프로세스가 **해제 이후의 요청으로** 5건을 전달하고, messageId가 503 때 시도한 것과 같아야 합니다. 대기는 해제 시점부터 360초 |
| PASS가 아닌 결과 | 해제 후 재전송이 없고, 해제 이후 로그에 그 프로세스의 freeze 기록이 있으며, 재실행 후 같은 messageId로 전달되면 **LIMIT**. Doze 중 이미 모두 전달되어 해제 후 재전송을 확인할 수 없으면 **REVIEW**. 그 밖은 **FAIL**(진단 파일 저장) |
| 판정 상태 | `LIMIT` 추가 (v1 설계 범위의 제약을 확인함. PASS로 세지 않고 종료 코드도 실패) |
| 기타 | 진단 단계에서 `grep`이 결과를 못 찾으면 예외로 시나리오가 끝나던 문제 수정(API 36 `appId=` 형식). 기기에 `curl`이 없으면 `nc` TCP 연결로 도달을 확인하고 `tcp-ok`로 표시(HTTP 200과 구분). `--restore-cert`(임시 인증서 복원) 추가 |

### 11.3 새 P5 도구 실행 결과 (에뮬레이터 API 36, 실기기 아님)
에뮬레이터는 deep Doze가 꺼져 있어(`deviceidle enabled deep` = 0) 실행 동안만 `dumpsys deviceidle enable`로 켜고, 끝난 뒤 `disable`로 되돌렸습니다.

| 시각 (debug) | 사건 |
|---|---|
| 10:11:22~23 | 503 3회 (Doze 전, 백그라운드 전환 중) |
| 10:11:23.3 | Doze 강제 진입, 서버 503 유지 |
| 10:11:28.6 | 서버 정상 응답으로 전환 (Doze 유지) |
| 10:11:29 / 10:11:49 | `connection abort` / 15초 연결 timeout → Doze 차단 |
| 10:12:03.7 | Doze 해제 |
| **10:12:34** | **`freezing 3941`** (해제 30초 뒤) |
| 10:12:34 ~ 10:18:04 | 요청 없음 (360초 대기 끝) |
| 10:18:04.7 | 도구가 앱 재실행 → `quick sync unfreeze 3941` |
| 10:18:05.3 | 200, 같은 messageId 5건, `Upload succeeded after 6 failed attempt(s)` |

| 변형 | 판정 | 요약 |
|---|---|---|
| debug | **LIMIT** | Doze 중 서버 수신 0/5, Doze 중 SDK 네트워크 실패 로그 2줄, 해제 후 같은 프로세스 재전송 0/5, freeze 기록 있음, 재실행 후 전달·같은 messageId |
| R8 release | **LIMIT** | 같음 (해제 10:18:51 → freeze 10:19:21 → 재실행 10:24:52 → 200) |

- 에뮬레이터에서도 실기기 release와 같은 현상(해제 30초 뒤 freeze, 재실행 전까지 재시도 없음)이 재현되었습니다.
- 새 도구로는 **"Doze 해제 후 같은 프로세스의 재전송"을 PASS로 확인한 실행이 아직 없습니다.** 백그라운드 프로세스가 freeze되지 않는 조건(예: 앱이 전면에 있는 상태의 Doze)은 v1 검증 범위에서 다루지 않았습니다.

### 11.4 P5 검증 한계
- Doze는 `force-idle`로 **강제 진입**만 검증했습니다. 화면 꺼짐과 움직임 없음으로 자연스럽게 들어가는 Doze, maintenance window, 앱 대기 버킷은 검증하지 않았습니다.
- 물리 suite 동안 도구가 화면을 켜 두므로(`svc power stayon usb`) 실제 사용 환경과 다릅니다.
- freeze 시점(해제 30초 뒤)은 이 기기와 에뮬레이터 이미지에서 관찰한 값이며, OS 버전과 제조사에 따라 다를 수 있습니다.
- 11.1의 실기기 판정은 당시 로그로 다시 매긴 것입니다. 새 도구로 실기기에서 다시 실행한 결과는 11.8에 있습니다.

### 11.5 sample 앱의 테스트 인증서 신뢰 범위 제한 (SDK 변경 없음)
**이전**: `base-config`에서 시스템 CA와 테스트 CA를 **모든 주소에** 신뢰(10장 실행 때의 임시 설정). API 23은 기본 `SSLSocketFactory`를 테스트 CA**만** 신뢰하는 것으로 바꿔서, 모든 HTTPS 연결에 적용.

**이후**
| 항목 | 내용 |
|---|---|
| 신뢰 범위 | 빌드할 때의 `-PsampleEndpoint`(기본 `https://10.0.2.2:8443/v1/batch`) **호스트 하나에만** 테스트 CA를 신뢰. 그 밖의 주소는 시스템 CA만 |
| API 24 이상 | `network_security_config.xml`을 Gradle 작업(`generate<Variant>TestNetworkSecurityConfig`)이 빌드 때 생성. `base-config` = 시스템 CA, `domain-config(<그 호스트>, includeSubdomains=false)` = 테스트 CA. 평문 HTTP 금지. 정적 파일은 삭제 |
| API 23 | `HostScopedSSLSocketFactory`: 연결 대상 호스트가 `BuildConfig.TEST_SERVER_HOST`일 때만 테스트 CA, 그 밖은 원래 시스템 기본 factory. 호스트를 모르는 `createSocket()`은 시스템 쪽 |
| 빌드 환경 제한 | endpoint가 https가 아니거나, 호스트가 loopback·`localhost`·사설 IPv4(10/8, 172.16/12, 192.168/16)가 아니면 **Gradle 구성 단계에서 실패** |
| SDK | 변경 없음. SDK는 플랫폼 기본 `HttpsURLConnection` 설정을 따름 |

검증
| 확인 | 결과 |
|---|---|
| 생성된 설정 (기본 빌드) | `domain-config`에 `10.0.2.2` 하나 |
| `-PsampleEndpoint=http://10.0.2.2:8443/…` | 빌드 실패: `sampleEndpoint는 https여야 합니다` |
| `https://8.8.8.8:8443/…`, `https://example.com/…` | 빌드 실패: `loopback·localhost·사설 IPv4여야 합니다` |
| `https://192.168.0.10:8443/…` | 구성 통과 |
| API 36 에뮬레이터 S1·S2·S4·S5·S9 (debug, R8 release) | **10/10 PASS** (생성된 network security config 경로) |
| API 23 에뮬레이터 S1·S2·S4·S5·S9 (debug, R8 release) | **10/10 PASS** (`HostScopedSSLSocketFactory` 경로) |
| 다른 호스트에서 테스트 CA가 거부되는지 (음성 검증) | **기기에서 검증하지 않음.** 빌드가 endpoint 호스트와 신뢰 호스트를 같은 값에서 만들기 때문에, sample로는 "신뢰하지 않는 호스트로 보내는" 구성을 만들 수 없습니다. 생성 파일과 코드로만 확인 |

### 11.6 README
"전송 정책"에 **백그라운드 전송 정책 (v1)** 절을 추가했습니다.
- 프로세스가 실행 중일 때만 전송·재시도
- 프로세스 종료 시 재시도 타이머 소멸, 다음 실행의 `initialize()`에서 같은 messageId로 전송
- freeze 중에는 재시도 타이머가 실행되지 않음
- Doze
- 유실 없음과 지연, 저장 상한
- 실기기 관찰
- WorkManager를 쓰지 않는 이유와 호스트 앱의 `flush()` 호출 방법

"검증 범위"와 "개발"(sample의 인증서 신뢰 범위)도 갱신했습니다.

### 11.7 테스트 (2026-11-12)
| 명령 | 결과 |
|---|---|
| `./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest :mini-analytics:check --rerun-tasks` | BUILD SUCCESSFUL. `testDebugUnitTest` 297/297, `testReleaseUnitTest` 297/297, lint 통과, `checkPublicApi` 기준 파일과 일치 |
| `./gradlew :sample:assembleDebug :sample:assembleRelease` | BUILD SUCCESSFUL |
| 에뮬레이터 API 36 / 23 | 11.3, 11.5 |
| 실기기 | 11.7 작성 시점에는 연결되지 않음. 이후 새 P5를 실행함 (11.8) |

### 11.8 새 P5 도구 실기기 실행 (2026-11-12, Galaxy S23, Android 16 / API 36)
- 환경: B안(Mac LAN `192.168.0.10`, 기기 Wi-Fi `192.168.0.22`). 임시 인증서(SAN에 LAN IP)는 `--extra-ip`로 만들고 실행 뒤 `--restore-cert`로 복원.
- sample은 `-PsampleEndpoint=https://192.168.0.10:8443/v1/batch`로 빌드. 생성된 network security config의 `domain-config`는 `192.168.0.10` 하나. **11.5의 신뢰 범위 제한이 실기기 TLS에서도 동작함**(503·200 응답을 정상 수신).

| 변형 | 판정 | 요약 |
|---|---|---|
| debug | **LIMIT** | Doze 중 서버 수신 0/5, Doze 중 SDK 네트워크 실패 로그 2줄, 해제 후 같은 프로세스 재전송 0/5 (360초), freeze 기록 있음, 재실행 후 전달·같은 messageId |
| R8 release | **LIMIT** | 같음 |

- Crash 0, ANR 0, StrictMode OURS 0, UNCLASSIFIED 0. FRAMEWORK 5(`IdsController`, `android.system.Os`)

| 시각 (debug) | 사건 |
|---|---|
| 10:33:01.0 | track 5건 → 10:33:01.4, 01.9 503 (Doze 전) |
| 10:33:01.8 | Doze 강제 진입, 서버 503 유지 |
| 10:33:06.9 | 서버 정상 응답으로 전환 (Doze 유지) |
| 10:33:07.4 | `SocketException: Software caused connection abort` |
| 10:33:23.3 | `SocketTimeoutException` (15초) → **Doze의 앱 네트워크 차단 확인** |
| 10:33:42.0 | Doze 해제 |
| **10:34:12.3** | **`ActivityManager: freezing 9212`** (해제 30초 뒤) |
| 10:34:12 ~ 10:39:43 | 요청 없음 (해제부터 360초 대기 끝) |
| 10:39:43.55 | 도구가 앱 재실행 → `quick sync unfreeze 9212` |
| 10:39:43.59 | 첫 시도 즉시 `ConnectException` (unfreeze 40ms 뒤) |
| 10:40:43.2 | **200**, 같은 messageId 5건, `Upload succeeded after 7 failed attempt(s)` (재실행 60초 뒤) |

release: 진입 10:40:54 → 해제 10:41:34 → **freeze 10:42:04.5 (30초 뒤)** → 재실행 10:47:35.6 → 즉시 `ConnectException` → **200 10:48:14 (재실행 39초 뒤)**.

**관찰 (SDK 결함으로 판단하지 않음, 코드 변경 없음)**
1. **관찰된 사실: freeze 해제 직후 첫 시도가 실패했습니다.** unfreeze 40ms 뒤 밀려 있던 재시도가 바로 실행되었고, `ConnectException`(`Failed to connect to /192.168.0.10:8443`)으로 실패했습니다. 실기기에서는 2026-11-10 release 실행(unfreeze 0.01초 뒤)과 이번 debug·release 실행 모두 같았습니다.
   - **에뮬레이터(11.3)에서는 이 실패가 관찰되지 않았습니다.** unfreeze 뒤 0.5~0.6초 만에 200을 받았습니다(debug 10:18:04.7 → 10:18:05.3, release 10:24:52.6 → 10:24:53.2). 그 실행의 증거 파일(`results/physical/{debug,release}-P5-*`)은 이번 실기기 실행이 같은 이름으로 덮어써서 남아 있지 않습니다. 이 시각들은 덮어쓰기 전에 추출해 둔 값입니다.
   - **원인은 확정하지 않았습니다.** 가설로는 앱의 네트워크 접근 제한이 프로세스 상태 갱신보다 늦게 풀리는 경우(실기기와 에뮬레이터의 OS 이미지 차이 포함)를 생각할 수 있으나, 네트워크 정책 상태를 그 시점에 기록하지 않아 검증하지 못했습니다.
2. **그 실패 때문에 전달이 backoff만큼 더 늦어집니다.** 연속 실패 7회 뒤의 backoff는 full jitter 0~64초이고, 이번에는 60초와 39초였습니다. 2026-11-10 실행은 6회 뒤라 8초였습니다.
   - 같은 프로세스가 freeze에서 풀린 것이므로 `initialize()`의 시작 시 확인은 다시 일어나지 않습니다. 재시도는 backoff를 따릅니다.
   - README의 "freeze가 풀리면 이어서 전송"은 맞지만, **즉시가 아니라 최대 수십 초 늦어질 수 있습니다.**
   - 개선 후보(이번에 구현하지 않음): 프로세스가 전면으로 돌아올 때 backoff를 재설정하거나 한 번 더 시도.
3. 진단 파일(해제 후 360초 대기가 끝난 시점, 재실행 전에 수집)에는 deviceidle 상태 `ACTIVE`(Doze 아님)와 `mScreenOn=true`(도구의 화면 유지)가 기록되어 있습니다. 앱 UID의 netpolicy 제한 항목은 없었습니다. freeze가 풀린 순간의 네트워크 정책 상태는 기록하지 않았습니다.

**정리 상태**
| 항목 | 상태 |
|---|---|
| 기기 설정 | `airplane_mode_on=0, wifi_on=1, mobile_data=1, stay_on_while_plugged_in=0` (시작 전과 같음) |
| Doze | **해제됨.** 실행 후 다시 확인: `deviceidle get deep` = `ACTIVE`, `get light` = `ACTIVE`, `get force` = `false`, `mForceIdle=false`. deviceidle의 `ACTIVE`는 Doze가 아닌 정상 상태입니다. 배터리 상태도 reset되어 실제 값(USB 전원, 충전 중)을 표시. 복원할 항목 없음 |
| 기기의 sample 앱 | 도구가 삭제 |
| `adb reverse` | 항목 없음 |
| 인증서 | `--restore-cert`로 복원, HEAD와 같음, 키와 인증서 공개키 일치 |
| sample 빌드 산출물 | 기본 endpoint(`10.0.2.2`)로 다시 빌드 |
| 결과 | `results/physical-<serial>-api36-physical.json`, `results/physical/{debug,release}-P5-*` (gitignore 대상) |

## 12. 미해결: P1 재실행 FAIL (2026-11-12, 원인 미확정)
공개 준비 중, 실제 LAN 주소를 저장소에서 빼고 **실행 인자로만** 넘겨도 검증 도구가 동작하는지 확인하려고 P1(debug)을 다시 실행했습니다(`--extra-ip`, `-PsampleEndpoint`). `verify.py`는 바꾸지 않았습니다.

### 12.1 실행 결과
1. **1차: ENV.** 시나리오 시작 전 기기에서 Mac 서버에 도달하지 못했습니다(기기 → 게이트웨이 ping도 실패, Mac → 기기 ping은 성공). 기본 네트워크 ID가 바뀌는 중이었고, 잠시 뒤 다시 확인하니 정상이었습니다. 도구가 의도대로 테스트 환경 문제로 분류했습니다.
2. **2차: FAIL.** 아래 표.

| 시각 | 사건 | 근거 |
|---|---|---|
| 11:00:32 | warm-up 2건 전달 (200) | 서버 |
| 11:00:32 ~ 47 | 비행기 모드, 오프라인 track 10건, 오프라인 재시작 | |
| 11:00:42.6 | SDK `ConnectException` (오프라인, 정상) | logcat |
| 11:00:47.8 | 비행기 모드 끔 → 시스템이 Wi-Fi 복원 | |
| 11:00:53.6 / 54.1 | Wi-Fi 연결, **probe 200** → 도구가 "서버 도달"로 판단 | 타임라인 |
| 11:00:57.7 | **다시 wlan0 주소 없음, 기본 네트워크 304, probe timeout** | 타임라인 |
| 11:01:03.1 | wlan0 주소 복구, 기본 네트워크 **306**. 이후 11:06:52까지 probe 200 유지 | 타임라인 |
| 11:01:25.3 | SDK `SocketTimeoutException: failed to connect … after 15000ms` (시도 시작 약 11:01:10). 출발 주소는 **그때의 Wi-Fi 주소와 같음** | logcat |
| 11:00:42 ~ 11:04:57 | sample 프로세스가 **전면** (`fg list : [27390]`) | Samsung 프로세스 상태 로그 |
| 11:01:25 ~ 11:06:54 | **SDK 로그 없음, 서버 수신 0건** | 서버, logcat |
| 11:04:54 ~ 57 | 사람의 조작: 잠금 해제 → HOME → 다른 앱 실행 → sample은 백그라운드 | `KeyguardViewMediator`, `startDockOrHome` |
| 11:06:07 | `freezing 27390` | logcat |
| 11:06:54 | 도구 판정 FAIL ("도달 확인 후 SDK 요청 0건") | |

### 12.2 확인된 것과 확인하지 못한 것
- **확인됨**
  - 네트워크가 안정된 뒤(11:01:03) 사람의 조작(11:04:54)까지 약 3분 50초 동안, 앱이 **전면**이고 기기 셸의 연결은 계속 성공했는데도 SDK 요청은 서버에 하나도 도착하지 않았습니다.
  - 이 구간에 기록된 SDK 실패는 11:01:25의 연결 timeout 1건뿐입니다.
- **확인하지 못함**
  - 11:01:25 이후 SDK가 재시도를 했는지, 했다면 어떻게 실패했는지 알 수 없습니다. 같은 종류의 실패 로그는 1시간에 한 번만 남기 때문입니다(설계).
  - backoff 상한으로 계산하면 이 구간에 5~6번의 시도가 있었어야 합니다(15초 timeout 포함). 실제로 시도했는지는 확인하지 못했습니다.
  - 앱 UID의 네트워크 차단 기록(`NetworkPolicy`, firewall)은 logcat에서 찾지 못했습니다. 그 시점의 per-UID 네트워크 상태와 기기 쪽 패킷은 수집하지 않았습니다.
- **이전 실행과 다른 점**: 2026-11-10의 P1 두 번(debug, release)은 복구 후 1~10초 안에 전달되었습니다. 이번에는 "도달 확인" 직후 네트워크가 한 번 더 끊겼다가(304 → 306) 돌아왔습니다.

### 12.3 판단과 다음 단계 (SDK 코드는 바꾸지 않음)
- **SDK 결함인지, 기기·네트워크 환경 문제인지 판단하지 못했습니다.** 이 실행은 PASS로 세지 않습니다. 10장의 P1 PASS(2회)를 뒤집는 근거로도 쓰지 않습니다. **미해결 항목**으로 남깁니다.
- 가설
  - (a) 네트워크 전환이 연달아 일어난 뒤, 앱 프로세스의 새 연결만 계속 실패하는 경우. 플랫폼, 제조사, 또는 `HttpURLConnection` 쪽 원인일 수 있습니다.
  - (b) SDK가 재시도는 했지만 매번 연결 timeout으로 실패한 경우
  - (c) 재시도가 일어나지 않은 경우
- 다음 확인 방법
  - P1 실행 동안 Mac 쪽에서 패킷을 캡처해 기기의 SYN이 도착하는지 확인합니다.
  - 실패가 계속될 때 `dumpsys connectivity`와 `dumpsys netpolicy`의 앱 UID 상태를 기록합니다.
  - 검증용 빌드에서만 실패 로그 억제를 끌 수 있는지 검토합니다. 이것은 SDK 변경이므로 승인이 필요합니다.
- 실행 뒤 상태: 기기 설정은 원래와 같고(도구가 복원), sample은 삭제했습니다. 임시 인증서는 `--restore-cert`로 복원했습니다(HEAD와 같음). 실제 LAN 주소는 저장소 파일에 남기지 않았습니다.
