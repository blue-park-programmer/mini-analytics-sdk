# 3단계 보고서: 명령 큐와 storage coroutine

- 작성일: 2026-11-09
- 계획: [`v1-implementation-plan.md`](./v1-implementation-plan.md) 3단계

## 구현한 것

| 파일 | 역할 | 설계 근거 |
|---|---|---|
| `internal/pipeline/EventPipeline.kt` | 명령 큐(lock + `ArrayDeque`), 메모리 상한, conflated `storageSignal`, storage coroutine(Identity 로드·생성, K개 단위 트랜잭션, commit 후 `current` 갱신), 재시도와 비활성화 | §4.2, §4.3, §8, R-1, R-4', R-7'' |
| `internal/pipeline/CallbackDispatcher.kt` | 콜백 post, 예외 격리, `OnceIdentityCallback`(한 번만 호출하는 방어 장치), `MainThreadExecutor`(`Handler(Looper.getMainLooper())`) | §2.3, Q18, Q21 |
| `internal/pipeline/Counters.kt` | 관측 카운터 9종 (내부 전용) | §9 |
| `internal/log/Logger.kt` | `Logger` 인터페이스와 `AndroidLogger` | §8 |
| `build.gradle.kts` | `implementation(kotlinx-coroutines-core 1.10.2)`, `testImplementation(kotlinx-coroutines-test)` | §10 |

`EventPipeline`은 4단계에서 facade(`MiniAnalytics` → `AnalyticsClient`)가 그대로 위임할 내부 진입점입니다: `track`, `screen`, `identify`, `reset`. 5단계 Uploader를 위해 commit 후 저장 행 수를 알리는 훅(`onCommitted`)도 있습니다.

## 핵심 동작

### 호출 스레드
1. **lock 밖**: 이름 검증(위반 시 경고 후 무시), `identify`의 `userId` 검증(위반 시 `INVALID_ARGUMENT`를 post하고 큐에 넣지 않음), properties 복사(`PropertiesSanitizer`), `messageId`·`timestamp`·reset용 새 `anonymousId` 생성
2. **lock 안**: 큐에 추가하고 메모리 상한을 적용합니다. I/O는 없습니다. 비활성 상태라면 큐에 넣지 않습니다.
3. **lock 해제 후**: `storageSignal.trySend(Unit)` (conflated라서 항상 즉시 반환)

### storage coroutine
```
load identity (없으면 새 anonymousId 생성 후 commit) ── 실패: 재시도 → 비활성화
loop: 신호 대기
  batch = lock { 큐 앞에서 최대 50개를 꺼내 "commit 중" 목록으로 옮김 }
  재시도 경계 {
    prepare: current에서 시작해 명령 순서대로 Identity 적용 + 직렬화 (트랜잭션 단위 SimpleDateFormat)
    store.commit(이벤트 INSERT + identity UPSERT + 상한 정리)        ← 하나의 트랜잭션
  }  실패 → 1초, 5초 뒤 같은 batch로 재시도 → 그래도 실패하면 비활성화
  성공 → current = next, 카운터 반영, Identity 콜백 Success post, onCommitted(counts)
```
- **`current`는 commit이 성공했을 때만 바뀝니다.** 실패한 Identity는 어디에도 반영되지 않습니다 (R-7'' 조건).
- **commit 중인 명령은 메모리 상한으로 버려지지 않습니다.** 큐에서 꺼내 별도 목록에 두기 때문입니다. 설계의 "실패한 명령은 큐 맨 앞에 그대로"와 의미가 같습니다.
- prepare(직렬화)도 재시도 경계 안에 있습니다. 순수 계산이라 재시도해도 결과가 같고, 직렬화에서 버린 수는 commit이 성공한 뒤에만 카운터에 반영합니다(중복 집계 방지).
- `SQLiteFullException`이면 첫 재시도 전에 한 번 `reclaimSpace(0.25)`을 실행합니다.
- **비활성화**: lock 안에서 `disabled = true`로 바꾸고 큐와 commit 중인 명령을 모두 비웁니다. 버린 수는 `droppedStorageError`에 더하고, Identity 콜백에는 `Failure(STORAGE_ERROR)`를 한 번씩 post합니다. error 로그는 한 번만 남깁니다. 이후 호출은 큐에 들어가지 않고 Identity 콜백은 즉시 실패를 받습니다.

## 테스트 결과
`./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL, 194개 통과 (실패 0)**. 3단계에서 40개가 늘었습니다.

| 테스트 | 개수 | 방식 |
|---|---|---|
| `EventPipelineTest` | 19 | Robolectric + 가상 시간 |
| `EventPipelineFailureTest` | 7 | Robolectric + 가상 시간 + `FaultyEventStore` |
| `LogoutFlowTest` | 6 | Robolectric + 가상 시간 / 실제 스레드 |
| `CallbackDispatcherTest` | 6 | Robolectric (메인 Looper) |
| `EventPipelineConcurrencyTest` | 2 | Robolectric + 실제 스레드 8개 |

### 계획에 있던 필수 테스트
| ID | 테스트 | 결과 |
|---|---|---|
| T-L1 | 저장소가 commit 안에서 멈춰 있어도 `reset`/`identify`/`track`이 200ms 안에 반환 (실제 스레드) | 통과 |
| T-L2 | 저장소 실패가 계속됨 → 0초, 1초, 6초에 시도한 뒤 비활성화, `Failure(STORAGE_ERROR)`가 한 번, Identity 변경 없음 | 통과 |
| T-L3 | `initialize()` 전 `reset` → `NOT_INITIALIZED` | **4단계에서 end-to-end로 검증** (facade가 아직 없음). 지연 없이 post되는 경로 자체는 `CallbackDispatcher` 테스트로 확인 |
| T-L4 | 비활성 상태에서 `reset`/`identify` → 가상 시간을 진행하지 않아도 `Failure(STORAGE_ERROR)` 전달 | 통과 |
| T-L5 | 콜백이 예외를 던져도 호출부와 이후 처리에 영향 없음 | 통과 |
| T-L6 | 호스트 로그아웃 흐름(타임아웃 2초): 저장소가 느리면 2초에 로그아웃 진행, 그동안 다른 메인 작업이 실행됨, 늦게 온 콜백은 무해함. 콜백이 오지 않는 경우도 2초에 진행 | 통과 |
| T-L7 | `Failure`를 받으면 기다리지 않고 바로 로그아웃 진행 | 통과 |
| T-I1 | 유효하지 않은 `userId` 5종 → 각각 `INVALID_ARGUMENT` 한 번, 저장된 Identity와 identify 행 수 불변 | 통과 |
| T-I2 | 유효하지 않은 `identify` 뒤의 `track`은 이전 사용자(`A`)로 기록 | 통과 |

### 그 밖의 주요 테스트
- `track(T1)` → `reset` → `track(T2)`: T1은 이전 Identity, T2는 새 Identity. 이미 저장된 payload는 그 뒤 `reset`으로 바뀌지 않음
- DB 로드 전 호출: 순서가 유지되고, `messageId`와 `timestamp`가 로드 시점이 아닌 **호출 시점** 값임
- 콜백은 commit 뒤에만 post되고(콜백 시점에 디스크에 이미 반영됨), 정확히 한 번 호출됨
- 메모리 상한: track/screen만 오래된 것부터 버리고 `reset`/`identify`는 버리지 않음
- 트랜잭션은 50개 단위: 명령 120개 → commit 3번
- 재시도 중에 들어온 track은 앞의 `reset`이 commit되기 전에는 기록되지 않고, 성공한 뒤 새 Identity로 기록됨
- 디스크 부족이면 한 번 공간을 확보하고 재시도. 다른 오류에서는 공간 확보를 하지 않음
- **동시성 (실제 스레드 8개 × 100회)**: 스레드마다 호출한 순서가 저장 순서와 같음. 각 track의 `userId`는 저장 순서상 바로 앞 identify의 값임. reset이 섞여도 "anonymousId가 바뀐 직후의 track은 `userId = null`, 같은 anonymousId 안에서는 identify 없이 `userId`가 바뀌지 않음"이 성립

### 반복 실행
실제 스레드를 쓰는 테스트(`EventPipelineConcurrencyTest`, `LogoutFlowTest`)를 `--rerun-tasks`로 **10번 반복해서 10번 모두 통과**했습니다.

### mutation check
| 일부러 넣은 결함 | 실패한 테스트 |
|---|---|
| 콜백을 post하지 않고 바로 실행 | 5개 (`callback is posted, not run inline`, `identity callbacks are posted only after the commit…`, 메인 Looper 테스트 등) |
| commit 전에 메모리의 `current`를 먼저 바꿈 (승인되지 않은 R-7' 방식) | 2개 (`failed commit is retried with the same commands and nothing leaks…` 등) |
| 메모리 상한이 Identity 명령도 버림 | 2개 (`memory queue limit … never identity commands` 등) |

각 결함을 확인한 뒤 원래 코드로 되돌렸습니다. 되돌린 파일이 백업과 같은지 diff로 확인했습니다.

## 테스트를 만들면서 바로잡은 것 (구현 결함 아님)
1. `runTest`의 `backgroundScope`에서 실행되는 작업은 `advanceUntilIdle()`이 실행하지 않습니다. 그래서 처음에는 storage coroutine이 전혀 돌지 않아 테스트 26개가 실패했습니다. 테스트 스케줄러를 공유하는 foreground dispatcher로 바꿨습니다(`pipelineScope()`).
2. 동시 reset 테스트가 identify를 120번 호출해서 승인된 identify 행 상한(100, R-4')을 넘었습니다. 그래서 오래된 identify 행이 **설계대로** 지워졌고, 테스트의 불변식("사용자 변경 앞에는 identify 메시지가 있다")이 깨졌습니다. identify 수를 상한 아래(64)로 줄였습니다. 이 현상은 문서화된 유실 조건 L-3(서로 다른 사용자 전환이 100번을 넘는 경우 연결 정보 유실)이 실제로 일어나는 모습이기도 합니다.
3. `JSONObject`는 `equals`를 구현하지 않으므로, payload를 비교할 때는 원본 문자열을 비교하도록 바꿨습니다.

## 공개 API와 의존성
- 공개 패키지 클래스는 그대로입니다 (9개).
- 릴리스 런타임 의존성: `kotlin-stdlib 2.2.21`, `kotlinx-coroutines-core 1.10.2`(+ `-core-jvm`, `-bom`, `annotations`). `-android` 모듈은 들어오지 않습니다.

## 4단계 전에 결정하거나 확인할 사항
| ID | 내용 | 제안 |
|---|---|---|
| Q-4a | (2단계부터 미결정) `initialize(context = null)` 또는 `configuration = null`일 때의 반환값 | `INVALID_CONFIGURATION`을 쓸지, 새 값을 추가할지 결정 필요 |
| Q-4b | storage coroutine에서 `Exception`이 아닌 `Error`(예: `OutOfMemoryError`)가 나면 coroutine이 종료되는데, 이때 `disabled` 표시가 되지 않아 이후 명령이 큐에 계속 쌓임(메모리 상한까지) | 4단계에서 scope의 `CoroutineExceptionHandler`가 파이프라인을 비활성 상태로 바꾸게 함. 설계 §8.2의 "예상하지 못한 예외는 같은 경로로 처리"와 일치하는 구현 보강이며, 새 정책은 아니라고 판단함 |

## 남은 위험
| ID | 위험 | 대응 |
|---|---|---|
| S3-1 | 호출부터 commit까지의 실제 지연(A-2)은 측정하지 않음. 가상 시간과 Robolectric은 실제 기기 성능을 대표하지 않음 | 7단계 샘플 앱/에뮬레이터에서 측정 |
| S3-2 | 동시성 테스트는 불변식 기반이라 모든 interleaving을 보장하지는 않음 | 10회 반복으로 보완. 이론적 근거는 "lock 하나 + 단일 소비자" 구조 |
| S3-3 | 메모리 사용량 상한은 "대기 1,000개 + commit 중 최대 50개 + Identity 명령"이며, Identity 명령 수에는 상한이 없음 (설계상 버리지 않음) | 현실적인 호출 빈도에서는 문제가 없다고 판단. 문서화 |
| S3-4 | 콜백 예외 격리는 `Exception`만 잡음 (`Error`는 메인 스레드에서 원래대로 전파됨) | 호스트 코드의 `Error`를 SDK가 삼키지 않는 쪽이 맞다고 판단 |
