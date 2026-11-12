# mini-analytics-sdk v1 최종 보고서

- 작성일: 2026-11-10
- 상태: **7단계 조건부 최종 승인 후, 조건(P7-1, P7-2b, P7-4) 반영 완료. 커밋하지 않음.**
- 단계별 상세: `v1-step0-report.md` ~ `v1-step7-report.md`, 설계: `v1-design.md`

## 1. 이번 최종 반영 (승인된 변경만)

| 항목 | 반영 내용 |
|---|---|
| **P7-1** | `Configuration.Builder.trustInvalidEventResponses(Boolean)` 추가, **기본값 false**. false면 400/422에 단건 격리도 삭제도 하지 않고, 보존 + 최대 backoff(5분) + `Log.e`. true일 때만 기존 단건 격리와 `invalid_event` 삭제(프로세스당 20건)를 함. `equals`, `hashCode`, `toString`, 재초기화 차이 로그에도 반영 |
| **P7-2b** | 격리 1회당 단건 요청 **최대 20번** (429/5xx 재시도 포함). 상한에 도달하면 격리를 끝내고 남은 이벤트를 보존한 채 최대 backoff → 다음 시도에서 새 회차. 일시적 실패의 backoff와 `Retry-After`는 상한 전까지 그대로 적용됨. **이 상한이 전체 서버 부하를 완전히 막지 못한다는 점**을 설계 §6.4와 README에 명시 |
| **P7-3** | 보류. 호스트 Kotlin **2.1 이상** 유지. 설계 §15 IC-4로 기록 |
| **P7-4** | Gradle 작업 `checkPublicApi`(`check`/`build`에 연결): release AAR 공개 패키지의 `javap -public` 결과를 `docs/api/public-api.txt`와 비교하고, 다르면 실패하며 추가·삭제된 줄을 보여 줌. 의도한 변경은 `updatePublicApi`로 갱신. P7-1로 추가한 API를 기준 파일에 반영함 |
| a안(간격), c안(bisect) | 구현하지 않음. 설계 §15 IC-2, IC-3으로 기록 |

새 기능은 추가하지 않았습니다.

## 2. 최종 테스트 결과

### JVM
| 명령 | 결과 |
|---|---|
| `./gradlew :mini-analytics:check :mini-analytics:assembleRelease --rerun-tasks` | **BUILD SUCCESSFUL** |
| └ `testDebugUnitTest` | **297개 통과** (7단계 290 + 7) |
| └ `testReleaseUnitTest` | **297개 통과** |
| └ lint | 통과 |
| └ `checkPublicApi` | 기준 파일과 일치 |

추가된 테스트 7개:
- trust=false에서 배치 400을 받아도 보존됨
- trust=false에서 단건 `invalid_event`도 삭제하지 않음
- 100건 격리가 회차별 20번 이하로 나뉘고 결국 모두 처리됨
- 재시도도 상한에 포함되며 유실 없음
- Configuration 2개 (기본값, 동등성과 차이 로그)
- Java에서 설정 1개

e2e 1개도 추가했습니다(trust=false 서버에서 격리 없음). 기존 격리 테스트 3개는 `trust = true`와 20회 상한을 반영하도록 고쳤습니다.

**mutation check**
- trust 확인을 무시하게 함 → 2개 실패
- 격리 요청 상한을 제거 → 3개 실패
- 공개 함수를 실수로 추가 → `check` 실패 (`+ public final int accidentalPublicApi();`)
- 모두 원래 코드로 되돌리고 diff로 확인했습니다.

### 에뮬레이터 (API 23 / 33 / 36)
| 검증 | API 23 | API 33 | API 36 |
|---|---|---|---|
| instrumented test (SQLite, 메인 Looper) | 6/6 | 6/6 | 6/6 |
| sample 시나리오, debug | 9/9 | 9/9 | 9/9 |
| sample 시나리오, **R8 release** | 9/9 | 9/9 | 9/9 |

시나리오: TLS 전달, Java API, identify/reset 콜백과 로그아웃 흐름, **프로세스 종료 후 SQLite 복원과 같은 messageId**, 리다이렉트를 따라가지 않음, 단건 격리 3종, **StrictMode(penaltyDeath)**

**격리 실측 (P7-2b 적용 후)**
| 시나리오 | 회차별 단건 요청 | 전체 요청 | 결과 |
|---|---|---|---|
| 100건 중 1건 잘못됨 | 20, 20, 20, 0 | 64 | 정상 99/99 전달, 잘못된 1건만 삭제 |
| 30건, 격리 중 503·429 | 20, 0 | 22 | 정상 29/29, backoff와 `Retry-After` 지킴 |
| 20건 격리 중 새 이벤트 5건 | 20 | 22 | 정상 19/19 + 새 이벤트 5/5 (잘못 삭제되지 않음) |

- "회차"는 검증에서 앱을 다시 시작해서 앞당긴 것입니다. 실제로는 회차 사이에 5분을 기다립니다.
- 마지막 회차에서 단건이 0인 이유: 잘못된 이벤트가 앞 회차에서 삭제되었으므로, 남은 배치가 한 번에 2xx를 받았기 때문입니다.

### 배포
- `maven-publish`로 AAR, POM, module metadata, sources를 `build/local-maven`에 배포했습니다.
- `:sample`은 이 AAR을 **Maven 좌표로** 받아서(debug와 R8 release) Kotlin과 Java 양쪽에서 사용합니다.
- POM에서 coroutines는 `runtime` 범위입니다. INTERNET 권한은 호스트 manifest에 병합됩니다.
- 런타임 의존성은 `kotlin-stdlib 2.2.21`, `kotlinx-coroutines-core 1.10.2`로 변경되지 않았습니다.

## 3. 미검증 항목 (통과로 표시하지 않음)
| 항목 | 상태 |
|---|---|
| **실제 물리 기기** | 이 보고서 작성 시점에는 미검증. 이후 Galaxy S23(Android 16 / API 36) 1대에서 검증함: `v1-physical-device-report.md` (P5 Doze는 PASS가 아니라 LIMIT/REVIEW, 11장) |
| **운영 서버** | 미검증. 로컬 계약 서버(테스트 전용)와 MockWebServer만 사용. 운영 서버가 계약(2xx 의미, `messageId` 중복 제거, `invalid_event`, identify 멱등성)을 지키는지는 SDK가 검증할 수 없음 |
| **실제 모바일 네트워크** | 미검증. 지연, 패킷 손실, Wi-Fi↔LTE 전환, 비행기 모드, 캡티브 포털 |
| TLS | 로컬 CA만 검증. 공인 CA 체인, 인증서 핀닝, 프록시, TLS 버전 협상 차이는 미검증 |
| API 레벨 | 23, 33, 36만 검증. 24~32, 34~35는 미검증 |
| 성능 | 호출부터 commit까지의 지연(A-2), commit 처리량(A-9), 메모리 사용량은 미측정 |
| OS 상태 | Doze, 앱 대기 버킷, 백그라운드 제한, 저메모리 종료는 미검증 |
| StrictMode | ThreadPolicy만 검증. VmPolicy는 미검증 |
| 호스트 Kotlin | 2.1.21, 2.2.21 컴파일만 확인. `kotlin-stdlib 2.2.21` 전이 상향의 런타임 영향은 미검증 |
| 대규모 동시 부하 | 여러 기기가 동시에 격리 경로에 들어갈 때의 서버 영향은 평가만 하고 측정하지 않음 |
| 배포 | Maven Central은 v1 범위 밖 |

## 4. 변경 파일 목록

### 이번 최종 반영에서 바뀐 파일
| 파일 | 변경 |
|---|---|
| `mini-analytics/src/main/kotlin/.../Configuration.kt` | P7-1 설정 추가 |
| `mini-analytics/src/main/kotlin/.../internal/AnalyticsClient.kt` | 설정을 Uploader로 전달 |
| `mini-analytics/src/main/kotlin/.../internal/upload/Uploader.kt` | P7-1 적용 조건, P7-2b 회차별 요청 상한 |
| `mini-analytics/build.gradle.kts` | P7-4 `checkPublicApi`·`updatePublicApi`, `check`에 연결 |
| `docs/api/public-api.txt` | 기준 파일 갱신 (`trustInvalidEventResponses(boolean)`, 합성 생성자 시그니처) |
| `mini-analytics/src/test/kotlin/.../internal/upload/UploaderIsolationTest.kt` | trust와 상한 테스트, 기존 테스트 갱신 |
| `mini-analytics/src/test/kotlin/.../ConfigurationTest.kt` | trust 기본값, 동등성, 차이 로그 |
| `mini-analytics/src/test/kotlin/.../mockserver/ServerContractEndToEndTest.kt` | trust=false e2e, 격리 e2e에 trust=true |
| `mini-analytics/src/test/java/.../JavaNullInputTest.java`, `IdentityCallbackJavaUsage.java` | Java에서 설정 사용 |
| `sample/src/main/kotlin/.../SampleApplication.kt` | 검증 서버용으로 `trustInvalidEventResponses(true)`를 명시하고 이유를 주석으로 남김 |
| `tools/device-verification/verify.py` | 격리 시나리오를 회차 단위로 검증 (회차별 단건 20 이하, 결국 전달) |
| `README.md`, `docs/design/v1-design.md` | P7-1, P7-2b(한계 포함), P7-4, 검증 범위, 개선 후보 |
| `docs/design/v1-final-report.md` | 이 문서 |
| `mini-analytics/src/main/kotlin/.../MiniAnalytics.kt` | **내용 변경 없음**. 공개 API 검증 mutation 때 잠시 바꿨다가 백업에서 복원함 (diff로 확인) |

### 저장소 전체 (커밋되지 않음, 빌드 산출물 제외 108개)
- 빌드: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew*`, `.gitignore`
- SDK: `mini-analytics/` (main 22개 파일, test 32개, androidTest 1개, `consumer-rules.pro`, manifest)
- 소비 앱: `sample/`
- 검증 도구: `tools/device-verification/` (`verify.py`, `mock_cert.pem`, `.gitignore`. 키와 `results/`는 git에서 제외)
- 문서: `README.md`, `CLAUDE.md`, `docs/design/*`(설계, 검토, 계획, 단계별 보고서, 최종 보고서), `docs/api/public-api.txt`, `docs/agents/*`

## 5. 커밋 전에 확인할 점
- `local.properties`, `build/`, `.gradle/`, `tools/device-verification/mock_key.pem`, `results/`는 `.gitignore`에 들어 있어 커밋되지 않습니다.
- `sample/src/main/res/raw/mock_ca.pem`은 테스트용 자체 서명 **공개** 인증서이며, 커밋해도 됩니다. 키는 포함하지 않습니다.
- 7단계에서 이 머신의 Android SDK에 system image 2개(API 23, 36)와 `cmdline-tools`를 설치했고, AVD 2개(`mini_analytics_api23`, `mini_analytics_api36`)를 만들었습니다. 필요 없으면 지워도 됩니다.
