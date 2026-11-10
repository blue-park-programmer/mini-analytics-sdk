# mini-analytics-sdk v1 설계 문서

- 상태: **승인된 설계** (구현 전)
- 최종 갱신: 2026-11-10 (최종: 7단계 P7-1, P7-2b, P7-4 반영)
- 근거: 설계 인터뷰 Q1–Q27, 설계 검토 1~3차 ([`v1-design-review.md`](./v1-design-review.md))
- 표기: 각 결정 옆의 `(Qn)`은 인터뷰 질문 번호이고, `(R-n)`은 검토에서 승인된 항목입니다. 검토를 거치며 대체된 결정은 §15에 기록합니다.

---

## 0. 목표와 원칙

**목표**
호스트 앱에 안전하게 통합할 수 있는 Native Android Analytics SDK를 설계하고 구현한다. 주요 설계 결정을 코드와 테스트로 검증해서, 스스로 설명하고 개선할 수 있는 수준에 도달한다. 학습 목적이지만 프로덕션 수준의 안정성과 유지보수성을 목표로 한다.

**원칙**
1. **호스트 앱을 해치지 않는다.** 메인 스레드에서 디스크·네트워크 I/O를 하지 않고, 공개 API는 예외를 던지지 않으며, 외부 의존성을 최소화한다.
2. **유실 조건과 한계를 숨기지 않는다.** 보장하는 것과 보장하지 않는 것을 명시한다 (§11).
3. 불필요한 기능, 공개 API, 상태는 늘리지 않는다. 다만 **필수적인 안정성 설계는 생략하지 않는다.**

---

## 1. 범위

| 항목 | 결정 |
|---|---|
| 성격 | 학습·포트폴리오 프로젝트, 품질은 프로덕션 수준 (Q1) |
| 플랫폼 | Native Android, Kotlin. v1은 Android 전용이며 iOS 확장 가능성만 고려 (Q2) |
| 형태 | Kotlin + Gradle Android Library 모듈, AAR/Maven 배포 연습 (Q3) |
| 서버 | SDK만 개발. 설정 가능한 HTTP endpoint로 전송. 테스트용 Mock Server 허용 (Q4) |
| v1 기능 | 이벤트 수집, 로컬 큐, 배치 전송, 실패 재시도 (Q6) |

---

## 2. 공개 API

```kotlin
public object MiniAnalytics {                                     // 4단계 구현 완료
    @JvmStatic public fun initialize(context: Context?, configuration: Configuration?): InitializeResult
    @JvmStatic @JvmOverloads public fun track(event: String?, properties: Map<String, Any?>? = null)
    @JvmStatic @JvmOverloads public fun screen(name: String?, properties: Map<String, Any?>? = null)
    @JvmStatic @JvmOverloads public fun identify(userId: String?, callback: IdentityCallback? = null)
    @JvmStatic @JvmOverloads public fun reset(callback: IdentityCallback? = null)
    @JvmStatic public fun flush()                                 // 5단계 전까지는 아무것도 하지 않음
}

public enum class InitializeResult { STARTED, ALREADY_INITIALIZED, INVALID_CONFIGURATION }

public fun interface IdentityCallback { public fun onComplete(result: IdentityResult) }

public sealed class IdentityResult {
    public object Success : IdentityResult()
    public class Failure(public val reason: FailureReason, public val cause: Throwable?) : IdentityResult()
}

public enum class FailureReason { STORAGE_ERROR, NOT_INITIALIZED, INVALID_ARGUMENT }

public class Configuration private constructor(/* internal 필드 */) {
    public class Builder(apiKey: String?, endpoint: String?) {   // 1단계 구현 완료
        public fun flushAt(value: Int): Builder
        public fun flushIntervalMillis(value: Long): Builder
        public fun maxQueuedEvents(value: Int): Builder
        public fun trustInvalidEventResponses(value: Boolean): Builder  // 기본 false (7단계 P7-1)
        public fun build(): Configuration                         // 검증하지 않고 예외도 던지지 않음
    }
}
```
(위 시그니처는 구현과 같습니다. `Configuration`, `InitializeResult`, `IdentityCallback`, `IdentityResult`, `FailureReason`은 1단계에서, `MiniAnalytics`는 4단계에서 구현했습니다.)

### 2.1 `initialize()` (Q19, R-6')
- 프로세스 단위 싱글톤 facade입니다. 실제 구현은 `internal class AnalyticsClient`이며, 테스트에서는 의존성을 주입해서 직접 생성합니다.
- 메인 스레드에서 호출해도 **I/O를 하지 않습니다.** 객체를 만들고 비동기 초기화(DB 열기, Identity 로드)를 시작한 뒤 즉시 반환합니다.
- `context`는 `applicationContext`로 바꿔서 보관합니다.
- 반환값의 계약은 다음과 같습니다.
  - `STARTED`: 이번 호출로 설정을 등록하고 비동기 초기화를 **시작**했다.
  - `ALREADY_INITIALIZED`: 이미 초기화되어 있어서 이번 호출을 무시했다. 설정이 다르면 `Log.e`로 달라진 필드를 남긴다(`apiKey`는 마스킹).
  - `INVALID_CONFIGURATION`: 동기 검증에 실패해서 초기화하지 않았다. 설정을 고쳐서 다시 호출할 수 있다.
- **동기 검증 규칙 (v1 초기 정책)**: 아래 값은 **실측으로 확정한 값이 아니라 v1의 초기 정책**입니다. 7단계 샘플 앱 측정(A-3, A-8) 후 조정할 수 있습니다.
  | 항목 | 규칙 | 기본값 |
  |---|---|---|
  | `apiKey` | null이 아니고 공백뿐이 아님 | — |
  | `endpoint` | null이 아니고, URI로 해석되고, 스킴이 `https`(대소문자 무관)이며 host가 있음. `http`는 테스트에서 명시적으로 허용한 경우만 | — |
  | `maxQueuedEvents` | 1..10,000 | 1,000 |
  | `flushAt` | 1..`maxQueuedEvents` | 20 |
  | `flushIntervalMillis` | 1,000 이상 | 30,000 |
  | `trustInvalidEventResponses` | 없음 (true/false) | **false** (7단계 P7-1) |
  - **반환값은 비동기 초기화의 성공 여부를 나타내지 않습니다.** 비동기 초기화가 실패하면 SDK는 비활성 상태가 되고(§8), 그 사실은 `Log.e`와 이후 `identify`/`reset` 콜백의 `Failure(STORAGE_ERROR)`로 드러납니다.
- **`context` 또는 `configuration`이 null이면** `INVALID_CONFIGURATION`을 반환합니다 (3단계 Q-4a). `Log.e` 메시지로 원인을 구분합니다(`context is null` / `configuration is null`). 둘 다 null이면 두 원인을 모두 남깁니다. 검증 순서는 **null 검사 → 설정값 검증 → 이미 초기화되었는지 확인**입니다. 그래서 이미 초기화된 뒤라도 인자가 유효하지 않으면 `INVALID_CONFIGURATION`을 반환하며, 기존 인스턴스는 그대로 유지됩니다. 어느 단계에서도 예외를 던지지 않습니다. 내부 오류로 인스턴스를 만들지 못한 경우에도 `INVALID_CONFIGURATION`을 반환하고 `Log.e`를 남깁니다.
- `initialize()` 전에 들어온 호출은 no-op과 경고 로그로 처리합니다. 콜백이 있으면 `Failure(NOT_INITIALIZED)`를 지체 없이 메인 스레드로 post합니다. 이때 `userId` 검증보다 `NOT_INITIALIZED`가 먼저입니다. **`initialize()` 전 호출은 버퍼링하지 않으므로**, 나중에 초기화해도 기록되지 않습니다.
- Java 편의를 위해 `MiniAnalytics`의 함수에는 `@JvmStatic`을 붙입니다 (`MiniAnalytics.track(...)` 형태로 호출 가능).
- 멀티 프로세스는 지원하지 않습니다. 메인 프로세스가 아닌 곳에서 초기화하면 경고 로그를 남깁니다.

### 2.2 이벤트 API (Q5, Q22, Q25, Q26)
- `track()`과 `screen()`은 같은 파이프라인을 쓰고, 다른 점은 `type`과 이름 필드뿐입니다.
- 이름이 §2.5의 식별 문자열 규칙을 어기면(null, 공백뿐, 256자 초과) 경고를 남기고 호출을 무시합니다 (R-9').
- 자동 화면 추적은 하지 않습니다.
- `flush()`는 비동기 전송 **요청**이며 완료를 보장하지 않습니다. backoff 중이면 시도 시각을 앞당기지 않습니다.

### 2.3 Identity API (Q8, Q16–Q18, Q21, R-10'')
- `identify(userId)`: `userId`를 설정하고, 같은 트랜잭션에서 identify 메시지를 저장합니다. **`userId`가 현재와 같아도 항상 메시지를 만듭니다.**
  - `userId`가 §2.5의 식별 문자열 규칙을 어기면(null, 공백뿐, 256자 초과) **Identity를 바꾸지 않고 메시지도 만들지 않으며**, 콜백에 `Failure(INVALID_ARGUMENT)`를 전달합니다 (1단계 D-5). 검사는 호출 스레드에서 하며, 명령을 큐에 넣지 않습니다.
- `reset()`: `userId`를 지우고, **이미 익명 상태여도 호출할 때마다** 새 `anonymousId`를 발급합니다. 메시지는 전송하지 않습니다.
- 콜백(선택)
  - Success: 그 변경의 효과를 포함하는 Identity가 SQLite에 commit되었다는 뜻입니다.
  - Failure: commit되지 않았다는 뜻입니다 (`STORAGE_ERROR` / `NOT_INITIALIZED` / `INVALID_ARGUMENT`).
  - **메인 스레드로 post해서** 실행합니다. SDK의 lock이나 storage coroutine 안에서 직접 실행하지 않습니다.
  - 콜백 안에서 난 예외는 잡아서 로그로 남기고, SDK 상태에는 영향을 주지 않습니다.
  - **프로세스가 살아 있고 SDK가 결과를 처리할 수 있는 경우에 한해 정확히 한 번** 호출됩니다. 프로세스가 강제 종료되면 호출되지 않을 수 있습니다.

### 2.4 Java 호출자의 null 입력 정책 (1단계 D-4)
**원칙**
- SDK의 공개 진입점은 Java 호출자가 `null`을 넘겨도 **예기치 않은 NPE를 던지지 않습니다.**
- 이를 위해 **Java에서 null이 들어올 수 있고, null을 "유효하지 않은 입력"으로 처리할 수 있는 파라미터만** nullable로 선언합니다. 모든 API를 일괄적으로 nullable로 바꾸지 않습니다.
- 컴파일러의 파라미터 null 검사를 전역으로 끄지 않습니다.
- SDK가 만들어서 호스트에 전달하는 타입(`IdentityResult.Failure` 생성자 등)은 이 정책의 대상이 아닙니다. 호스트가 직접 생성할 이유가 없기 때문입니다.
- 각 API의 Java null 동작은 **Java로 작성한 테스트**로 검증합니다 (`src/test/java`).

**API별 정책**
| API | 파라미터 | Kotlin 선언 | null일 때 | 상태 |
|---|---|---|---|---|
| `Configuration.Builder` | `apiKey`, `endpoint` | `String?` | `build()`는 정상, `initialize()` 검증에서 `INVALID_CONFIGURATION` | 1단계 구현·테스트 완료 |
| `Configuration.Builder` setter | `Int`, `Long` | 원시 타입 | 해당 없음 (Java에서 null 불가) | 완료 |
| `track` | `event` | `String?` | 이름 규칙 위반 → 경고 후 무시 | 4단계 |
| `track`, `screen` | `properties` | `Map<String, Any?>?`, 기본값 `null` | 빈 properties로 처리 | 4단계 |
| `screen` | `name` | `String?` | 이름 규칙 위반 → 경고 후 무시 | 4단계 |
| `identify` | `userId` | `String?` | `Failure(INVALID_ARGUMENT)`, Identity 유지 | 4단계 |
| `identify`, `reset` | `callback` | `IdentityCallback?` (원래 선택값) | 콜백 없이 동작 | 4단계 |
| `initialize` | `context`, `configuration` | `Context?`, `Configuration?` | `INVALID_CONFIGURATION`, `Log.e`로 원인 구분 | 결정됨 (Q-4a), 4단계 구현 |

- **Kotlin 사용성 영향**: 문자열 파라미터가 nullable이면 Kotlin 사용자도 컴파일 오류 없이 null을 넘길 수 있습니다. 그 경우에도 결과는 "무시 + 경고" 또는 `INVALID_ARGUMENT`로, Java와 같습니다. 비 null 값을 넘기는 일반적인 Kotlin 코드는 영향이 없습니다.
- 공개 API 안에서 난 `Exception`은 잡아서 로그를 남기고 호출을 무시합니다. `Error`는 잡지 않습니다 (§8.4).

### 2.5 식별 문자열 규칙 (event 이름, screen 이름, `userId`)
| 항목 | 규칙 |
|---|---|
| 길이 기준 | Kotlin `String.length`, 즉 **UTF-16 code unit 수**. 한글 한 글자는 1, 이모지 하나는 대개 2로 셈 |
| 허용 길이 | 1~256 |
| 공백 | 모든 문자가 공백(`Char.isWhitespace`, 전각 공백 등 유니코드 공백 포함)이면 유효하지 않음 |
| 다듬기 | **하지 않음**. 앞뒤 공백을 그대로 보존하며, 길이도 원래 값 기준 |
| 잘라내기 | **하지 않음**. 너무 길면 거부 |
| null | 유효하지 않음 |

같은 길이 기준(UTF-16 code unit)을 properties의 key 길이(256)와 문자열 값 길이(8,192)에도 적용합니다 (§7.3).

---

## 3. Identity 모델

| 항목 | 결정 |
|---|---|
| `anonymousId` | SDK가 생성한 UUID v4. `ANDROID_ID`와 AAID는 쓰지 않음 (Q7) |
| 생성 시점 스냅샷 | 이벤트는 호출 순서상 자신보다 앞선 모든 Identity 변경이 적용된 `userId`와 `anonymousId`를 가진다 (Q8, R-1) |
| 불변성 | 이미 저장된 이벤트의 식별자는 이후 `reset()`이나 `identify()`로 바뀌지 않는다 (Q8) |
| 저장 위치 | 같은 SQLite DB의 단일 행 `identity` 테이블 (Q16) |
| 원자성 | Identity 변경과 identify 메시지는 **같은 트랜잭션**으로 commit된다 (R-1) |
| 소유 | Identity는 storage coroutine만 읽고 쓴다. commit이 성공했을 때만 메모리의 `current`가 바뀐다 (R-1, R-7'') |
| 서버 연결 계약 | identify 메시지를 중심으로 연결한다. 서버는 identify를 `(anonymousId, userId)` 쌍에 대해 멱등하게 처리한다 (R-10'') |

---

## 4. 아키텍처

### 4.1 전체 구조

```
호출 스레드 (main 등)                         storage coroutine
─────────────────────                        (Dispatchers.IO.limitedParallelism(1))
명령 생성 (lock 밖)                          DB와 Identity를 독점 소유
  messageId, timestamp, properties 복사,     ─────────────────────────────────
  reset용 새 anonymousId                     시작: DB 열기, identity 로드
[lock] pending.addLast(command)               loop:
       메모리 상한 적용                         storageSignal 대기
storageSignal.trySend(Unit) ──────────────►     큐 앞의 최대 K개를 peek
                                                트랜잭션 { 명령 순서대로 적용,
                                                  events INSERT, identity UPSERT,
                                                  상한 정리 }
                                                성공: 큐에서 제거, current 갱신,
                                                     콜백 post(main)
                                                실패: 재시도 또는 비활성화 (§8)
                                                저장 행 수 ≥ flushAt → uploadSignal
                                                      │
ticker(flushInterval), flush() ───────────────► uploadSignal (conflated)
                                                      ▼
                                              Uploader coroutine (single in-flight)
                                                nextAttemptAt, backoff 단계,
                                                batchLimit, isolateThroughId,
                                                rejectionDeletes
                                                readBatch / deleteByIds → storage
                                                HttpURLConnection → endpoint
```

동시성 장치는 **lock 1개, coroutine 2개(storage, Uploader), conflated 신호 채널 2개**입니다.

### 4.2 호출 스레드와 lock (Q9, Q10, Q13, Q20, R-1)
- lock 밖에서는 명령에 필요한 값을 **호출 시점에** 확정합니다.
  - `messageId`(UUID v4)
  - `timestamp`(`System.currentTimeMillis()`)
  - `properties` 깊은 복사 (R-9')
  - `reset`이 쓸 새 `anonymousId`
- lock 안에서는 `ArrayDeque`에 명령을 넣고 메모리 상한을 적용하는 일만 합니다. **I/O는 하지 않습니다.**
- lock을 해제한 뒤 conflated `Channel<Unit>`에 `trySend`합니다. 이 호출은 항상 즉시 반환합니다.
- `initialize()` 후 DB를 읽기 전에 들어온 명령도 같은 큐에서 기다립니다. 별도의 버퍼는 없습니다.

### 4.3 storage coroutine (R-1, R-5, R-7'')
- 모든 DB 접근(삽입, 상한 정리, 배치 읽기, 전송분 삭제)을 **독점**합니다. Uploader는 `withContext(storageDispatcher)`로 요청합니다.
- 명령 적용 규칙
  - `Track`/`Screen`: 그 시점의 `next` Identity를 스냅샷해서 직렬화하고 events에 INSERT
  - `Identify`: `next = next.copy(userId = ...)`, identify 메시지 INSERT
  - `Reset`: `next = Identity(userId = null, anonymousId = 명령에 담긴 새 값)`
  - 트랜잭션 끝에서 Identity가 바뀌었으면 identity 행을 UPSERT
- 한 트랜잭션은 최대 K개(예: 50) 명령을 처리합니다. 트랜잭션이 길어져서 유실 창이 넓어지는 것을 막기 위해서입니다.
- `SimpleDateFormat`(UTC)은 **트랜잭션 하나당** 새로 만들어 그 안에서만 씁니다 (R-11).

### 4.4 Uploader coroutine (Q22, Q23, R-5)
- coroutine 하나로 동작하므로 그 자체로 single in-flight입니다.
- trigger는 세 가지입니다: 저장 행 수 ≥ `flushAt`(기본 20), `flushInterval`(기본 30초) 주기의 확인, 수동 `flush()`. 모두 conflated `uploadSignal`로 모입니다.
- `now < nextAttemptAt`이면 그때까지 기다립니다. 기다리는 동안 들어온 신호는 하나로 합쳐지므로 중복 요청이 생기지 않습니다.
- 앱을 다시 시작하면 SQLite에 남아 있는 이벤트도 전송 대상입니다. backoff 상태는 메모리에만 두므로 재시작하면 바로 시도합니다.
- `sentAt`용 `SimpleDateFormat`은 **요청 하나당** 새로 만듭니다 (R-11).
- **dispatcher 분리 (5단계)**: Uploader의 DB 작업(`readBatch`, `deleteByIds`, `counts`)은 storage dispatcher에서, HTTP 요청은 network dispatcher(`Dispatchers.IO`)에서 합니다. 그래서 네트워크 요청이 오래 걸려도 storage coroutine, 즉 `track()`의 저장은 막히지 않습니다.
- **시작 시 1회 확인**: Uploader가 시작되면 trigger를 한 번 보내서, 이전 실행에서 남은 이벤트를 바로 전송합니다.
- **배치를 보낸 뒤**: 남은 행이 `flushAt` 이상이면 바로 다음 배치를 보내고, 그보다 적으면 다음 trigger(주기 확인, `flush()`, 개수)를 기다립니다.
- 비활성화되면(§8.3) Uploader와 ticker를 멈춥니다. 디스크의 데이터는 그대로 남습니다.

---

## 5. 저장소

### 5.1 구현 (Q12)
- `SQLiteOpenHelper`를 직접 쓰고, `internal interface EventStore` 뒤에 숨깁니다. 테스트에서는 in-memory 구현이나 오류를 주입하는 구현으로 바꿔 씁니다.
- journal 모드는 기본값(rollback journal)을 유지합니다. commit이 반환된 뒤에는 프로세스가 강제 종료돼도 데이터가 보존됩니다 (R-4).

### 5.2 스키마 (개략)
| 테이블 | 컬럼 |
|---|---|
| `events` | `id INTEGER PRIMARY KEY AUTOINCREMENT`, `message_id TEXT UNIQUE NOT NULL`, `type TEXT NOT NULL`, `payload TEXT NOT NULL`(직렬화된 메시지 JSON), `size INTEGER NOT NULL` |
| `identity` | 단일 행: `anonymous_id`, `user_id`, `version`(명령마다 1씩 증가, **디버깅용**, R-1b) |

### 5.3 순서 (R-3)
- 순서의 기준은 `events.id`(`AUTOINCREMENT`)입니다. lock 순서와 큐 순서, INSERT 순서, rowid가 모두 같습니다.
- `AUTOINCREMENT`는 rowid를 재사용하지 않습니다. 그래서 메모리에 기억한 rowid 경계(§6.4)가 새 행과 겹치지 않습니다.

### 5.4 상한과 공간 부족 (Q14, R-4')
| 구분 | 정책 |
|---|---|
| 정상 전송 후 | identify를 포함한 모든 행은 2xx를 받으면 삭제 |
| 메모리 큐 상한 | Track/Screen 명령 1,000개. 넘으면 가장 오래된 Track/Screen부터 버림 (`droppedInMemory`). Identify/Reset 명령은 버리지 않음 |
| `maxQueuedEvents` (기본 1,000, 설정 가능) | `track`/`screen` 행에만 적용. 넘으면 가장 오래된 행부터 같은 트랜잭션에서 삭제 (`evictedFromStore`) |
| `maxQueuedIdentifies` (100, 내부 상수) | `identify` 행에만 적용. 넘으면 가장 오래된 행부터 삭제 (`evictedIdentify`) |
| 이벤트 크기 | 직렬화 결과가 32KB를 넘으면 그 이벤트를 버림 (`droppedOversized`) |
| 디스크 부족 (`SQLiteFullException`) | 첫 재시도 전에 한 번, 가장 오래된 `track`/`screen` 행을 일정 비율(예: 25%) 삭제해서 공간을 확보. 그 뒤로는 §8의 재시도 경로 |

- 개수 상한은 SQLite **파일 크기의 엄격한 상한이 아닙니다.**
- 저장 행 수의 상한은 항상 존재합니다 (최대 1,100행).

---

## 6. 전송

### 6.1 보장 수준 (Q13)
- **at-least-once** 전송입니다. 재전송할 때도 같은 `messageId`를 씁니다. 중복 제거는 서버가 `messageId`로 합니다.
- 보장의 예외는 §11에 정리합니다.

### 6.2 배치 (Q22)
- 배치당 최대 100개, 512KB까지 담습니다. `events.id` 순서로 맨 앞부터 읽습니다.
- 서버가 처리 완료를 확인한 이벤트만 삭제합니다.

### 6.3 응답 분류와 재시도 (Q23, R-5)
| 결과 | 처리 |
|---|---|
| `2xx` | 배치 전체 삭제. `rejected` 목록은 `rejectedByServer`와 로그에 기록. 배치 **전부가** `rejected`면 `Log.e` (동작은 같음). backoff와 `batchLimit` 초기화 |
| `IOException`, 타임아웃, `5xx`, `408`, `429` | 유지. 지수 backoff + full jitter (1초에서 시작, 최대 5분). `Retry-After`는 상한 안에서 우선 |
| `413` | 유지. `batchLimit`를 절반으로 줄임. 1건짜리도 `413`이면 그 이벤트 삭제 (`rejectedByServer`) |
| `401`, `403` | 유지. 최대 backoff. `Log.e` |
| `400`, `422` | `trustInvalidEventResponses = false`(기본): 격리·삭제 없이 유지 + 최대 backoff + `Log.e`. `true`: 단건 격리 (§6.4) |
| 그 밖의 응답 (1xx, 3xx, 404·405·409 등 그 밖의 4xx) | 유지 + 최대 backoff + `Log.e`. 아무것도 삭제하지 않음 (5단계 D5-1 승인) |

- 리다이렉트(3xx)는 따라가지 않습니다. POST 본문을 다른 곳으로 다시 보내지 않기 위해서입니다.
- 전송 중 `IOException`이 아닌 예외(예: INTERNET 권한이 없어서 나는 `SecurityException`)도 일시적 실패로 다룹니다. 데이터를 지우지 않습니다.
- 2xx를 받은 뒤 **로컬 삭제가 실패하면**, 그 배치는 다음 시도에서 같은 `messageId`로 다시 전송됩니다. 곧바로 다시 보내지 않도록 backoff합니다.
- **실패 로그 억제 (5단계 승인 조건 1)**: 같은 종류의 실패(예: `503`, `404`, `401`, 근거 없는 400)가 이어지면, **처음 한 번과 그 뒤로는 1시간마다 한 번**만 로그를 남깁니다. 그사이 생략한 횟수는 다음 로그에 함께 적습니다. 실패 종류별로 따로 셉니다. 전송이 다시 성공하면 "N번 실패 후 성공"을 한 번 남기고 기록을 초기화합니다. 데이터 처리(보존, backoff)에는 영향이 없습니다.
- 일시적 실패는 이벤트별 재시도 횟수 제한 없이 재시도합니다.
- backoff 중에는 자동 trigger와 수동 `flush()` 모두 시도 시각을 앞당기지 않습니다.
- 전송 중에 상한 정리가 그 배치의 행을 지웠다면, 2xx 이후의 `deleteByIds`가 없는 행을 무시합니다. 모순이 없습니다.

### 6.4 단건 격리 (Q24, R-8'', 7단계 P7-1·P7-2b)
- **적용 조건 (P7-1)**: 호스트 앱이 `Configuration.Builder.trustInvalidEventResponses(true)`를 설정한 경우에만 격리와 `invalid_event` 기반 삭제를 합니다. **기본값 false**에서는 400/422를 "근거 없는 400"과 같이 처리합니다: 아무것도 삭제하지 않고, 격리하지 않고, 최대 backoff로 기다립니다. 서버 계약을 확인하지 않은 운영 서버에서 삭제 경로를 열지 않기 위해서입니다.
- **격리 1회당 요청 상한 (P7-2b, v1 초기 정책)**: 격리 한 번에 보내는 단건 요청은 **최대 20번**입니다. 429/5xx 같은 일시적 실패의 재시도도 포함합니다. 상한에 도달하면 격리를 끝내고 남은 이벤트를 **보존**한 채 최대 backoff(5분)로 기다립니다. 다음 시도에서 다시 배치로 보내고, 400이면 새 격리 회차가 시작됩니다. 일시적 실패의 backoff(지수 backoff, `Retry-After`)는 상한에 도달하기 전까지 그대로 적용됩니다.
- **한계**: 상한은 **기기 하나**의 요청 수를 5분에 최대 21번(배치 1 + 단건 20)으로 제한할 뿐입니다. 서버 배포 버그로 많은 기기가 동시에 400을 받으면 전체 서버 부하는 여전히 늘어납니다. 상한은 이를 **완전히 막지 못합니다.**
- 아래는 `trustInvalidEventResponses = true`일 때의 동작입니다.

- 배치가 `400`/`422`를 받으면 `isolateThroughId = 그 배치의 마지막 id`로 두고, `id ≤ isolateThroughId`인 동안 한 건씩 보냅니다.
- 단건 응답 처리:
  | 단건 응답 | 처리 |
  |---|---|
  | `2xx` | 삭제 |
  | `400`/`422` + 본문 `error.code == "invalid_event"` + `error.messageId`가 요청과 일치 + 이번 프로세스 삭제 건수 < 20 | 삭제, `rejectedByServer`, `rejectionDeletes += 1` |
  | `400`/`422` + 위 조건 불충족 (코드 없음, 다른 코드, `messageId` 불일치, HTML 등) | **서버 문제로 판단**: 삭제하지 않음, 격리 종료, `Log.e`, 최대 backoff |
  | `400`/`422` + `invalid_event`이지만 삭제 건수가 20에 도달 | **삭제하지 않고 보류**. 서버 문제와 같이 처리 (보존, 최대 backoff, `Log.e`) |
  | `5xx` / 타임아웃 / `429` / `401` / `403` | 격리 상태 유지, 해당 backoff |
- 격리 상태는 메모리에만 둡니다. 재시작하면 다시 배치로 보내 보고, 400이면 다시 격리합니다. 이미 전달되어 삭제된 이벤트는 다시 보내지 않습니다.
- **요청 수 상한 (6단계)**: 재귀 없이 같은 전송 루프에서 한 건씩 처리합니다.
  - 격리 한 번에 보내는 단건 요청은 원래 배치 크기(최대 100)를 넘지 않습니다.
  - 근거 없는 400이면 첫 단건에서 멈추고 최대 backoff합니다. 그래서 계약이 없는 서버가 모든 요청에 400을 주면 5분마다 "배치 1번 + 단건 1번"만 보냅니다(시간당 약 24번).
- **정상 이벤트 보존**: 정상 이벤트와 잘못된 이벤트가 섞인 배치에서는 정상 이벤트가 한 건씩 전달되고, 근거가 있는 이벤트만 삭제됩니다.
- **보존되는 경우**: 아래 경우에는 이벤트를 하나도 지우지 않습니다.
  - 배치에 대한 400/422 자체 (항상 근거가 되지 않음)
  - 요청 형식이나 계약이 맞지 않는 응답 (코드 없음, 다른 코드, `messageId` 불일치, HTML)
  - 인증 오류(401/403), 일시적 실패
  - 프로세스당 20건 상한에 도달한 뒤
- **한계 (문서화)**: 거부가 보류되면 그 이벤트가 큐 맨 앞에 남아 **그 뒤의 전송이 멈출 수 있습니다.** 프로세스를 다시 시작하면 삭제 건수 카운터가 초기화되어 다시 처리될 수 있고, 그렇지 않으면 §5.4의 상한 정리로만 해소됩니다.

### 6.5 서버 계약 (Q24, R-8''a, R-10'')
이 계약은 일반 HTTP의 보장이 아니라 **SDK와 수신 서버 사이의 명시적 계약**입니다. Mock Server도 똑같이 구현합니다.
1. `2xx`는 배치의 모든 이벤트가 처리 완료(저장, 중복, 영구 거부 중 하나)됐다는 뜻이다. 부분 거부는 본문의 선택적 `{"rejected":[{"messageId","reason"}]}`로 표현한다.
2. 일부만 저장했다면 `5xx`로 응답한다. 클라이언트는 전체를 재전송하고 서버는 `messageId`로 중복을 제거한다.
3. `400`/`422`는 요청 전체를 해석할 수 없을 때 쓴다. **단건 요청에서 이벤트 자체가 문제라면** 본문에 `{"error":{"code":"invalid_event","messageId":"..."}}`를 담는다.
4. `messageId`로 중복을 제거한다 (재전송 중복).
5. identify는 `(anonymousId, userId)` 쌍에 대해 **멱등하게** 처리한다 (반복 호출로 생긴, `messageId`가 서로 다른 identify).

**서버가 일부 이벤트만 저장하는 경우의 응답 계약 (6단계)**
| 서버 상황 | 서버가 보내야 하는 응답 | SDK 동작 | 서버가 해야 하는 일 |
|---|---|---|---|
| 일부 이벤트가 **영구적으로 잘못됨**, 나머지는 저장함 | `2xx` + `rejected`에 잘못된 이벤트 목록 (계약 1) | 배치 전체 삭제, `rejectedByServer` 집계 | 저장한 이벤트는 그대로, 거부한 이벤트는 저장하지 않음 |
| **일부만 저장한 뒤** 장애가 남 (DB 오류 등) | `5xx`, 또는 응답 없이 연결 종료 (계약 2) | 배치 **전체**를 같은 `messageId`로 재전송 | 이미 저장한 이벤트를 `messageId`로 중복 제거 (계약 4) |
| 요청 자체를 해석할 수 없음 | `400`/`422` (코드 없이, 또는 `invalid_event`가 아닌 코드) | 격리 → 근거가 없으면 보존 + 최대 backoff | 원인 수정 |
| 한 건짜리 요청의 이벤트가 영구적으로 잘못됨 | `400`/`422` + `{"error":{"code":"invalid_event","messageId":"<그 id>"}}` (계약 3) | 그 이벤트만 삭제 (프로세스당 20건까지) | — |

- **계약 위반은 SDK가 감지할 수 없습니다.** 예를 들어 서버가 일부만 저장해 놓고 `rejected` 없이 `2xx`를 돌려주면, 저장되지 않은 이벤트는 **조용히 유실**됩니다. SDK는 `2xx`를 "배치 전체 처리 완료"로 믿기 때문입니다.
- "일부만 저장 + 5xx" 경우에서 서버가 `messageId` 중복 제거를 하지 않으면, 이미 저장한 이벤트가 **중복 저장**됩니다 (아래 표).
- 이 계약들은 테스트용 `ContractMockServer`(`src/test/.../mockserver/`)로 구현하고, SDK 전체 경로로 검증합니다 (응답 유실, 일부 저장 + 503, `rejected`, 배치 400 격리, 계약 없는 400, 인증 오류, 중복 제거하지 않는 서버).

**서버 멱등성에 대한 SDK의 전제와 중복 수집 가능성 (5단계 검증 항목 6)**
- SDK는 **같은 이벤트를 항상 같은 `messageId`로** 보냅니다. `messageId`는 호출 시점에 정해져 DB에 저장되고, 재전송할 때 바뀌지 않습니다.
- SDK는 중복을 스스로 막을 수 없습니다. 아래 경우에 **같은 `messageId`가 두 번 이상 도착할 수 있고**, 이를 하나로 합치는 것은 **서버의 책임**입니다 (계약 4).
  | 경우 | 이유 |
  |---|---|
  | 서버는 처리했지만 응답이 유실됨 (타임아웃, 연결 끊김) | SDK는 실패로 판단하고 재전송 |
  | 2xx를 받은 뒤 로컬 삭제 전에 프로세스가 종료됨 | 다음 실행에서 재전송 |
  | 2xx를 받은 뒤 로컬 삭제가 실패함 | backoff 후 재전송 |
  | 서버가 일부만 저장하고 `5xx`를 응답함 | 계약 2에 따라 배치 전체를 재전송 |
- **서버가 `messageId` 중복 제거를 지원하지 않으면**, 위 경우마다 **같은 이벤트가 중복 수집됩니다.** 그 빈도는 네트워크 품질과 프로세스 종료 빈도에 따라 달라지며, SDK는 이를 줄이거나 보정할 수 없습니다. 이 경우 분석 결과(이벤트 수, 퍼널 등)가 실제보다 커질 수 있습니다.
- 이와 별개로, 호스트가 `identify(X)`를 여러 번 호출하면 **서로 다른 `messageId`를 가진 identify 메시지가 여러 개** 생깁니다. 이것은 재전송 중복이 아니므로 `messageId`로는 제거되지 않으며, 계약 5(identify 멱등 처리)로 다룹니다.
- 이 저장소의 Mock Server(6단계)는 계약 4와 5를 구현해서 위 시나리오를 테스트합니다. **실제 운영 서버가 이 계약을 지키는지는 SDK가 검증할 수 없습니다** (A-1).

---

## 7. 전송 형식

### 7.1 HTTP (Q25)
- 직렬화는 `org.json`, HTTP는 `HttpURLConnection`(연결 15초, 읽기 15초)입니다. gzip은 쓰지 않습니다.
- `POST {endpoint}`. HTTPS만 허용합니다. 예외는 **테스트 환경에서 명시적으로 허용한 경우**에만 적용합니다.
- 헤더: `Content-Type: application/json`, `Authorization: Bearer {apiKey}`, `User-Agent: mini-analytics-android/{version}`
- `apiKey`는 앱에 포함되는 **공개 식별자이며 비밀 인증 수단이 아닙니다.**

### 7.2 본문
```json
{
  "sentAt": "2026-11-04T10:00:00.123Z",
  "context": {
    "library": { "name": "mini-analytics-android", "version": "0.1.0" },
    "os": { "name": "Android", "version": "15" },
    "app": { "version": "1.2.3", "build": "45" }
  },
  "batch": [
    { "messageId": "…", "type": "track", "event": "Button Clicked",
      "properties": {}, "userId": null, "anonymousId": "…",
      "timestamp": "2026-11-04T09:59:58.001Z" },
    { "messageId": "…", "type": "screen", "name": "Home", "properties": {}, "…": "…" },
    { "messageId": "…", "type": "identify", "userId": "B", "anonymousId": "…", "timestamp": "…" }
  ]
}
```
- `timestamp`는 발생 시각(호출 시점), `sentAt`은 전송 시각이며 둘 다 UTC ISO-8601 밀리초 형식입니다. `sentAt`으로 전송 시점의 시계 오차를 **추정**할 수는 있지만, 정확한 발생 시각의 복원은 **보장하지 않습니다.**
- `context`에는 최소한의 정보만 담습니다. 기기 모델, 광고 ID, 위치는 넣지 않습니다.
- 본문에 `sequence`나 rowid는 넣지 않습니다 (R-3).

### 7.3 `properties` 검증과 복사 (Q25, R-9')
- 호출 스레드(lock 밖)에서 실행하며 예외를 던지지 않습니다. 값을 잘라내지 않고 **그대로 담거나 빼기만** 합니다.

| 입력 | 처리 |
|---|---|
| `String`, `Boolean`, `Int`, `Long`, `Double`, `null` | 그대로 |
| `Float` → `Double`, `Byte`/`Short` → `Int` | 변환 |
| `NaN`, `±Infinity` | 그 항목 제외 |
| `Map<*, *>` | key가 `String`인 항목만 재귀 복사 |
| `List<*>` | 재귀 복사 |
| 그 밖의 타입 (`Array`, `Set`, `Char`, `BigDecimal`, `enum`, `Date` 등) | 그 항목 제외 |

| 제한 | 값 | 넘었을 때 |
|---|---|---|
| 중첩 깊이 | 5 | 그 컨테이너 제외 |
| key 길이 | 1~256 (UTF-16 code unit) | 그 항목 제외 |
| 문자열 값 길이 | 8,192 (UTF-16 code unit) | 그 항목 제외 |
| 순환 참조 | 현재 경로를 identity 기준으로 추적 | 그 항목 제외 |
| 전체 노드 수 | 500 (모든 map entry와 list 원소. key가 유효하지 않아 제외되는 entry도 포함. 깊이 초과로 제외된 컨테이너의 내부는 순회하지 않음) | properties 전체를 비우고 이벤트는 보냄 |
| 순회 중 예외 (`ConcurrentModificationException` 등) | — | properties 전체를 비우고 이벤트는 보냄 |

- 위반 내용은 이벤트당 요약 경고 한 줄로 남깁니다(빠진 key 경로 최대 5개). 카운터는 `propertiesSanitized`와 `propertiesDropped`입니다.
- 제한값은 **실측으로 확정한 값이 아니라 v1 초기 정책**입니다. 구현 단계에서 측정한 뒤 조정할 수 있습니다.
- String이 아닌 key는 로그 경로를 만들기 위해 호스트 객체의 `toString()`을 호출합니다. 여기서 예외가 나면 다른 순회 예외와 같이 properties 전체를 비웁니다.

---

## 8. 오류 처리 (R-7'')

### 8.1 원칙
- SDK 내부의 어떤 예외도 호스트 앱으로 전파하지 않습니다.
- 오류 경로는 세 갈래입니다.
  - **저장소**: 재시도한 뒤 비활성화
  - **전송**: §6.3의 응답 분류
  - **데이터**: 그 항목이나 그 이벤트만 제외

### 8.2 저장소 오류: 멈추고 재시도하고, 실패하면 비활성화
- 트랜잭션이 실패하면 롤백합니다. 명령은 **큐 맨 앞에 그대로** 두고, `current` Identity도 바꾸지 않습니다.
- 같은 명령을 다시 시도합니다. 재시도 대기 시간은 1초, 5초입니다(첫 시도 포함 3회).
- 이 대기 시간은 **재시도 사이의 대기만** 뜻합니다. SQLite 작업 자체에 걸리는 시간은 포함하지 않으므로, 전체 지연의 상한이 아닙니다.
- 재시도하는 동안 새 명령은 큐 뒤에 쌓입니다(§5.4 메모리 상한 적용). Identity 변경이 commit되기 전에는 그 뒤의 이벤트가 기록되지 않으므로, **영속화되지 않은 Identity가 이벤트에 붙는 일은 없습니다.**

| 오류 | 처리 |
|---|---|
| DB 열기 / 스키마 / identity 로드 실패 | 같은 재시도 → 비활성화 |
| `SQLiteFullException` | 첫 재시도 전에 공간 확보 1회 (§5.4) → 같은 경로 |
| 손상 (`SQLiteDatabaseCorruptException`) | 기본 `DatabaseErrorHandler`가 파일을 다시 만듦. 저장된 이벤트와 Identity가 사라지고 새 `anonymousId`로 시작 |
| 직렬화 실패 / 32KB 초과 | 그 이벤트만 제외 (`droppedInvalid` / `droppedOversized`), 트랜잭션은 계속 |
| 루프 안의 예상하지 못한 예외 | 저장소 실패와 같은 경로 |
| `readBatch` 실패 (Uploader) | 일시적 실패처럼 backoff |
| `deleteByIds` 실패 (2xx 이후) | 행이 남아 다시 전송됨 → 서버가 중복 제거 |
| 콜백 안의 예외 (호스트 코드) | 잡아서 `Log.e` |

### 8.3 비활성 상태
**진입할 때**
- 큐에서 기다리던 모든 명령을 버립니다(`droppedStorageError`).
- 기다리던 `identify`/`reset` 콜백에는 `Failure(STORAGE_ERROR)`를 전달합니다(메인 스레드, 한 번).
- Uploader와 ticker를 멈춥니다. `Log.e`를 한 번 남깁니다.

**진입한 뒤 들어오는 호출**
| 호출 | 동작 |
|---|---|
| `track`, `screen`, `flush` | 아무것도 하지 않음 (`droppedStorageError` 증가, 로그 없음) |
| `identify`, `reset` | 상태를 바꾸지 않음. 콜백이 있으면 즉시 `Failure(STORAGE_ERROR)`를 메인 스레드로 post |
| `initialize` | `ALREADY_INITIALIZED`. **다시 활성화하지 않음** |

- 다시 활성화되는 것은 **다음 프로세스 시작** 때 `initialize()`를 호출할 때뿐입니다.
- 비활성 상태에서는 어떤 이벤트도 기록되지 않으므로, 같은 프로세스 안에서 식별자가 섞이지 않습니다.

### 8.4 처리되지 않은 실패와 `Error` (3단계 Q-4b)
**구분**
| 실패 | 처리 경로 |
|---|---|
| storage 작업 안의 `Exception` (SQLite 오류 등) | 기존 §8.2 경로: 재시도 후 비활성화 |
| 재시도 경계 밖으로 새어 나온 `Exception` (SDK 버그 등) | 아래 "마지막 방어선"으로 비활성화. **호스트로 전파하지 않음** |
| `Error` (`OutOfMemoryError`, `StackOverflowError` 등) | 마지막 방어선으로 비활성화를 **시도**한 뒤, **삼키지 않고** 스레드의 uncaught exception handler로 넘김 (Android에서는 보통 프로세스 종료). 코루틴을 쓰지 않았을 때와 같은 동작 |
| 호출 스레드(공개 API 안)에서 난 `Exception` | 잡아서 로그를 남기고 호출을 무시함 (공개 API는 예외를 던지지 않음) |
| 호출 스레드에서 난 `Error` | 잡지 않음. 호스트의 호출부로 그대로 전파됨 |

**마지막 방어선**
- storage coroutine의 최상위 `try/catch`와 `CoroutineExceptionHandler` 두 곳에서 처리하며, 여러 번 불려도 **한 번만** 동작합니다.
- 비활성 상태 전환은 **새 coroutine을 띄우지 않고 실패가 난 스레드에서 동기적으로** 합니다. 가장 먼저 lock 안에서 `disabled` 표시를 하고 큐를 비우므로, 이후 명령은 **큐에 쌓이지 않습니다** (3단계 테스트로 검증: 비정상 종료 뒤 호출 5,000번 → 큐가 비어 있음).
- 그다음 대기 중인 Identity 콜백에 `Failure(STORAGE_ERROR)`를 post하고 `Log.e`를 남깁니다. 이 단계에서 다시 실패하더라도, 최소한 `disabled` 표시는 남기도록 합니다.

**실제 예외 전달 경로 (4단계 C-1 검토, kotlinx-coroutines 1.10.2 소스 기준)**
```
storage coroutine 안에서 Throwable 발생 (재시도 경계 밖)
 ├─ CancellationException → 그대로 전파 (정상 취소, 처리 없음)
 └─ 그 밖의 Throwable
     1) 최상위 catch: failUnexpectedly() → 비활성화 (한 번만) → 다시 throw
     2) coroutine이 실패로 끝남. 부모가 SupervisorJob이므로 자식이 직접 처리
        → StandaloneCoroutine.handleJobException → handleCoroutineException(context, e)
     3) context에 CoroutineExceptionHandler가 있으므로 그 handler만 호출하고 끝남
        (이 경우 coroutines는 전역 handler나 스레드의 uncaught handler를 호출하지 않음)
     4) 우리 handler: failUnexpectedly() (이미 처리되어 아무것도 안 함)
        ├─ Exception → 여기서 끝. 호스트로 전파하지 않음 (프로세스 종료 없음)
        └─ Error     → Thread.currentThread().uncaughtExceptionHandler로 한 번 넘김
                        (Dispatchers.IO 스레드 → ThreadGroup → 기본 handler. Android에서는 보통 프로세스 종료)
```
- **중복 전파 없음**: 3)에서 coroutines가 스스로 전파하지 않으므로, 4)의 명시적 전달이 **유일한** 전파 경로입니다. 명시적 전달을 지우면 `Error`가 조용히 사라지고, 두 번 하면 두 번 전달됩니다. 두 경우 모두 테스트가 실패하도록 고정했습니다 (`C-1 an Error reaches the thread uncaught handler exactly once`).
- **불필요한 프로세스 종료 없음**: `Exception`은 uncaught handler로 가지 않습니다 (`C-1 an escaping Exception never reaches the thread uncaught handler`).
- **전제**: storage coroutine의 부모가 `SupervisorJob`이어야 합니다 (`AnalyticsClient.create`). 일반 `Job`이라면 예외가 부모로 올라가 자식의 handler가 쓰이지 않습니다. 그래도 1)의 비활성화는 일어납니다.
- 우리 handler가 다시 예외를 던지면 coroutines는 전역 handler로 넘깁니다. 같은 인스턴스를 다시 던지면 원래 예외 하나만 넘어갑니다. 이 경로는 스레드의 handler가 없을 때만 생기며, JVM에서 스레드의 handler는 항상 있습니다(최소 `ThreadGroup`).
- `kotlinx-coroutines-android`를 쓰지 않으므로, Android 전용 플랫폼 handler는 이 경로에 없습니다.

**한계 (보장하지 않는 것)**
- `OutOfMemoryError`, `StackOverflowError` 같은 VM 수준의 `Error`가 나면 **호스트 앱 프로세스가 종료될 수 있습니다** (4단계 C-1에서 승인). 또한
- `OutOfMemoryError` 같은 심각한 `Error` 상황에서는 **정상 복구도, 콜백 전달도, 로그도 보장하지 않습니다.** 메모리가 없으면 비활성화 처리 자체가 다시 실패할 수 있습니다.
- `Error`를 넘긴 뒤 호스트 앱이 계속 정상 동작한다고 가정하지 않습니다. SDK는 `Error`를 삼켜서 숨기지 않습니다.
- 호스트의 실행 환경이 uncaught exception handler를 바꿔 두었다면, `Error` 이후의 동작은 그 handler를 따릅니다.

---

## 9. 관측

| 카운터 | 의미 |
|---|---|
| `droppedInMemory` | 메모리 큐 상한 초과로 버린 Track/Screen |
| `evictedFromStore` | `maxQueuedEvents` 초과 또는 공간 확보로 지운 행 |
| `evictedIdentify` | `maxQueuedIdentifies` 초과로 지운 identify 행 |
| `droppedOversized` | 32KB 초과 |
| `droppedInvalid` | 직렬화 실패 |
| `droppedStorageError` | 비활성화로 버린 명령과 그 뒤의 호출 |
| `rejectedByServer` | 2xx `rejected`, 단건 `invalid_event`, 단건 `413` |
| `propertiesSanitized` / `propertiesDropped` | properties 일부 제외 / 전체 비움 |

- v1에서는 카운터를 **디버그 로그로만** 노출합니다. 공개 API는 없습니다. 내부 상태 객체는 테스트에서 검증합니다.

---

## 10. 빌드와 배포 (Q11, Q27)

| 항목 | 결정 |
|---|---|
| SDK 수준 | `minSdk 23`, `compileSdk 36` |
| 바이트코드 | `jvmTarget 11`, 빌드는 JDK 17 이상 |
| Kotlin | `explicitApi()`. **호스트 Kotlin 2.1 이상** (7단계 실측: 2.1.21, 2.2.21 성공 / 2.0.21 실패). 하한을 낮추는 방법은 7단계 제안 P7-3 |
| 의존성 | 런타임은 `kotlinx-coroutines-core`만, `implementation`으로 선언. `-android` 모듈은 쓰지 않음 |
| 권한 | 라이브러리 manifest에 `android.permission.INTERNET`을 선언함. 호스트 앱 manifest에 병합됨 (5단계) |
| 시각 | `java.time`과 library desugaring은 쓰지 않음. 시각은 `Long` 밀리초로 기록 |
| 버전 | AGP, Kotlin, Gradle wrapper, coroutines는 구현을 시작할 때 서로 호환되는 안정 버전으로 확정하고 `libs.versions.toml`에 고정 |
| 테스트 | JUnit, `kotlinx-coroutines-test`, Robolectric. instrumented test는 필요한 경우에만 |
| 공개 API 검증 | `checkPublicApi`(`check`에 포함): release AAR의 공개 JVM 시그니처를 `docs/api/public-api.txt`와 비교하고, 다르면 실패. 의도한 변경은 `updatePublicApi`로 갱신 (7단계 P7-4) |
| Mock Server | `MockWebServer`는 **HTTP 계약 테스트용**이며 운영 서버 구현과 구분함. 테스트 의존성으로만 씀 |
| 배포 | `maven-publish`로 로컬 Maven에 배포. 샘플 호스트 앱에서 전이 의존성이 제대로 해결되는지 검증. Maven Central은 v1 이후 |

---

## 11. 보장, 유실 조건, 한계

### 11.1 보장하는 것
- SQLite에 commit된 이벤트는 서버가 §6.5 계약에 따라 처리 완료를 알릴 때까지 같은 `messageId`로 재전송됩니다 (at-least-once).
- **순서의 기준**은 §11.6에 정의합니다. 요약하면, 저장 순서와 전송 순서는 **명령이 큐에 들어가며 lock을 얻은 순서**이고, Identity 변화와 항상 일관됩니다.
- Identity 변경과 identify 메시지는 함께 저장되거나 함께 저장되지 않습니다. 저장되지 않은 Identity가 이벤트에 붙지 않습니다.

### 11.2 보장하지 않는 것
- **서버가 받는 순서**: 재전송과 중복 때문에 달라질 수 있습니다.
- **정확한 발생 시각**: 기기 시계가 틀리면 복원할 수 없습니다.

### 11.3 이벤트가 유실될 수 있는 경우
| ID | 경우 | 관측 |
|---|---|---|
| L-1 | 호출한 뒤 commit 전에 프로세스가 종료됨 (initialize 직후 DB 로드 중인 경우 포함) | 관측 불가 |
| L-2 | 메모리 큐 상한 초과 | `droppedInMemory` |
| L-3 | 저장 상한 초과, 공간 확보 | `evictedFromStore`, `evictedIdentify` |
| L-4 | 32KB 초과, 직렬화 실패 | `droppedOversized`, `droppedInvalid` |
| L-5 | 서버의 영구 거부 | `rejectedByServer` |
| L-6 | 저장소 장애로 비활성화 | `droppedStorageError` |
| L-7 | `initialize()` 전 호출, `INVALID_CONFIGURATION` 이후 호출 | 경고 로그 |
| L-8 | DB 손상 (파일 재생성) | `Log.e` |
| L-9 | 인증 오류(`401`/`403`)나 보류된 거부(§6.4) 때문에 전송이 멈춘 동안 L-3이 발생 | L-3 카운터 |
| L-10 | 앱 삭제, 앱 데이터 삭제 | — |

### 11.4 Identity 불일치 한계 (자동 복구하지 않음)
다음 경우에는 **SDK에 저장된 Identity와 호스트 앱의 로그인 상태가 달라질 수 있고, v1은 이를 자동으로 복구하지 않습니다.**

| 경우 | 다음 실행 때 SDK | 호스트 | 영향 |
|---|---|---|---|
| `reset()` commit 전 프로세스 종료 | 이전 사용자 A | 로그아웃 (호스트가 콜백을 기다리지 않은 경우) | **로그아웃 후 익명 활동이 A로 기록됨** |
| `reset()`이 저장 실패 (비활성화) | A | 로그아웃 | 위와 같음 |
| `initialize()` 전에 `reset()` 호출 | A | 로그아웃 | 위와 같음 |
| `identify()` commit 전 프로세스 종료 | 익명 | 로그인 | 그 사용자의 이벤트가 익명으로 기록됨 |
| DB 손상 | 새 익명 | 로그인 | 위와 같음, 이전 연결 정보 유실 |

- 호스트 앱이 §12 가이드를 따르면 첫 번째 경우의 가능성이 줄어듭니다. **하지만 0이 되지는 않습니다.**
- 호스트 앱이 스스로 보정하고 싶다면, 로그아웃 상태로 시작할 때 `reset()`을 호출할 수 있습니다. 다만 `reset()`은 매번 새 `anonymousId`를 발급하므로 **익명 사용자의 연속성을 잃습니다.** 이 선택은 호스트 앱의 판단에 맡깁니다.
- 콜백은 프로세스가 강제 종료되면 호출되지 않을 수 있습니다.

### 11.5 그 밖의 한계
- 저장소 장애가 생기면 재시도하는 동안(대기 1초 + 5초, 그리고 각 SQLite 작업 시간) 처리가 멈추고 콜백이 늦어집니다.
- 보류된 거부(§6.4)나 인증 오류가 있으면 큐의 전송이 멈출 수 있습니다.
- 개수 상한은 SQLite 파일 크기의 엄격한 상한이 아닙니다.
- 멀티 프로세스에서 쓰는 경우는 지원하지 않습니다.
- **`identify`/`reset` 명령의 메모리 적재에는 상한이 없습니다.** 근거와 평가는 §11.7에 있습니다.

### 11.6 순서의 기준 (서로 다른 스레드의 동시 호출, 3단계 추가 확인 2)
1. **선형화 지점**: 각 호출의 순서는 그 명령이 **큐에 들어가며 lock을 얻은 순간**으로 정해집니다. 저장 순서(rowid), 전송 순서, Identity 적용 순서가 모두 이 순서를 따릅니다.
2. **lock을 얻기 전에 끝나는 일**은 순서에 참여하지 않습니다.
   - 이름·`userId` 검증에 실패한 호출은 큐에 들어가지 않으므로 순서에 없습니다. `identify`는 `INVALID_ARGUMENT`를 받고, 다른 호출의 Identity에 영향을 주지 않습니다.
   - `messageId`와 `timestamp`는 lock을 얻기 **전에** 정해집니다. 그래서 서로 다른 스레드의 두 호출은 **저장 순서와 `timestamp` 순서가 다를 수 있습니다** (A가 먼저 시각을 얻었지만 B가 먼저 lock을 얻은 경우). 서버는 `timestamp`를 순서의 기준으로 쓰면 안 됩니다.
3. **같은 스레드**: 앞의 호출이 반환된 뒤 다음 호출을 하므로, 프로그램 순서가 곧 저장 순서입니다.
4. **서로 다른 스레드**: 호출들이 시간상 겹치면 어느 쪽이 먼저인지 **정해지지 않습니다.** 다만 한번 정해진 순서 안에서는 다음이 항상 성립합니다.
   - 각 이벤트의 Identity는 그 순서상 앞선 모든 `identify`/`reset`을 적용한 결과입니다.
   - `reset` 이후의 이벤트에 이전 `userId`가 붙지 않고, `identify` 없이 `userId`가 바뀌지 않습니다 (3단계 실제 스레드 테스트의 불변식).
5. 호스트가 두 호출 사이의 순서를 보장받고 싶다면, **같은 스레드에서 호출**하거나 호스트 쪽에서 동기화해야 합니다.
6. **완료 콜백의 순서**: 같은 트랜잭션에 들어간 `identify`/`reset`의 콜백은 저장 순서대로 post됩니다. 메인 스레드에서는 post된 순서대로 실행됩니다.
7. **서버가 받는 순서**는 보장하지 않습니다 (§11.2).

### 11.7 `identify`/`reset` 명령의 메모리 적재 평가 (3단계 추가 확인 1)
**왜 상한이 없는가**: Identity 명령을 버리면 그 뒤 이벤트의 식별자가 틀어지므로 버리지 않기로 했습니다 (R-4', Q15). 그래서 메모리 큐의 상한(track/screen 1,000개)은 Identity 명령에 적용되지 않습니다.

**쌓일 수 있는 경우와 크기**
| 상황 | 적재 기간 | 최대 적재량 |
|---|---|---|
| 앱 시작 직후 DB 로드 중 | 수 ms ~ 수십 ms (A-2) | 그 사이의 호출 수 |
| 저장소 재시도 중 | 재시도 대기 6초 + SQLite 작업 시간. 끝나면 성공해서 비우거나, 비활성화되면서 큐를 비움 | 호출 빈도 × 약 6초 |
| 비활성 상태 | 없음 (큐에 넣지 않음) | 0 |
| 정상 상태 | 쓰기 처리량(트랜잭션당 50개)보다 빠르게 호출이 계속될 때만 증가 | 호출 빈도 − 처리 빈도 |

- **아래 크기와 호출 빈도는 모두 실측 전 가정입니다 (A-9).**
- 명령 하나의 크기(추정): `identify`는 `messageId`(36자), `userId`(최대 256자), 콜백 참조, 객체 오버헤드를 합쳐 약 **1KB 이하**, `reset`은 약 **0.3KB 이하**입니다.
- 예시: 호스트가 버그로 `identify`를 초당 10,000번 호출하고 저장소가 6초 동안 재시도 중이면 약 60,000개, 약 60MB까지 쌓일 수 있습니다. 일반적인 앱은 세션당 `identify`/`reset`을 몇 번만 호출하므로 수 KB 수준입니다.

**결론과 권장**
- **v1에서는 상한을 두지 않습니다.** 적재는 시간적으로 제한되고(재시도 창이 끝나면 비워짐), 메모리 문제가 되려면 실제 사용과 동떨어진 호출 빈도(호스트 버그)가 필요합니다.
- 상한을 두려면 넘었을 때의 동작을 새로 정해야 합니다. 그런데 선택지가 모두 대가가 큽니다.
  - Identity 명령 버리기: 식별자가 틀어지므로 R-4' 위반
  - 새 명령 거부(`Failure`): 이후 이벤트가 이전 Identity로 기록됨
  - SDK 비활성화: 데이터 전체 유실
- 7단계에서 실측(A-2: commit 처리량)을 한 뒤에도 위험이 크다고 판단되면, "상한을 넘으면 비활성화"(예: 10,000개)를 **별도 결정으로** 제안합니다.

---

## 12. 호스트 앱 통합 가이드 (R-2''b, 수정판)

1. **G-1**: `MiniAnalytics.initialize()`는 `Application.onCreate()`에서 호출합니다. 그래야 다른 호출이 초기화 전에 들어가지 않습니다.
2. **G-2**: 로그인에 성공하면 `identify(userId)`를 호출합니다. 확실한 저장이 필요하면 콜백 결과를 확인합니다.
3. **G-3**: 로그아웃할 때는 `reset { result -> ... }`를 호출하고, **결과를 받은 뒤** 호스트 세션(토큰 등)을 삭제합니다. `Failure`여도 로그아웃은 진행하되, §11.4의 한계가 적용된다는 점을 알아 둡니다.
   - **SDK는 호스트 앱의 로그아웃을 막아서는 안 됩니다.** 다음 규칙을 함께 지킵니다.
     - **G-3a**: 로그아웃의 성공 여부를 SDK 결과에 의존시키지 않습니다. `Success`든 `Failure`든 로그아웃은 항상 진행합니다.
     - **G-3b**: 콜백을 기다린다면 반드시 **타임아웃**(예: 2초)을 둡니다. 콜백은 저장소를 재시도하는 동안(대기 1초 + 5초, 그리고 각 SQLite 작업 시간) 늦어질 수 있고, 프로세스가 종료되면 아예 호출되지 않을 수 있습니다. 타임아웃이 지나면 결과를 기다리지 않고 로그아웃을 진행합니다.
     - **G-3c**: 메인 스레드에서 콜백을 **블로킹 방식으로 기다리지 않습니다** (`CountDownLatch.await`, `runBlocking` 등). 콜백도 메인 스레드로 전달되므로, 메인 스레드를 막고 기다리면 교착 상태가 됩니다.
   - SDK 쪽 보장(§2.3, §8): `reset()`은 즉시 반환하고, 예외를 던지지 않으며, `NOT_INITIALIZED`와 비활성 상태에서는 지체 없이 `Failure`를 전달합니다.
4. 사용자를 전환할 때(A에서 B로)는 `reset()` 후 `identify(B)`를 호출합니다. `reset()` 없이 `identify(B)`를 호출하면 A의 `anonymousId`가 B와 연결됩니다.
5. 콜백은 메인 스레드에서 호출됩니다. 콜백 안에서 오래 걸리는 작업은 하지 않습니다.
6. 수신 서버는 §6.5 계약을 구현해야 합니다.

---

## 13. 검증이 필요한 가정

| ID | 가정 | 검증 방법 |
|---|---|---|
| A-1 | 서버가 §6.5 계약을 지킨다 | 계약 문서화, Mock Server 계약 테스트 |
| A-2 | 호출부터 commit까지의 시간(L-1 창)이 정상 상황에서 수 ms 수준이다 | Robolectric과 기기에서 측정 |
| A-3 | 32KB / 512KB / R-9'의 제한값이 일반적인 사용에 충분하다 | 샘플 앱에서 확인 |
| A-4 | `kotlinx-coroutines-core`가 호스트 앱의 버전과 충돌 없이 해결된다 | 샘플 호스트 앱의 dependencies 확인 |
| A-5 | 지원할 수 있는 가장 낮은 호스트 Kotlin 버전 | **7단계 확인: 2.1** (2.0.21 컴파일 실패) |
| A-6 | `Retry-After` 상한을 backoff 최대값(5분)과 같게 두는 것이 적절하다 | 문서화 |
| A-7 | Robolectric SQLite가 실제 기기의 트랜잭션 의미를 충분히 재현한다 | **7단계 에뮬레이터(API 23/33/36)에서 확인**: journal 모드, 롤백, 영속성, 손상 처리, 강제 종료 후 복원. 실제 물리 기기는 미검증 |
| A-8 | 트랜잭션당 K=50, 공간 확보 비율 25%가 적절하다 | 구현 단계에서 측정 |
| A-9 | `identify`/`reset` 명령 하나가 약 1KB / 0.3KB 이하이고, 일반 앱은 세션당 몇 번만 호출한다. 그래서 상한 없이도 메모리 위험이 낮다 (§11.7) | 7단계에서 명령 크기와 commit 처리량 측정 |

---

## 14. v1에서 제외한 기능

| 기능 | 근거 |
|---|---|
| autocapture, 자동 화면 추적 | Q6, Q26 |
| session replay, feature flags, A/B 테스트, 플러그인 | Q6 |
| `group`, `alias`, identify traits | Q5 |
| 백그라운드 진입 시 자동 전송 | Q22 |
| 네트워크 상태 감지 | Q22 |
| 400 계열 bisect | Q23, Q24 |
| gzip | Q25 |
| `callbackExecutor` 설정 | Q18 |
| backoff·격리 상태 영속화 | Q23, R-8'' |
| 멀티 프로세스 | Q19 |
| WorkManager | Q11 |
| Identity getter (`getAnonymousId()` 등) | R-1 |
| `syncUser()` 등 Identity 자동 보정 API | R-2''a 미채택 |
| 같은 프로세스 안에서 비활성 상태의 자동 복구 | R-7'' |
| 카운터 공개 API | §9 |
| Maven Central 공개 배포 | Q27 |
| iOS SDK | Q2 |

---

## 15. 개선 후보 (v1에서는 하지 않음)

| ID | 내용 | 하지 않는 이유 | 기록 시점 |
|---|---|---|---|
| IC-2 | 격리 중 단건 요청 사이의 간격 (P7-2a) | v1에서 구현하지 않기로 함 (7단계 결정) | 7단계 |
| IC-3 | bisect로 잘못된 이벤트 찾기 (P7-2c) | 상태가 늘어남. v1에서 구현하지 않기로 함 | 7단계 |
| IC-4 | 호스트 Kotlin 2.0 지원 (`coreLibrariesVersion`, `apiVersion` 낮추기, P7-3) | 보류. v1은 Kotlin 2.1 이상 | 7단계 |
| IC-1 | 2xx를 받은 뒤 로컬 삭제가 실패하면, 배치를 다시 보내는 대신 **SQLite 삭제만 재시도** | 현재 정책(backoff 후 재전송 + 서버 중복 제거)으로 정확성은 유지됩니다. 삭제만 재시도하려면 "서버가 처리한 id 목록"을 메모리에 두고 다른 경로와 조율해야 해서 상태가 늘어납니다. 서버가 중복 제거를 하지 않는 경우 중복 수집을 줄이는 효과가 있으므로, 필요해지면 검토합니다 | 5단계 승인 조건 3 |

---

## 16. 대체된 결정 (변경 이력)

| 원래 결정 | 대체 | 이유 |
|---|---|---|
| Q9 `AtomicReference<Identity>`, 호출 스레드에서 스냅샷, CAS 갱신 | R-1: storage coroutine이 Identity를 독점하고 명령 순서대로 적용 | Identity와 identify 메시지의 원자성 확보. 관찰되는 식별자는 같음 |
| Q10 lock 안의 `sequence` 카운터 | R-3: `AUTOINCREMENT` rowid | 재시작 후 카운터 복원 문제 제거 |
| Q11 이벤트 전달용 `Channel` | R-1: lock으로 보호하는 `ArrayDeque` + conflated 신호 `Channel<Unit>` | Identity 명령을 버리지 않는 유실 정책 필요 |
| Q14 단일 개수 상한 | R-4': `maxQueuedEvents` + `maxQueuedIdentifies` | identify 보호와 저장 상한의 공존 |
| Q15 bounded Channel `DROP_OLDEST` + Identity용 `MutableStateFlow` | R-1, R-4': 단일 큐, Track/Screen만 버림 | 두 경로로 나뉜 데서 생기는 원자성 문제 |
| Q17 `identityVersion` 기반 "version ≤ N" 완료 | R-1: 명령과 트랜잭션이 1:1로 대응, `version`은 디버깅용 | conflation이 없어짐 |
| Q19 다른 설정으로 재초기화 시 경고 | R-6': `InitializeResult` + `Log.e`(바뀐 필드) | 반환값 계약의 명확화 |
| Q20 별도의 복원 전 명령 버퍼 | R-1: 같은 큐 | 역할 중복 제거 |
| Q24 단건 400/422면 영구 거부 | R-8'': `invalid_event` 코드가 있을 때만 삭제, 프로세스당 20건 | 서버 오류로 인한 대량 유실 방지 |
