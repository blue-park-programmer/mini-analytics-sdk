# Android SDK 벤치마크 도구 (Step 8)

`mini-analytics` SDK가 호스트 앱 성능에 주는 영향을 실기기에서 재현 가능하게 측정하는 도구다.
측정 결과와 해석은 [`docs/design/v1-benchmark-report.md`](../../docs/design/v1-benchmark-report.md)에 있다.

- 측정 전용이며 배포하지 않는다. SDK의 공개 API·동작·런타임 의존성을 바꾸지 않는다.
- SDK는 sample과 같이 **배포된 AAR(Maven 좌표 `io.github.dduddupark:mini-analytics:0.1.0-SNAPSHOT`)** 으로 받는다.

## 구성
| 경로 | 역할 |
|---|---|
| `benchmark-host/` | 측정 전용 호스트 앱. release(R8, profileable)와 debug(패키지 `.debug`)를 함께 설치할 수 있다. `am start`로 시나리오를 하나 실행하고 결과를 logcat `MiniAnalyticsBench` 태그에 JSON 한 줄로 남긴다 |
| `benchmark/` | androidx.benchmark 1.5.0 Microbenchmark(`TrackBenchmark`). 교차 확인용. 이 모듈만 minSdk 24 |
| `tools/benchmark/run_host.py` | 호출 지연 측정 실행기 (`--plan main` / `sparse` / `mainsparse` / `compare`) |
| `tools/benchmark/run_store.py` | SQLite 저장 처리량·commit 지연·배경 CPU 측정 실행기. 로컬 검증 서버로 업로드해 저장 건수를 독립 검증한다 |
| `tools/benchmark/run_micro.py` | Microbenchmark 설치·AOT 컴파일·실행·JSON 수집 자동화 |
| `tools/benchmark/variants.py` | 개선 전후 비교용 변형 빌드(두 실행기가 공유) |

## 필요 환경
- **실기기 1대** (USB 디버깅 허용). 보고서의 수치는 **Galaxy S23(SM-S911N), Android 16 / API 36**에서 측정했다. 다른 기기에서도 실행되지만 수치를 그대로 비교하거나 일반화하지 않는다.
- Android SDK platform-tools(`adb`가 `PATH`에 있어야 한다), 프로젝트와 같은 JDK/Gradle 환경, Python 3.
- `run_store.py`: 기기와 측정 PC가 **같은 LAN**에 있어야 한다(PC에서 HTTPS 검증 서버를 포트 8443으로 띄운다). LAN IP는 macOS의 `ipconfig getifaddr en0`로 자동으로 찾고, 다른 환경에서는 `--lan-ip`로 준다. `openssl`이 필요하다(임시 인증서 생성).
- (선택) 프로파일: NDK의 simpleperf (`<ndk>/simpleperf/`).

## 측정 전 준비
1. 기기를 연결하고 **잠금을 해제**한다. 측정이 끝날 때까지 기기를 만지지 않는다(잠금·다른 앱 실행이 결과를 바꾼다, `docs/design/v1-physical-device-report.md` 12장).
2. 충전 중, 열 상태 정상(`adb shell dumpsys thermalservice`의 `Thermal Status: 0`)에서 시작한다.
3. SDK AAR을 배포하고 측정 앱을 빌드한다. **배포와 빌드는 나눠서 실행한다**(같은 실행에서 하면 이전 스냅샷으로 컴파일될 수 있다).
   ```bash
   ./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository
   ./gradlew :benchmark-host:assembleDebug :benchmark-host:assembleRelease
   ```
   `run_store.py`와 `run_host.py --plan compare`는 필요한 빌드를 직접 한다.

## 실행
`<serial>`은 `adb devices`에 보이는 기기 serial이다.

| 측정 | 명령 | 소요(대략) |
|---|---|---|
| 동작 확인(표본 1/10) | `python3 tools/benchmark/run_host.py --serial <serial> --runs 1 --quick` | 3~5분 |
| track() 호출 지연·할당 (속성 없음/5/20/약 4KB × normal/saturated, 메인 스레드·진단 변형, debug 대표 조건) | `python3 tools/benchmark/run_host.py --serial <serial> --runs 5` | 약 15분 |
| 간헐 호출(백그라운드 스레드, 100ms/500ms/2s, 대조군·AOT·JIT 진단) | `python3 tools/benchmark/run_host.py --serial <serial> --plan sparse --runs 5` | 약 90분 |
| 메인 스레드 간헐 호출(100ms/500ms × 정지 화면/UI 동작 × SDK/대조군) | `python3 tools/benchmark/run_host.py --serial <serial> --plan mainsparse --runs 5` | 약 45분 |
| SQLite 저장 처리량·commit 지연·배경 CPU·유실 메커니즘 분리 | `python3 tools/benchmark/run_store.py --serial <serial> --runs 5` | 약 20분 |
| Microbenchmark | `python3 tools/benchmark/run_micro.py --serial <serial> --repeat 1` | 약 3분 |
| 개선 전후 비교(SQLite) | `python3 tools/benchmark/run_store.py --serial <serial> --runs 5 --only store_sparse_cpu --only store_commit --only store_burst --final-only store_evict --final-only store_drop --variant base=<dir> --variant opt=<dir>` | 변형 수에 비례 |
| 개선 전후 비교(메인 스레드 track) | `python3 tools/benchmark/run_host.py --serial <serial> --plan compare --runs 5 --variant base=<dir> --variant opt=<dir>` | 변형 수에 비례 |

- `--variant label=<dir>`의 `<dir>`에는 그 변형의 SDK 소스를 `main/` 하위 디렉터리로 담는다(`mini-analytics/src/main`의 복사본). 실행기는 변형마다 소스를 바꿔 AAR을 배포·빌드하고, 끝나면 작업 트리를 원래대로 되돌린다.
- `run_store.py --only <시나리오>`로 일부만 실행할 수 있다: `store_burst`, `store_commit`, `store_sparse_cpu`, `store_evict`, `store_drop`.

## 측정 조건 (실행기가 지키는 규칙)
- 실행마다 `pm clear` 후 **새 프로세스**에서 조건 하나를 실행한다(SDK 싱글턴·DB 누적 방지).
- 라운드마다 조건(변형) 순서를 섞는다(고정 seed, 열 상태 편향 감소). 실행 전마다 잠금·열 상태·배터리 온도를 기록하고, 잠겨 있으면 그 실행을 건너뛴다(`SKIP locked`).
- 백분위는 **실행 단위로만** 계산하고, 요약은 실행별 백분위의 실행 간 중앙값과 최소–최대다. 서로 다른 실행의 원시 표본을 합치지 않는다.
- 판정은 release 빌드로 한다(debug는 비교용).
- 저장 측정은 메커니즘이 하나만 일어나도록 설계했다: `store_burst`(손실 없음이어야 함), `store_evict`(DB 상한 정리만), `store_drop`(메모리 큐 폐기만). 모든 이벤트에 순번(seq)을 넣고 측정 뒤 DB와 업로드 서버 양쪽에서 seq 패턴으로 확인한다.

## 실행기가 기기·작업 트리에 하는 일 (모두 끝나면 되돌린다)
- 측정 앱(`io.github.dduddupark.minianalytics.benchhost`, `.debug`)과 Microbenchmark 테스트 APK를 설치하고 끝나면 삭제한다.
- 측정 동안 `svc power stayon usb`로 화면을 켜 두고, 끝나면 `stay_on_while_plugged_in`을 원래 값으로 되돌린다. 실행기가 중간에 강제로 끝났다면 `adb shell settings get global stay_on_while_plugged_in`으로 확인하고 원래 값(보통 0)으로 되돌린다.
- `run_store.py`는 임시 인증서(SAN에 LAN IP)를 만들어 `tools/device-verification/mock_cert.pem`과 `sample/src/main/res/raw/mock_ca.pem`을 잠시 덮어쓴다. 끝나면 자동으로 원래대로 되돌린다. 중간에 끊겼다면 커밋하기 전에 `python3 tools/device-verification/verify.py --restore-cert`를 실행한다.
- `run_host.py --plan sparse`의 AOT 진단 조건은 그 실행만 `cmd package compile -m speed`로 컴파일하고 끝나면 `--reset`한다.
- 측정 확인을 위해 SDK 내부 DB 이름(`mini_analytics.db`)을 측정 뒤에 읽기 전용으로 연다(측정 중에는 열지 않는다).

## 결과 위치 (git 제외)
| 실행기 | 위치 | 파일 |
|---|---|---|
| `run_host.py` | `tools/benchmark/results/<시각>/` | `raw.jsonl`(실행별 원본), `summary.json`, `summary.md` |
| `run_store.py` | `tools/benchmark/results/store-<시각>/` | `raw.jsonl`, `summary.json` (비교 모드는 변형별 APK도 이곳에 만든다) |
| `run_micro.py` | `tools/benchmark/results/micro-<시각>/` | `instrument-*.txt`, `summary.json`, `summary.md`, 기기에서 가져온 JSON |

`tools/benchmark/results/`는 `.gitignore`에 들어 있다. 결과를 공유할 때는 기기 serial(파일 이름에 일부 들어감)과 LAN 주소가 포함되지 않았는지 확인한다.

## 한계
- 기기 1대 기준의 도구다. CPU 클록 고정(루팅 필요)은 하지 않는다.
- 호출 스레드 할당은 deprecated된 `Debug` 스레드 할당 API로 잰다(API 36에서 동작, androidx.benchmark 결과와 교차 확인).
- 측정 앱은 시나리오마다 SDK 설정을 바꾸려고 SDK를 `Activity.onCreate`에서 초기화한다(통합 가이드 G-1과 다름, 측정 앱에만 해당).
