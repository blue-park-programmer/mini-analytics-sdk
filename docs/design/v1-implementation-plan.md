# mini-analytics-sdk v1 구현 계획

- 근거: [`v1-design.md`](./v1-design.md) (승인된 설계)
- 작성일: 2026-11-04
- 상태: **승인 대기**

## 진행 규칙
1. 단계마다 **빌드 결과, 테스트 결과, 핵심 코드의 동작 설명**을 보고합니다.
2. **사용자가 승인하기 전에는 다음 단계로 넘어가지 않습니다.**
3. 각 단계는 그 자체로 빌드와 테스트가 통과하는 상태로 끝냅니다.
4. 설계와 다르게 구현해야 할 이유가 생기면, 구현하기 전에 멈추고 설계 변경을 먼저 제안합니다.
5. 공개 API는 설계 §2에 있는 것만 만듭니다.

## 단계 순서의 이유
- 순수 로직(외부 의존 없음) → 저장소 → 명령 파이프라인 → facade 순서로 갑니다. 그러면 4단계가 끝났을 때 **"수집해서 영구 저장하는" 최소 SDK**가 완성됩니다.
- 네트워크(5~6단계)는 그 위에 올립니다. 배포와 통합 검증(7단계)은 기능이 고정된 뒤에 합니다.
- 각 단계는 앞 단계의 인터페이스(`EventStore`, `Transport`, clock)를 가짜 구현으로 바꿔서 독립적으로 테스트합니다.

---

## 0단계. 프로젝트 골격

**작업**
- AGP, Kotlin, Gradle wrapper, coroutines, Robolectric의 **호환되는 안정 버전을 확인해서** `gradle/libs.versions.toml`에 고정합니다 (설계 §10).
- 모듈 `:mini-analytics`(Android Library)를 만듭니다. 설정: `minSdk 23`, `compileSdk 36`, `jvmTarget 11`, `explicitApi()`.
- `local.properties`의 `sdk.dir`을 설정합니다(`ANDROID_HOME`이 설정되어 있지 않음). 이 파일은 `.gitignore`에 넣습니다.
- 빈 `MiniAnalytics` object를 만들고, 동작 확인용 단위 테스트를 하나 둡니다.

**검증**
- `./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest`가 성공합니다.
- AAR이 생성됩니다. 런타임 의존성이 아직 없는 것을 확인합니다.

**설명할 내용**
- 버전을 고른 근거와 각 빌드 설정(minSdk, jvmTarget, explicitApi)의 의미

---

## 1단계. 순수 로직 (Android 의존 최소)

**작업**
- `Identity`, `Command`(Track / Screen / Identify / Reset) 모델
- `PropertiesSanitizer`: 설계 §7.3의 타입 처리, 제한, 순환 참조, 노드 예산, 순회 중 예외 처리
- `EventSerializer`: `org.json`, UTC ISO-8601 변환(트랜잭션 단위 `SimpleDateFormat`), 32KB 검사
- `Configuration`과 동기 검증 (`INVALID_CONFIGURATION`의 조건)
- 공개 타입: `InitializeResult`, `IdentityCallback`, `IdentityResult`, `FailureReason`

**테스트**
- Sanitizer: 허용 타입과 변환, 각 제한의 경계값, 순환 참조, 다른 스레드가 Map을 동시에 수정하는 경우, 예외가 새어 나가지 않음
- Serializer: 스키마 형태, `null` 처리, 밀리초 UTC 형식, 32KB 경계
- `org.json`은 JVM 단위 테스트에서 Android stub이므로, Robolectric으로 돌리거나 테스트 전용 `org.json` 구현을 씁니다(0단계에서 결정).

**설명할 내용**
- properties를 "잘라내지 않고 그대로 담거나 빼는" 이유, 노드 예산을 넘으면 전체를 비우는 이유

---

## 2단계. `EventStore` (SQLite)

**작업**
- `internal interface EventStore`와 `SqliteEventStore`(`SQLiteOpenHelper`)
- 스키마: `events`(AUTOINCREMENT, UNIQUE `message_id`, `type`, `payload`, `size`), `identity`(단일 행, `version`)
- 트랜잭션 API: 명령 묶음 적용, 상한 두 종류(`maxQueuedEvents`, `maxQueuedIdentifies`) 정리, 공간 확보
- `readBatch(limit, maxBytes, afterId?)`, `deleteByIds(ids)`, `loadIdentity()`
- 테스트용 `InMemoryEventStore`와 오류를 주입하는 `FaultyEventStore`

**테스트 (Robolectric)**
- rowid 순서와 재사용되지 않음, UNIQUE 제약
- 상한 정리가 track/screen과 identify를 따로 다루는지
- 롤백하면 events와 identity가 함께 되돌아가는지(원자성)
- `readBatch`의 개수와 바이트 경계, 없는 id를 삭제할 때 무시하는지

**설명할 내용**
- 트랜잭션 경계와 원자성, `AUTOINCREMENT`가 필요한 이유

---

## 3단계. 명령 큐와 storage coroutine

**작업**
- lock으로 보호하는 `ArrayDeque<Command>`, 메모리 상한(Track/Screen만 버림), conflated `storageSignal`
- storage coroutine: identity 로드 → peek K → 트랜잭션 → 성공 시 제거와 `current` 갱신 → 콜백 post
- 콜백 전달: 메인 Looper로 post, 정확히 한 번, 예외 격리. 테스트에서는 실행 위치를 주입
- 저장소 오류: 재시도(즉시 / 1초 / 5초) → 비활성화. 대기 중인 콜백에 Failure, 비활성 상태에서의 호출 처리 (설계 §8)
- 관측 카운터(내부)

**테스트 (`kotlinx-coroutines-test` 가상 시간 + Robolectric)**
- `track(T1)` → `reset()` → `track(T2)`: T1은 이전 Identity, T2는 새 Identity
- 여러 스레드에서 동시에 `track`/`identify`/`reset`을 호출해도 저장 순서와 식별자 변화가 모순되지 않음
- DB 로드 전에 호출해도 유실되지 않고, `messageId`/`timestamp`가 호출 시점 값임
- `FaultyEventStore`로 실패를 주입: 재시도 중에는 T2가 기록되지 않음 → 재시도가 성공하면 새 Identity로 기록됨 / 3번 실패하면 비활성화되고 콜백은 Failure
- 콜백이 정확히 한 번 호출되고, 메인 스레드에서 실행되며, 콜백 안의 예외가 격리됨

**필수 테스트: 로그아웃이 SDK 저장 실패 때문에 막히지 않음** (0단계에서 사용자 요청으로 추가, 설계 §12 G-3a~c)
| ID | 시나리오 | 기대 결과 |
|---|---|---|
| T-L1 | 저장소가 느리거나(지연 주입) 계속 실패할 때 `reset()` 호출 | `reset()`이 **즉시 반환**함 (호출 스레드에서 I/O나 대기가 없음) |
| T-L2 | 저장소가 계속 실패함 | 재시도 대기(1초, 5초)가 끝나고 비활성화되면 `Failure(STORAGE_ERROR)`가 **한 번** 전달됨 (가상 시간) |
| T-L3 | `initialize()` 전에 `reset(callback)` | `Failure(NOT_INITIALIZED)`가 지체 없이 전달됨 |
| T-L4 | 비활성 상태에서 `reset(callback)` | `Failure(STORAGE_ERROR)`가 즉시 전달됨 |
| T-L5 | 콜백이 예외를 던짐 | `reset()` 호출부와 SDK 상태에 영향 없음 |
| T-L6 | 테스트 코드 안에 만든 "호스트 로그아웃 흐름"(콜백 대기 + 타임아웃 2초)에서 콜백이 늦거나 오지 않음 | 타임아웃이 지나면 로그아웃이 진행되고, 메인 스레드가 막히지 않음 |
| T-L7 | 위 흐름에서 `Failure`를 받음 | 로그아웃이 그대로 진행됨 |

**필수 테스트: 유효하지 않은 `userId`** (1단계 D-5에서 추가, 설계 §2.3)
| ID | 시나리오 | 기대 결과 |
|---|---|---|
| T-I1 | `identify("A")` commit 후 `identify("")`, `identify("   ")`, `identify(257자)` | 각 호출마다 `Failure(INVALID_ARGUMENT)`가 한 번 전달됨. 저장된 Identity는 `userId = "A"`, `anonymousId` 그대로. identify 행이 추가되지 않음 |
| T-I2 | 유효하지 않은 `identify` 뒤의 `track` | 이전 Identity(`A`)로 기록됨 |
| T-I3 | Java에서 `identify(null, callback)` (4단계 Java 테스트) | NPE 없이 `Failure(INVALID_ARGUMENT)` |

**설명할 내용**
- 왜 lock 하나와 단일 coroutine만으로 원자성과 순서가 동시에 보장되는지
- 재시도 중 큐에 남은 명령의 처리

---

## 4단계. facade와 최소 SDK (전송 없음)

**작업**
- `MiniAnalytics` facade와 `AnalyticsClient` 연결
- `initialize()`: 동기 검증, `InitializeResult`, `applicationContext`, 메인 프로세스 확인, 재초기화 시 바뀐 필드 로그
- 초기화 전 호출 no-op과 `NOT_INITIALIZED` 콜백
- `track`/`screen`/`identify`/`reset`/`flush`를 공개 (이 단계의 `flush`는 아무 동작도 하지 않음)

**검증**
- Robolectric 통합 테스트: initialize → 이벤트 → DB에 행이 생김
- 메인 스레드 I/O 검사: 테스트에서 `StrictMode` 또는 그에 준하는 방법으로 메인 스레드 디스크 접근이 없음을 확인
- 공개 API 목록을 검토 (`explicitApi()` 결과)

**설명할 내용**
- 이 시점의 SDK가 보장하는 것(수집과 영구 저장)과 아직 하지 않는 것

---

## 5단계. 전송 파이프라인

**작업**
- `internal interface Transport`와 `HttpUrlConnectionTransport` (HTTPS 강제, 테스트에서 명시적으로 허용한 경우만 예외, 헤더, 15초 타임아웃)
- envelope 구성(`sentAt`, `context`, `batch`)
- Uploader coroutine: single in-flight, `uploadSignal`, `flushAt` / `flushInterval` ticker / `flush()`
- 응답 분류(설계 §6.3), 지수 backoff + full jitter, `Retry-After`, `413` 축소, `401`/`403`
- 재시작 후 남은 이벤트 전송

**테스트**
- 가짜 `Transport` + 가상 시간: 각 trigger, backoff 중에 trigger와 `flush()`가 시도를 앞당기지 않음, jitter 범위, `Retry-After` 상한, `413`을 1건까지 줄임
- 전송 중 상한 정리가 일어나도 모순이 없음

**설명할 내용**
- single in-flight와 conflated 신호가 중복 요청을 막는 원리, 응답 분류 표

---

## 6단계. 서버 계약과 단건 격리 (Mock Server)

**작업**
- 단건 격리: `isolateThroughId`, `invalid_event` + `messageId` 일치 판정, 프로세스당 20건 상한, 보류
- 2xx `rejected` 목록 처리와 "전부 거부" 경고
- Mock Server: `MockWebServer` 기반의 **계약 테스트용** 서버. `messageId` 중복 제거, identify 멱등 처리, 응답 유실(응답 직전 연결 끊기), 일반 400, 코드가 있는 400, 부분 `rejected`를 구현. 위치(별도 모듈 또는 테스트 소스)는 이 단계를 시작할 때 결정

**테스트**
- 응답 유실 → 재전송 → 서버에는 1건만 저장
- 일반 400만 돌려주는 서버 → 삭제 0건, 최대 backoff
- 모든 이벤트에 `invalid_event`를 붙이는 서버 → 20건만 삭제되고 나머지는 보류
- 문제 이벤트 1건 + 정상 이벤트 → 문제 이벤트만 삭제

**설명할 내용**
- "정상적인 영구 거부"와 "서버 오류로 인한 대량 거부"를 구분하는 기준

---

## 7단계. 배포와 통합 검증

**작업**
- `maven-publish`: AAR, POM, sources jar → `mavenLocal()`과 빌드 디렉터리의 로컬 저장소
- `:sample` 호스트 앱: 로컬 Maven에서 SDK를 받아 통합 가이드(설계 §12)대로 사용
- 전이 의존성 해결 확인(A-4), Kotlin Metadata 호환성 검증으로 지원 Kotlin 버전 결정(A-5)
- 필요하면 에뮬레이터 instrumented test: commit 전후의 강제 종료(A-2, A-7)

- **공개 API 표면 검증 (후속 검증 항목, 1단계에서 추가)**: Kotlin `internal`은 Java에서 public으로 보이는 언어 특성이 있어, `.internal` 패키지 관례로 수용했습니다. 의도하지 않은 공개 API가 추가되지 않도록 다음을 확인합니다.
  - 공개 패키지(`io.github.dduddupark.minianalytics`)의 클래스 목록과 public 멤버가 설계 §2와 일치하는지 (javap 기반)
  - 이 확인을 자동화할 방법(예: Kotlin ABI 검증 도구 도입)을 검토하고, 도입 여부를 사용자에게 묻기
  - 매 단계 보고서에도 공개 패키지 클래스 목록을 포함

**검증**
- 샘플 앱 빌드, `dependencies` 출력, 생성된 POM 확인
- 샘플 앱 → 로컬 Mock Server 전송 확인

**설명할 내용**
- POM에 들어간 의존성과 그 범위, 호스트 앱이 받게 되는 것

---

## 8단계. 문서

**작업**
- `README.md`: 설치, 통합 가이드, 서버 계약, 보장과 유실 조건과 한계 (설계 §6.5, §11, §12)
- 공개 API KDoc (`InitializeResult` 계약, 콜백 의미, 스레드)
- 측정 결과로 가정(A-2, A-3, A-8)을 갱신

---

## 위험과 대응
| 위험 | 대응 |
|---|---|
| 버전 조합 문제 (AGP / Kotlin / Robolectric / JDK) | 0단계에서 먼저 확정하고 고정 |
| `org.json`이 JVM 테스트에서 stub임 | 0~1단계에서 테스트 방식 결정 |
| 동시성 테스트가 불안정(flaky)함 | 가상 시간과 주입 가능한 dispatcher를 쓰고, 실제 스레드 테스트는 반복 실행으로 확인 |
| Robolectric과 실제 기기의 차이 | 강제 종료 시나리오만 instrumented test로 보완 |
