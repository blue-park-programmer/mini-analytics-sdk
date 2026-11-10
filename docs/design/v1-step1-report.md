# 1단계 보고서: 순수 로직

- 작성일: 2026-11-09
- 계획: [`v1-implementation-plan.md`](./v1-implementation-plan.md) 1단계

## 구현한 것

| 파일 | 공개 여부 | 역할 | 설계 근거 |
|---|---|---|---|
| `InitializeResult.kt` | public | `STARTED` / `ALREADY_INITIALIZED` / `INVALID_CONFIGURATION` | §2.1, R-6' |
| `IdentityResult.kt` | public | `IdentityCallback`(fun interface), sealed `IdentityResult`, `FailureReason` | §2.3, Q21 |
| `Configuration.kt` | public | `Configuration.Builder(apiKey, endpoint)` + `flushAt` / `flushIntervalMillis` / `maxQueuedEvents`. `build()`는 검증도 예외도 없음. `toString`과 차이 설명에서 `apiKey` 마스킹 | §2.1, Q22, Q14 |
| `internal/BuildInfo.kt` | internal | 라이브러리 이름과 버전 | §7.2 |
| `internal/config/ConfigurationValidator.kt` | internal | 동기 검증. 위반 목록을 반환하고 예외는 던지지 않음 | §2.1, §7.1 |
| `internal/model/Identity.kt`, `Command.kt` | internal | `Identity`, `Command`(Track / Screen / Identify / Reset), `MessageType` | §3, §4.2, R-1 |
| `internal/model/NameValidator.kt` | internal | event·screen 이름: 공백이 아닌 1~256자 | §2.2, R-9' |
| `internal/properties/PropertiesSanitizer.kt` | internal | 타입 처리, 제한, 순환 참조, 노드 예산, 순회 중 예외, 수정 불가능한 깊은 복사, 위반 보고 | §7.3, R-9' |
| `internal/serialization/Iso8601UtcFormatter.kt` | internal | UTC ISO-8601 밀리초. 스레드 안전하지 않으므로 트랜잭션·요청 단위로 생성 | §4.3, R-11 |
| `internal/serialization/EventSerializer.kt` | internal | 메시지 → `batch[]` 원소 JSON, UTF-8 크기, 32KB 검사, 실패는 결과값으로 반환 | §7.2, Q14 |

## 테스트 결과

`./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL, 67개 통과 (실패 0, skip 0)**

| 테스트 | 실행 방식 | 개수 |
|---|---|---|
| `PropertiesSanitizerTest` | JUnit | 30 |
| `EventSerializerTest` | Robolectric (`org.json`) | 9 |
| `ConfigurationValidatorTest` | JUnit | 9 |
| `ConfigurationTest` | JUnit | 7 |
| `IdentityResultTest` (+ `IdentityCallbackJavaUsage.java` 컴파일) | JUnit | 3 |
| `Iso8601UtcFormatterTest` | JUnit | 3 |
| `NameValidatorTest` | JUnit | 3 |
| `RobolectricSetupTest`, `MiniAnalyticsSmokeTest` (0단계) | Robolectric / JUnit | 3 |

**테스트가 결함을 잡는지 확인 (mutation check)**: 코드를 일부러 망가뜨려 해당 테스트가 실패하는지 본 뒤 원래대로 되돌렸습니다.
- `Float`를 `toDouble()`로 바로 변환 → `float is converted ... decimal representation` 실패
- 순환 참조 검사 제거 → 순환 관련 테스트 2개 실패

## 공개 API 표면 점검 (javap)
- `io.github.dduddupark.minianalytics` 패키지에서 Java에 보이는 것은 의도한 타입뿐입니다: `Configuration`(+`Builder`), `InitializeResult`, `IdentityCallback`, `IdentityResult`(+`Success`/`Failure`), `FailureReason`, `MiniAnalytics`.
- 0단계에서 만든 `MiniAnalytics.LIBRARY_VERSION`은 설계 §2에 없는 공개 상수였습니다. `internal/BuildInfo`로 옮겼습니다.
- `internal const val`은 Java에서 **public static 필드**로 보입니다. 그래서 `Configuration`과 `Iso8601UtcFormatter`의 상수는 파일 수준 `private const`로 옮겼습니다.

## 결정이 필요한 사항
| ID | 내용 | 제안 |
|---|---|---|
| D-1 | 설계에 `Configuration`의 형태가 정해져 있지 않았음 | `Builder` 패턴으로 구현함. Java에서 쓰기 쉽고, 필드를 추가해도 바이너리 호환이 유지됨. 공개 setter는 `flushAt`, `flushIntervalMillis`, `maxQueuedEvents` 세 개 |
| D-2 | 검증 범위의 구체적인 값 (설계에 없던 숫자) | `flushAt` 1..`maxQueuedEvents`, `flushIntervalMillis` ≥ 1,000ms, `maxQueuedEvents` 1..10,000 |
| D-3 | 테스트용 http 허용을 어떻게 켤지 | 지금은 `internal` 파라미터라 테스트 코드에서만 켤 수 있음. 7단계에서 샘플 앱이 로컬 Mock Server로 보낼 방법은 그때 결정 |
| D-4 | **Java 호스트가 `null`을 넘기면 Kotlin의 파라미터 null 검사가 NPE를 던짐** (예: `new Configuration.Builder(null, …)`). "공개 API는 예외를 던지지 않는다"는 원칙과 충돌 | 4단계에서 공개 API 전체에 하나의 정책을 적용: (a) Java에 노출되는 파라미터를 nullable로 선언하고 내부에서 검증, 또는 (b) 컴파일러 옵션으로 파라미터 검사를 끄고 facade에서 직접 검사. **(a)를 권장**: 동작이 코드에 드러나고 Java 테스트로 검증할 수 있음 |
| D-5 | **`identify(userId)`의 `userId` 검증 규칙이 설계에 없음** (빈 문자열, 길이, 콜백 결과) | `NameValidator`와 같은 규칙(공백이 아닌 1~256자)을 적용하고, 위반하면 호출을 무시. 콜백 결과는 새 `FailureReason.INVALID_ARGUMENT`를 추가할지, 기존 값으로 처리할지 결정 필요 |
| D-6 | Kotlin `internal` 클래스는 Java에서 public으로 보임 (언어 특성) | `.internal` 패키지 이름으로 "공개 API 아님"을 표시하는 일반적인 관례로 수용하고 README에 명시. 추가 조치 없음 |

## 남은 위험
| ID | 위험 | 대응 |
|---|---|---|
| S1-1 | 동시 수정 테스트(실제 스레드)는 "예외가 새어 나가지 않음"만 확인함. 실행할 때마다 경합이 실제로 일어난다는 보장은 없음 | 결정적 검증은 `ThrowingMap`으로 함. 실제 스레드 테스트는 보조 수단 |
| S1-2 | 노드 예산이나 크기 제한값이 실제 사용 패턴에 맞는지 측정하지 않음 (A-3) | 7단계 샘플 앱에서 측정 |
| S1-3 | `org.json`의 숫자 출력 형식은 Android 구현에 따름 (예: `1.0`의 표기) | JSON 숫자로서의 의미는 같음. 서버 계약 테스트(6단계)에서 확인 |

---

## 보완 (2026-11-09, 1단계 조건부 승인 후)

| 항목 | 반영 |
|---|---|
| D-4 (a) | `Configuration.Builder(apiKey: String?, endpoint: String?)`. null이어도 `build()`는 정상이고, 검증에서 오류로 보고됨. 컴파일러의 null 검사는 끄지 않음. API별 정책은 설계 §2.4에 정리 (4단계 API는 정책만 정하고, `initialize`의 null 처리는 미결정) |
| D-5 | `FailureReason.INVALID_ARGUMENT` 추가. 식별 문자열 규칙(UTF-16 code unit 기준 1~256, 유니코드 공백만 있으면 무효, 다듬기·잘라내기 없음)을 설계 §2.5에 명시. "Identity 유지" 테스트는 `identify`가 구현되는 3단계 필수 테스트 T-I1~T-I3으로 기록 |
| 노드 예산 경로 검토 | 호스트 컬렉션에서 실제로 꺼낸 원소 수를 세는 테스트 5개 추가: 거대한 최상위 Map(10만 개 → 최대 501개만 꺼냄), 거대한 중첩 List, 형제 컨테이너 간 예산 공유, 유효하지 않은 key도 예산을 소비, 깊이 초과 컨테이너는 순회하지 않음 |
| `internal` 노출 | 관례를 유지하고, 공개 API 표면 검증을 구현 계획 7단계의 후속 항목으로 기록 |
| 공개 패키지 정리 | 파일 수준 `const` 때문에 생긴 빈 공개 클래스 `ConfigurationKt`와, Java에 보이던 `Configuration.Companion.maskApiKey`를 제거함 (`internal/config/ApiKeyMasking.kt`로 이동) |
| Configuration 제한값 | 설계 §2.1에 "실측으로 확정하지 않은 v1 초기 정책"으로 명시 |

**결과**: 테스트 80개 통과 (67 → 80; Java null 테스트 4, 노드 예산 경로 5, 길이·공백 규칙 3, Configuration null 1).

**공개 API 호환성**
- `Configuration.Builder` 생성자의 JVM 시그니처는 `(String, String)` 그대로입니다. nullable로 바꿔도 바이너리 호환이 유지됩니다.
- `FailureReason`에는 값을 추가만 했습니다. Java `switch`의 `default` 분기 안내는 KDoc에 이미 있습니다.
- 공개 패키지 클래스: `Configuration`, `Configuration$Builder`, `FailureReason`, `IdentityCallback`, `IdentityResult`(+`Success`, `Failure`), `InitializeResult`, `MiniAnalytics`
