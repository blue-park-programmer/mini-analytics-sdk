# 4단계 보고서: facade와 최소 SDK (+ 3단계 보완)

- 작성일: 2026-11-09
- 계획: [`v1-implementation-plan.md`](./v1-implementation-plan.md) 4단계
- 이 단계가 끝나면 SDK는 **이벤트를 수집해서 영구 저장**합니다. 전송은 아직 하지 않습니다 (5단계).

## A. 3단계 보완

### Q-4b: 처리되지 않은 실패 (설계 §8.4)
| 실패 | 처리 |
|---|---|
| storage 작업 안의 `Exception` | 기존 재시도 경로 (변경 없음) |
| 재시도 경계 밖으로 새어 나온 `Exception` | 비활성화. 호스트로 전파하지 않음 |
| `Error` | 비활성화를 **시도**한 뒤 삼키지 않고 스레드의 uncaught exception handler로 넘김 |

- storage coroutine의 최상위 `try/catch`와 `CoroutineExceptionHandler` 두 곳에서 처리하며, `AtomicBoolean`으로 **한 번만** 동작합니다.
- 새 coroutine을 띄우지 않고 실패가 난 스레드에서 바로, **가장 먼저 lock 안에서 `disabled` 표시**를 하고 큐를 비웁니다. 콜백 전달과 로그는 최선을 다할 뿐입니다. 그마저 실패하면 `disabled` 표시만이라도 남깁니다.
- 한계(설계 문서에 명시): `OutOfMemoryError` 상황에서는 복구, 콜백, 로그를 보장하지 않습니다. `Error`를 삼켜서 호스트가 정상이라고 가정하지 않습니다.

### 추가 확인 1~3
| 항목 | 결과 |
|---|---|
| 1. `identify`/`reset` 무제한 적재 | 설계 §11.7에 평가 기록. 적재는 재시도 창(약 6초 + 작업 시간)으로 **시간적으로 제한**되고, 메모리 문제가 되려면 실제 사용과 동떨어진 호출 빈도가 필요함(예: 초당 1만 번 × 6초 ≈ 60MB). **v1은 상한 없음**. 상한을 두려면 넘었을 때의 동작(버리기, 거부, 비활성화)을 새로 정해야 하는데 모두 대가가 크므로, 7단계 실측 후 필요하면 별도 결정으로 제안 |
| 2. 동시 호출의 순서 기준 | 설계 §11.6에 정의. 선형화 지점은 **큐에 넣으며 lock을 얻은 순간**. `messageId`·`timestamp`는 그 전에 정해지므로 서로 다른 스레드에서는 저장 순서와 `timestamp` 순서가 다를 수 있음. 검증에 실패한 호출은 순서에 참여하지 않음. 콜백은 저장 순서대로 post됨 |
| 3. 비정상 종료 뒤 무한 적재 | `EventPipelineUnexpectedFailureTest` 5개 추가: `Error`가 나면 비활성화되고 handler로 넘어감 / 그 뒤 호출 5,000번 → 큐가 비어 있고 전부 `droppedStorageError`로 집계 / 새어 나온 `Exception`은 handler로 넘기지 않음 / 두 경로가 모두 실행돼도 콜백은 한 번 / 일반 `Exception`은 기존 재시도 경로 그대로. mutation check(비활성화 처리 제거)를 하자 4개가 실패함 |

## B. 4단계: facade

### 구현한 것
| 파일 | 역할 |
|---|---|
| `MiniAnalytics.kt` | 공개 facade. `@JvmStatic`, `@JvmOverloads`. 모든 함수를 `Exception` 가드로 감쌈 |
| `internal/AnalyticsClient.kt` | 초기화된 인스턴스. 운영 구성(`create`)은 `SqliteEventStore`, `Dispatchers.IO.limitedParallelism(1)`, `MainThreadExecutor`, `AndroidLogger`로 이루어짐. 생성자는 I/O 없음. `flush()`는 5단계 전까지 아무것도 하지 않음 |
| `internal/DefaultClientFactory.kt` | 운영용 생성기 (공개 패키지에 합성 클래스가 생기지 않도록 분리) |
| `internal/ProcessInfo.kt` | 메인 프로세스 확인. Android 9 미만에서는 `/proc/self/cmdline`을 읽으므로 **storage coroutine에서** 확인하고, 아니면 경고만 남김 |

### `initialize()` 동작 (Q-4a, R-6')
1. **null 검사**: `context is null` / `configuration is null`를 구분해서 `Log.e`로 남기고 `INVALID_CONFIGURATION`
2. **설정값 검증**: 위반 내용을 `Log.e`로 남기고 `INVALID_CONFIGURATION`. 설정을 고쳐서 다시 호출할 수 있음
3. **이미 초기화됨**: `ALREADY_INITIALIZED`. 설정이 다르면 바뀐 필드를 `Log.e`로 남김(`apiKey` 마스킹). 비활성 상태여도 다시 활성화하지 않음
4. `STARTED`: 비동기 초기화를 시작함 (DB 열기와 Identity 로드는 storage coroutine에서)
- 인스턴스를 만들다가 내부 오류가 나도 예외를 던지지 않고 `INVALID_CONFIGURATION`을 반환함

### 초기화 전 호출
- `track`/`screen`/`flush`: 경고 후 무시. **버퍼링하지 않으므로** 나중에 초기화해도 기록되지 않음
- `identify`/`reset`: 콜백이 있으면 `Failure(NOT_INITIALIZED)`를 지체 없이 메인 스레드로 post. `userId` 검증보다 우선

## 테스트 결과
`./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL, 220개 통과 (실패 0)**. 3단계 194개에서 26개가 늘었습니다.

| 추가된 테스트 | 개수 | 내용 |
|---|---|---|
| `EventPipelineUnexpectedFailureTest` | 5 | 위 A-3 |
| `MiniAnalyticsTest` | 14 | 아래 표 |
| `JavaFacadeNullTest` (Java) | 5 | `initialize(null, null)` 등 4가지 null 조합, 초기화 전후 모든 API에 null 전달, T-I3, String이 아닌 key를 가진 raw `Map` |
| `ProcessInfoTest` | 2 | 메인 프로세스 판단, 이름을 모를 때 잘못 경고하지 않음 |

**`MiniAnalyticsTest` 주요 항목**
| 테스트 | 확인 내용 |
|---|---|
| **T-L3** | 초기화 전 `reset`/`identify`(유효하지 않은 `userId` 포함) → 호출한 곳에서 직접 실행하지 않고, 메인 Looper를 한 번 처리하면 `NOT_INITIALIZED` (시간 진행 없음) |
| T-L3 로그아웃 흐름 | 초기화하지 않은 SDK에서도 콜백 기반 로그아웃이 진행됨 |
| 초기화 전 호출은 버퍼링하지 않음 | 초기화 뒤에 호출한 이벤트만 저장됨 |
| Q-4a | null 조합별로 반환값과 원인 메시지 확인 |
| 재초기화 | 같은 설정이면 error 로그 없음. 다른 설정이면 바뀐 필드가 로그에 남고 키 원문은 없으며, 기존 인스턴스가 유지됨 |
| 내부 오류 | 인스턴스 생성 실패 → 예외 없이 `INVALID_CONFIGURATION`, 이후 호출도 정상적으로 무시됨 |
| 최소 SDK (실제 SQLite + `Dispatchers.IO`) | `track` → `identify` → `screen`이 순서와 식별자대로 저장되고 identity 행이 갱신됨 |
| 콜백 스레드 | facade를 거친 `identify` 콜백이 commit 뒤 메인 Looper에서 실행됨 |
| **호출 스레드에서 저장소를 건드리지 않음** | storage dispatcher를 멈춘 상태에서 `initialize` + 모든 API 호출 → `loadIdentity`와 `commit` 호출 0회, DB 파일 없음. dispatcher를 진행시키면 그때 DB가 열리고 3건이 기록됨 |
| 비활성 상태 | `initialize`를 다시 불러도 활성화되지 않고, `reset`은 `STORAGE_ERROR` |

**메인 스레드 I/O 검증 방식에 대해**: 계획서에는 "StrictMode 또는 그에 준하는 방법"이라고 적었습니다. Robolectric의 SQLite는 StrictMode 디스크 검사를 확실하게 일으키지 않아서, StrictMode 테스트가 통과해도 증거가 되지 않는다고 판단했습니다. 대신 **"호출 스레드에서는 저장소 메서드가 한 번도 불리지 않고 DB 파일도 생기지 않는다"**를 직접 확인했습니다. 실제 기기에서의 StrictMode 확인은 7단계 샘플 앱에서 하겠습니다.

### 반복 실행과 mutation check
- 실제 스레드와 실제 `Dispatchers.IO`를 쓰는 테스트(`MiniAnalyticsTest`, `JavaFacadeNullTest`, `EventPipelineConcurrencyTest`, `LogoutFlowTest`)를 `--rerun-tasks`로 **10번 반복해서 10번 모두 통과**했습니다.
- mutation check:
  - `NOT_INITIALIZED` 콜백을 전달하지 않게 함 → T-L3 2개와 Java 테스트 1개 실패
  - 호출 스레드에서 저장소를 건드리게 함 → "호출 스레드에서 저장소를 건드리지 않음" 테스트 실패
  - 두 경우 모두 확인 후 원래 코드로 되돌리고 diff로 확인함

## 공개 API와 의존성
- 공개 패키지 클래스 **9개**: `Configuration`, `Configuration$Builder`, `FailureReason`, `IdentityCallback`, `IdentityResult`(+`Success`, `Failure`), `InitializeResult`, `MiniAnalytics`
- 처음 구현에서는 테스트용 함수 참조 때문에 공개 패키지에 **합성 클래스 2개(`MiniAnalytics$clientFactory$1` 등)와 public 합성 접근자(`access$createDefaultClient`)**가 생겼습니다. 1단계에서 기록한 후속 검증 항목으로 javap 확인을 하다가 발견했고, 기본 생성기를 `internal/DefaultClientFactory`로 옮겨서 제거했습니다.
- `MiniAnalytics`의 Java 공개 시그니처: `initialize(Context, Configuration)`, `track(String[, Map])`, `screen(String[, Map])`, `identify(String[, IdentityCallback])`, `reset([IdentityCallback])`, `flush()`. 모두 `static`. 이 밖에는 이름이 바뀐 `internal` 멤버만 있습니다(합의한 관례).
- 릴리스 런타임 의존성: `kotlin-stdlib 2.2.21`, `kotlinx-coroutines-core 1.10.2` (변경 없음)

## 확인이 필요한 사항
| ID | 내용 | 판단 |
|---|---|---|
| C-1 | `Error`를 스레드의 uncaught exception handler로 넘기면 Android에서는 보통 **앱 프로세스가 종료**됨 | "Error를 삼키지 않는다"는 지시에 따른 동작이며, 코루틴이 없었을 때와 같음. 다만 SDK 스레드의 `Error`가 앱 종료로 이어진다는 점을 명확히 승인받고 싶음 |
| C-2 | 이미 초기화된 뒤에 유효하지 않은 인자로 `initialize`를 호출하면 `ALREADY_INITIALIZED`가 아니라 `INVALID_CONFIGURATION`을 반환함 (검증 순서: null → 설정값 → 기존 인스턴스) | 호출 인자의 오류를 숨기지 않으려는 선택. 기존 인스턴스는 영향받지 않음 |
| C-3 | `@JvmStatic` 추가 (설계에는 명시되지 않았음) | Java에서 `MiniAnalytics.INSTANCE.track(...)` 대신 `MiniAnalytics.track(...)`으로 호출할 수 있게 함. 추가만 했으므로 호환성 영향 없음 |

## 남은 위험
| ID | 위험 | 대응 |
|---|---|---|
| S4-1 | 실제 기기의 StrictMode와 메인 스레드 I/O | 7단계 샘플 앱에서 `StrictMode.ThreadPolicy`(penaltyDeath)로 확인 |
| S4-2 | 메인 프로세스 확인은 Robolectric에서 의미 있게 검증할 수 없음 (판단 로직만 단위 테스트) | 7단계에서 필요하면 확인 |
| S4-3 | facade가 프로세스 전역 상태라 테스트끼리 간섭할 수 있음 | `@Before`/`@After`에서 `resetForTesting()`으로 격리함. 반복 실행에서도 문제 없음 |
