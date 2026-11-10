# 2단계 보고서: `EventStore` (SQLite)

- 작성일: 2026-11-09
- 계획: [`v1-implementation-plan.md`](./v1-implementation-plan.md) 2단계

## 구현한 것

| 파일 | 역할 |
|---|---|
| `internal/storage/EventStore.kt` | 인터페이스와 데이터 타입 (`StoredIdentity`, `NewEvent`, `StoredEvent`, `StoreLimits`, `StoreTransaction`, `CommitResult`, `StoreCounts`) |
| `internal/storage/SqliteEventStore.kt` | `SQLiteOpenHelper` 기반 구현 |
| `internal/model/Command.kt` | DB에서 읽은 문자열을 되돌리는 `MessageType.fromWireName` 추가 |
| (test) `InMemoryEventStore.kt` | 3단계 이후 테스트용 저장소. 아래 계약 테스트로 SQLite 구현과 같은 의미인지 검증 |
| (test) `FaultyEventStore.kt` | 3단계 R-7'' 테스트용 오류·지연 주입 데코레이터 |
| (test) `EventStoreContract.kt` | 모든 `EventStore` 구현이 지켜야 하는 테스트 19개 |

### 인터페이스 (storage coroutine이 쓰는 방식 기준)
| 메서드 | 의미 | 설계 근거 |
|---|---|---|
| `loadIdentity()` | 저장된 Identity, 처음 실행이면 null | §3 |
| `commit(StoreTransaction)` | 메시지 INSERT → identity UPSERT → 상한 정리를 **하나의 트랜잭션**으로 처리. 결과로 정리된 행 수를 돌려줌 | R-1, R-4' |
| `readBatch(maxCount, maxBytes)` | id 순서로 읽음. 바이트 상한 직전에 멈추되 첫 행은 항상 포함 | §6.2 |
| `deleteByIds(ids)` | 없는 id는 무시. 500개 단위로 나눠서 실행 | §6.3 |
| `reclaimSpace(fraction)` | 가장 오래된 track/screen을 비율만큼(올림, 최소 1개) 삭제. identify는 건드리지 않음 | §5.4 |
| `counts()` | track/screen 수와 identify 수 | §4.4 (`flushAt` trigger) |

- **오류는 예외로 던집니다.** 잡아서 재시도하거나 비활성화하는 경계는 storage coroutine입니다(3단계, §8.2). 저장소가 스스로 오류를 삼키지 않으므로 R-7''의 "실패한 명령은 그대로 두고 다시 시도" 흐름과 맞습니다.
- 생성자는 디스크 I/O를 하지 않습니다. DB는 첫 메서드 호출 때 엽니다. `initialize()`가 메인 스레드에서 이 객체를 만들어도 안전합니다(테스트로 확인).

### 스키마 (v1)
```sql
CREATE TABLE events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  message_id TEXT NOT NULL UNIQUE,
  type TEXT NOT NULL,          -- 'track' | 'screen' | 'identify'
  payload TEXT NOT NULL,       -- 직렬화된 메시지 JSON
  size INTEGER NOT NULL);      -- UTF-8 바이트
CREATE INDEX events_type_id ON events (type, id);
CREATE TABLE identity (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  anonymous_id TEXT NOT NULL,
  user_id TEXT,
  version INTEGER NOT NULL);   -- 디버깅용 (R-1b)
```

## 검토가 필요한 구현상의 선택
설계 의도를 구현하면서 내린 세부 선택입니다. 새로운 정책은 아니라고 판단했지만, 확인을 부탁드립니다.

| ID | 선택 | 이유 |
|---|---|---|
| I-1 | 같은 `message_id`의 INSERT는 **무시**(`CONFLICT_IGNORE`)하고, 트랜잭션을 실패시키지 않음 | 중복 `messageId`가 생기는 유일한 현실적 경로는 "commit은 됐지만 SDK가 실패로 판단해서 같은 명령을 재시도"하는 경우입니다. 이때 UNIQUE 위반으로 트랜잭션이 실패하면 R-7''가 재시도를 반복하다가 SDK가 비활성화됩니다. 무시하면 재시도가 멱등이 됩니다. 같은 `messageId`는 같은 메시지이므로 의미상 손실도 없습니다 |
| I-2 | `setWriteAheadLoggingEnabled(false)`를 명시함 | 설계 §5.1의 "기본 journal 모드(rollback journal) 유지"를 지키기 위해서입니다. Android 9 이상에서는 앱이 지정하지 않으면 compatibility WAL이 적용될 수 있어서, "기본값"이 기기마다 다를 수 있습니다 |
| I-3 | DB에 알 수 없는 `type` 값이 있으면 `readBatch`가 예외를 던짐 | v1은 알려진 세 값만 씁니다. 이런 값이 있다면 저장소 이상이므로 저장소 오류 경로(§8)로 처리합니다 |
| I-4 | `onUpgrade`는 비어 있고, `onDowngrade`는 Android 기본 동작(예외)을 따름 | v1에는 이전 스키마가 없습니다. 더 높은 스키마 버전의 DB를 만나면 DB 열기 실패 → §8.2 비활성화 경로로 갑니다 |

## 테스트 결과
`./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest` → **BUILD SUCCESSFUL, 154개 통과 (실패 0)**

| 테스트 | 실행 방식 | 개수 |
|---|---|---|
| `SqliteEventStoreTest` | Robolectric, **SDK 23과 34** | 26 × 2 = 52 |
| `InMemoryEventStoreTest` | JUnit (같은 계약) | 19 |
| `FaultyEventStoreTest` | JUnit | 3 |
| 1단계까지의 테스트 | | 80 |

**계약 테스트 (두 구현 공통, 19개)**: 빈 저장소, 삽입 순서와 증가하는 id, 전부 지운 뒤에도 id가 증가함, 중복 `messageId` 무시(멱등 재시도), Identity 저장·교체·유지, Identity와 이벤트의 동시 commit, track/screen 상한과 identify 상한의 독립 정리, 배치 개수·바이트 경계와 첫 행 보장, 읽기는 삭제하지 않음, id 삭제(없는 id 무시, 1,200개 한 번에), 공간 확보(비율 올림, 최소 1개, identify 제외).

**SQLite 고유 테스트 (7개)**
| 테스트 | 확인 내용 |
|---|---|
| `constructorDoesNotTouchTheDisk` | 생성만으로는 DB 파일이 생기지 않음 |
| `dataSurvivesReopen` | 닫았다 다시 열어도 이벤트와 Identity가 유지됨 |
| `idsAreNotReusedAcrossReopenEvenWhenTableBecameEmpty` | `AUTOINCREMENT` 효과 (R-3, R-8''의 격리 경계 안전성) |
| `failureBeforeCommitRollsBackEventsAndIdentityTogether` | commit 직전 예외 → 삽입, Identity, 상한 정리가 **모두** 반영되지 않음. 같은 트랜잭션을 다시 시도하면 정상 반영 (R-1, R-7'') |
| `journalModeIsNotWal` | journal 모드가 WAL이 아님 (I-2) |
| `corruptedDatabaseIsRecreated` | 손상된 파일 → 새 DB로 시작, 이전 데이터 없음 (§8.2, L-8) |
| `nullUserIdRoundTrips` | 익명 Identity 저장과 복원 |

**테스트가 결함을 잡는지 확인 (mutation check)**: 코드를 일부러 망가뜨려 해당 테스트가 실패하는지 본 뒤 원래대로 되돌렸습니다.
- `setTransactionSuccessful()`을 오류 훅보다 앞으로 옮김(실패해도 commit됨) → 원자성 테스트가 SDK 23과 34 모두에서 실패
- `AUTOINCREMENT` 제거 → id 재사용 테스트 2개가 두 SDK 모두에서 실패

## 공개 API와 의존성
- 공개 패키지 클래스는 그대로입니다: `Configuration`, `Configuration$Builder`, `FailureReason`, `IdentityCallback`, `IdentityResult`(+`Success`, `Failure`), `InitializeResult`, `MiniAnalytics`. 저장소 타입은 모두 `internal/storage`에 있습니다.
- 릴리스 런타임 의존성은 그대로 `kotlin-stdlib 2.2.21`뿐입니다.

## 남은 위험
| ID | 위험 | 대응 |
|---|---|---|
| S2-1 | **프로세스 강제 종료 시의 내구성**("commit이 반환되면 보존")은 Robolectric으로 검증할 수 없음 (A-7) | 7단계 에뮬레이터 instrumented test |
| S2-2 | 손상 복구 테스트는 Robolectric의 네이티브 SQLite 기준. 실제 기기의 `DatabaseErrorHandler` 동작과 다를 수 있음 | 7단계에서 필요하면 instrumented test로 보완 |
| S2-3 | SDK 35/36에서는 SQLite 테스트를 하지 않음 (JDK 21 필요) | JDK 21 환경에서 `sdk = [23, 34, 36]`로 실행하는 별도 작업을 7단계에서 검토 |
| S2-4 | 상한 정리와 count는 매 commit마다 `COUNT(*)`를 실행함. 행 수가 최대 약 1,100개라 비용은 작다고 판단 | 3단계에서 commit 시간을 측정 (A-2) |
| S2-5 | 스키마 마이그레이션 경로가 없음 (v1 범위) | 스키마를 바꾸는 버전에서 추가 |

## 다음 단계 전에 결정이 필요한 사항
- **설계 §2.4의 미결정 항목**: `initialize(context = null, …)` 또는 `configuration = null`일 때의 반환값. 3단계에는 영향이 없고, **4단계 전에** 결정이 필요합니다.
