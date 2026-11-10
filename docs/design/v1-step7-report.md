# 7단계 보고서: 배포와 통합 검증

- 작성일: 2026-11-10
- 계획: [`v1-implementation-plan.md`](./v1-implementation-plan.md) 7단계
- **표기 원칙**: 실제로 실행해서 확인한 것만 "검증됨"으로 적습니다. 에뮬레이터에서 확인한 것은 "에뮬레이터"라고 밝힙니다. 실행하지 못한 것은 "미검증"으로 적습니다.

## 0. 결과 요약

| 영역 | 결과 |
|---|---|
| JVM 단위 테스트 | **290개 통과** (기존 288 + D6-1 2개) |
| 에뮬레이터 instrumented test (SQLite, 메인 Looper) | **API 23 / 33 / 36 각 6개 통과** |
| 에뮬레이터 sample 앱 시나리오 (debug + R8 release) | **API 23 / 33 / 36 각 18개 통과 (총 54개)** |
| Release AAR → 로컬 Maven → 소비 앱 | 검증됨 |
| R8 (keep 규칙 추가 없음) | 검증됨 (빌드, 그리고 3개 API에서 release APK 실행) |
| 호스트 Kotlin 호환성 | 2.1.21, 2.2.21 컴파일 성공 / **2.0.21 실패** |
| 실제 물리 기기 | **미검증** (에뮬레이터만 사용) |

## 1. 6단계 승인 조건 반영 (D6-1)
"서로 다른 중요한 오류가 같은 제한 그룹에 묶여 누락되지 않는가"를 점검하다가 **두 가지 실제 결함**을 찾아 고쳤습니다.
1. **네트워크 오류가 예외 종류와 관계없이 한 그룹(`network`)이었습니다.** 그래서 타임아웃 뒤 1시간 안에 생긴 TLS 인증서 오류(`SSLHandshakeException`, 설정 문제)는 로그에 남지 않았을 것입니다. 예외 클래스별로 그룹을 나눴습니다.
2. **네트워크 오류가 두 번씩 기록되었습니다.** catch에서 한 번, 응답 분류에서 `"network"`로 또 한 번 기록되면서, 1번에서 나눈 그룹도 다시 하나로 섞였습니다. catch에서만 기록하도록 고쳤습니다.
- 테스트 2개 추가: 401, 403, 404, 500, 503, 네트워크 오류가 한 시간 안에 번갈아 생겨도 **각각 한 번씩** 기록됨 / `IOException`, `SocketTimeoutException`, `SSLHandshakeException`이 각각 기록됨
- 그룹 키: `transient:<code>`, `auth:<code>`, `unexpected:<code>`, `isolate:<code>`, `invalid:<code>:<unproven|limit>`, `network:<예외 클래스>`, `read:<예외 클래스>`

## 2. 실제 Android 환경 (에뮬레이터)

### 환경
| API | system image | 비고 |
|---|---|---|
| 23 | `android-23;default;arm64-v8a` | 7단계에서 내려받음 (약 242MB) |
| 33 | `android-33;google_apis;arm64-v8a` | 기존 AVD |
| 36 | `android-36;default;arm64-v8a` | 7단계에서 내려받음 (약 773MB) |

모두 Apple Silicon 호스트의 **에뮬레이터**입니다. 실제 물리 기기 검증은 하지 않았습니다.

### instrumented test (`SqliteEventStoreDeviceTest`, 3개 API 모두 6/6)
| 테스트 | 의미 |
|---|---|
| `journalModeIsNotWalOnThisDevice` | Android 9 이상의 compatibility WAL이 적용되지 않음 (2단계 I-2를 실제 기기에서 확인) |
| `dataAndIdentitySurviveReopen` | 이벤트와 Identity 영속 |
| `failureBeforeCommitRollsBackEverything` | 이벤트와 Identity의 원자적 롤백 |
| `idsAreNotReusedAfterReopenWithEmptyTable` | `AUTOINCREMENT` |
| `corruptedFileIsRecreatedByTheDefaultErrorHandler` | 기기의 손상 처리기가 DB를 새로 만듦 (A-7, S2-2) |
| `mainThreadExecutorDeliversOnTheRealMainLooper` | 콜백이 실제 메인 Looper에서 실행됨 |

### sample 앱 시나리오 (`tools/device-verification/verify.py`)
debug와 R8 release 각각, API 23·33·36 모두 **9/9 통과**:

| ID | 시나리오 | 확인한 것 |
|---|---|---|
| S1 | TLS 전달 | Android의 `HttpURLConnection`이 HTTPS(로컬 CA)로 이벤트 3건을 전달 |
| S2 | Java API | Java 정적 호출, 오버로드, `null` 전달. `identify(null)` → `INVALID_ARGUMENT` |
| S3 | identify/reset | 서버에 연결 반영, 콜백이 메인 스레드에서 `Success`, 로그아웃 흐름(타임아웃 패턴)이 콜백으로 진행 |
| S4 | **프로세스 종료 후 복원** | 서버 503 중 시도한 messageId 5건 → `am force-stop`(종료 확인) → 재시작 후 **같은 messageId 5건** 전달 |
| S5 | 리다이렉트 | 서버가 307을 줘도 Location으로 요청 0건. 이벤트는 보존되고 재시작 후 전달 |
| S6 | 격리 100건 | 아래 3장 |
| S7 | 격리 중 429/503 | 아래 3장 |
| S8 | 격리 중 추가된 이벤트 | 아래 3장 |
| S9 | **StrictMode** | `detectDiskReads/Writes/Network` + `penaltyDeath`를 SDK 초기화 **전에** 켜고, 모든 API를 메인 스레드에서 호출 → 프로세스 생존, 앱 PID의 위반 로그 0건 (S1~S8 내내 같은 정책) |

**검증 범위의 한계**
- StrictMode는 ThreadPolicy만 확인했습니다. VmPolicy(리소스 누수 등)는 켜지 않았습니다.
- TLS는 **로컬에서 만든 CA**로만 확인했습니다. 공인 CA 체인, 인증서 핀닝, 프록시 환경은 미검증입니다.
- API 23은 `network_security_config`를 지원하지 않아서, **sample 앱**이 기본 `SSLSocketFactory`에 로컬 CA를 추가했습니다. SDK 코드는 바꾸지 않았습니다. SDK가 기본 `HttpsURLConnection` 설정을 따른다는 사실 자체도 이 과정에서 확인되었습니다.

### 검증 도구의 결함 3가지와 재실행 (숨기지 않고 기록)
첫 실행 결과는 SDK 결함이 아니라 **검증 도구의 결함** 때문에 실패했습니다. 원인을 재현하고 고친 뒤 다시 실행했습니다. 첫 실행 결과 파일은 `results/*-first-run-*.json`으로 보존했습니다.

| # | 증상 | 원인 | 조치 |
|---|---|---|---|
| 1 | API 33: S1 이후 서버에 요청 0건 (18개 중 4개 통과) | Python 서버가 accept 루프에서 TLS handshake를 함 → handshake를 끝내지 않는 연결 하나가 서버 전체를 멈춤 (로컬에서 재현: 멈춘 연결이 있는 동안 응답 `000`) | 연결마다 자기 스레드에서 handshake, 소켓 타임아웃 30초 |
| 2 | 1을 고친 뒤에도 S2부터 실패 | `am start`를 두 번째로 보내면 Android가 task만 앞으로 가져오고 intent를 전달하지 않음 | `--activity-single-top` + 앱이 action을 실제로 실행했는지 로그로 확인한 뒤 진행 |
| 3 | API 23: S9만 실패 | API 23의 `ps -A`가 헤더만 출력하고 성공(0)으로 끝남 → 생존 확인이 항상 거짓. **S4의 "종료 확인"도 API 23에서는 의미 없이 통과했음** | `ps`와 `ps -A` 결과를 합침. 시작 후 True, 강제 종료 후 False를 직접 확인한 뒤 API 23을 전부 다시 실행 |

추가로, StrictMode 확인이 **시스템 프로세스**(연락처 provider 등)의 StrictMode 로그까지 잡고 있었습니다. 샘플 앱 PID의 로그만 보도록 고쳤습니다.

## 3. 단건 격리 안전성

### 실측 (에뮬레이터 → 호스트 로컬 HTTPS 서버)
| 시나리오 | 요청 수 | 소요 시간 | 요청 본문 바이트 | 결과 |
|---|---|---|---|---|
| S6: 100건 중 1건 잘못됨 | **101** (배치 1 + 단건 100) | 2.6 ~ 6.7초 | 약 65KB | 정상 99/99 전달, 잘못된 1건만 삭제 |
| S7: 30건, 격리 중 503 6번, 429(`Retry-After: 2`) 3번 | 40 | 10.3 ~ 16.1초 | 약 24KB | 정상 29/29, 일시적 실패 동안 격리 유지, `Retry-After` 대기 |
| S8: 20건 격리 중 새 이벤트 5건 저장 | 22 | 6.7 ~ 9.4초 | 약 15KB | 격리 대상 19/19 + **새 이벤트 5/5 전달 (잘못 삭제되지 않음)** |

- 바이트는 **요청 본문만** 셌습니다. 헤더와 TLS handshake는 포함하지 않았습니다.
- `HttpUrlConnectionTransport`는 요청마다 `disconnect()`를 호출하므로, 단건마다 TLS handshake가 다시 일어날 수 있습니다. 로컬 서버라 지연이 작아서, 실제 모바일 네트워크(RTT 100~300ms)에서는 101번 요청에 약 **10~60초**가 걸릴 것으로 추정합니다(미검증 추정).

### `invalid_event` 기반 삭제의 적용 조건 (현재)
삭제는 아래 조건을 **모두** 만족할 때만 일어납니다.
1. 여러 건 배치가 400/422를 받아 격리가 시작됨
2. **한 건짜리** 요청이 400/422를 받음
3. 본문이 정확히 `{"error":{"code":"invalid_event","messageId":"<보낸 그 id>"}}`
4. 이번 프로세스에서 400 경로로 삭제한 수가 20건 미만

→ 이 계약을 구현하지 않은 서버에서는 삭제가 **일어나지 않습니다**(6단계 e2e와 이번 S6~S8로 확인). 다만 **"운영 서버가 계약을 지킨다고 확인되지 않은 상태에서는 아예 끈다"는 설정은 현재 없습니다.** 제안 P7-1을 참고하세요.

## 4. 배포 품질

| 항목 | 결과 |
|---|---|
| Release AAR | `maven-publish` → `build/local-maven`. AAR, POM, Gradle module metadata, sources jar |
| POM 의존성 | `kotlinx-coroutines-core:1.10.2` = **runtime**, `kotlin-stdlib:2.2.21` = compile (KGP 기본값) |
| 소비 앱 | `:sample`이 **프로젝트 의존성이 아니라 Maven 좌표**로 받음 (`dependencies`로 확인) |
| Manifest 병합 | sample은 INTERNET을 선언하지 않았는데도 병합된 manifest(debug/release)에 들어 있음 |
| R8 | `isMinifyEnabled = true`, 추가 keep 규칙 없음 → 빌드 경고와 missing class 없음. release APK가 3개 API에서 시나리오 9개 모두 통과 |
| 공개 JVM 시그니처 | `docs/api/public-api.txt`에 정리. 공개 패키지 클래스 9개. `MiniAnalytics`의 Java 공개 메서드는 의도한 static 11개(`initialize`, `track` ×2, `screen` ×2, `identify` ×2, `reset` ×2, `flush`, `INSTANCE`) |
| Kotlin 사용 | 소비 앱(Kotlin)이 컴파일·실행됨 |
| Java 사용 | 소비 앱의 `JavaUsage.java`가 컴파일되고 기기에서 실행됨 (S2) |
| README | 설치, 초기화, track/screen, identify/reset, 로그아웃 패턴, 전송·실패 정책, 유실 조건, 서버 계약, 안정성 원칙 |

**공개 JVM 시그니처에서 알아 둘 점**: Kotlin이 만드는 합성 생성자 `Configuration(String, String, int, long, int, DefaultConstructorMarker)`와 `IdentityResult(DefaultConstructorMarker)`가 public으로 보입니다. Java에서 marker 인자에 `null`을 넣으면 호출할 수 있는 언어상의 부산물입니다. 공개 API로 문서화하지 않았으며, 위험은 낮다고 판단합니다.

### 호스트 Kotlin 호환성 (A-5)
같은 AAR을 쓰는 소비 앱을 Kotlin 버전별로 컴파일했습니다.

| 호스트 Kotlin | 결과 |
|---|---|
| 2.2.21 | 성공 |
| 2.1.21 | 성공 |
| 2.0.21 | **실패** (컴파일러가 분석 중 `FileAnalysisException`) |

→ **지원하는 호스트 Kotlin은 2.1 이상**입니다(README와 설계에 명시). 하한을 낮추는 방법은 P7-3으로 제안합니다.

## 5. 테스트 안정성
- 기존 288개 유지, 290개 통과.
- 리소스 정리 점검: 테스트의 모든 `CoroutineScope`, `Thread`, `MockWebServer`/`ContractMockServer` 생성 위치를 확인했습니다.
  - **누수 1곳을 고쳤습니다**: e2e 테스트의 `previousRun()`이 만든 클라이언트의 coroutine을 정리하지 않았습니다.
  - 나머지는 모두 `@After`, `finally`, `backgroundScope` 등록으로 정리됩니다. HTTP 연결은 transport가 요청마다 `disconnect()`합니다.
- 기기 검증은 실행이 끝나면 sample 앱을 지웁니다. 에뮬레이터는 종료했습니다.

## 6. 101번 요청 구조의 비용과 위험 평가

**언제 일어나는가**: 서버가 **계약 1을 어기고**(이벤트 문제를 2xx + `rejected`로 알리지 않고) 여러 건 배치에 400/422를 줄 때만 일어납니다. 계약을 지키는 서버에서는 일어나지 않습니다.

| 측면 | 평가 |
|---|---|
| 요청 수 | 배치 크기 + 1 (최대 101). 반복되지 않음: 근거 없는 400이면 첫 단건에서 멈추고, 그 뒤로는 5분마다 2번 |
| 데이터량 | 이벤트 본문은 배치로 보낼 때와 비슷함(측정: 100건에 약 65KB). 늘어나는 것은 요청 100개만큼의 HTTP 헤더와, 연결을 재사용하지 못할 때의 TLS handshake |
| 시간 | 로컬 2.6~6.7초 측정. 모바일 네트워크에서는 RTT에 비례해서 수십 초로 추정. 그동안 새 이벤트 전송이 늦어짐 (저장은 지연되지 않음, 5단계 #4) |
| 배터리·데이터 요금 | 드문 오류 경로에서 한 번 일어나는 비용이라 일상적인 영향은 작다고 판단 |
| **서버 부하 (가장 큰 위험)** | 서버 배포 버그로 **모든 기기가 동시에** 400을 받으면, 각 기기가 최대 101번씩 요청해서 서버 부하가 순간적으로 최대 약 100배가 될 수 있음 |
| 데이터 안전 | 계약이 없는 응답에서는 삭제하지 않음. 삭제는 프로세스당 20건 상한 |

### 제안 (설계를 크게 바꾸지 않는 범위, 모두 승인 대기)
| ID | 제안 | 효과 | 비용 |
|---|---|---|---|
| **P7-2a (권장)** | 격리 중 **단건 요청 사이에 짧은 간격**(예: 200~500ms + jitter) | 서버 부하가 동시에 몰리는 것을 완화. 상태 추가 없음 | 격리가 더 오래 걸림 (100건 × 0.3초 ≈ +30초) |
| P7-2b | 격리 한 번에 보내는 단건 수에 상한 (예: 20건). 넘으면 남은 것은 다음 주기(5분)에 이어서 | 최악의 경우 요청 수가 21번으로 줄어듦 | 상태 1개(이번 회차 카운터) 추가. 정상 이벤트 전달이 늦어짐 |
| P7-2c | bisect(절반씩 나눠서 찾기) | 잘못된 이벤트 1건이면 약 2·log₂(100) ≈ 14번 | 6단계에서 복잡도 때문에 제외한 방식. 상태가 늘어남 |
| (변경 없음) | 서버 계약 1(2xx + `rejected`)을 지키도록 서버를 구현 | 격리 경로 자체가 일어나지 않음 | 서버 쪽 책임 |

권장은 **"변경 없음 + 서버 계약 강조"**이고, 필요하면 **P7-2a**를 추가하는 것입니다. 측정된 비용이 감당할 만하고, 이 경로는 계약을 어기는 서버에서만 일어나기 때문입니다.

## 7. 그 밖의 제안 (승인 대기)
| ID | 제안 | 근거 |
|---|---|---|
| **P7-1** | `Configuration.Builder.trustInvalidEventResponses(Boolean)`, **기본값 false**. false면 `invalid_event` 응답도 "근거 없는 400"으로 처리해서 **삭제하지 않음** | 운영 서버가 계약을 지킨다고 확인되지 않은 상태에서 삭제 경로를 열어 두지 않기 위함 (사용자 조건 2-4). 공개 API가 하나 늘어나므로 승인이 필요함. 기본값 false면 현재 동작보다 보수적이 되며, 잘못된 이벤트는 저장 상한 정리로만 해소됨 |
| P7-3 | 호스트 Kotlin 하한을 낮추려면 `kotlin { coreLibrariesVersion = "2.0.x"; compilerOptions { apiVersion/languageVersion = 2.0 } }`를 적용하고, 2.0.21 소비 앱으로 다시 검증 | 지금은 2.1 이상만 지원. 학습 프로젝트라 필수는 아님 |
| P7-4 | 공개 API 표면 검증 자동화 (Kotlin ABI 검증 도구, 또는 `public-api.txt`와 비교하는 Gradle 작업) | 매 단계 javap로 수동 확인한 것을 자동화 |

## 8. 미검증 항목
| 항목 | 상태 |
|---|---|
| 실제 물리 기기 | **미검증** (에뮬레이터만) |
| API 24~32, 34~35 | **미검증** (23, 33, 36만) |
| 공인 CA 인증서 체인, 핀닝, 프록시 | **미검증** (로컬 CA만) |
| 실제 모바일 네트워크(지연, 손실, 비행기 모드 전환) | **미검증** |
| 호출부터 commit까지의 지연 (A-2), commit 처리량 (A-9) | **미측정** |
| Doze, 백그라운드 제한, 저메모리 상태의 프로세스 종료 | **미검증** |
| StrictMode VmPolicy | **미검증** |
| `kotlin-stdlib 2.2.21`의 전이 상향(S0-1)이 실제 호스트 앱에 미치는 영향 | 컴파일 하한만 확인. 런타임 영향은 미검증 |
| Maven Central 배포 | v1 범위 밖 |

## 9. 산출물
- `mini-analytics/build.gradle.kts`: `maven-publish`, androidTest 의존성
- `mini-analytics/src/androidTest/.../SqliteEventStoreDeviceTest.kt`
- `sample/`: 소비 앱 (Kotlin과 Java, R8, StrictMode, network security config)
- `tools/device-verification/verify.py`: 로컬 HTTPS 계약 서버 + adb 시나리오
  - `mock_cert.pem`은 저장소에 포함합니다. `mock_key.pem`과 `results/`는 git에서 제외합니다.
- `docs/api/public-api.txt`, `README.md`
- 결과 파일: `tools/device-verification/results/` (api23/33/36.json, instrumented-api23/36.xml, 첫 실행 기록)
