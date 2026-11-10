# mini-analytics-sdk v1 설계 검토

- 대상: [`v1-design.md`](./v1-design.md) (Q1–Q27)
- 작성일: 2026-11-04
- 상태: **완료**. 승인된 항목은 `v1-design.md`에 반영했습니다. 이 문서는 검토 기록으로 남깁니다.

| 항목 | 1차 검토 결과 (2026-11-04) |
|---|---|
| R-1, R-3, R-5, R-11 | **승인** (설계 문서 반영은 전체 재검토가 승인된 뒤 일괄 진행) |
| R-2, R-8 | 조건부 동의. 실패·종료 시나리오 재확인 → [2차 검토](#2차-검토) |
| R-4, R-6, R-10 | 재검토 → [2차 검토](#2차-검토) |
| R-7, R-9 | 구체안과 예외 처리 정책 제시 → [2차 검토](#2차-검토) |
| R-4', R-6', R-9' | **승인** (2차 검토) |
| R-2', R-7', R-8', R-10' | 수정 요청 → [3차 검토](#3차-검토) |
| R-2''b, R-8''a, R-10'' | **승인** (3차 검토). R-2''b의 G-2는 `syncUser` 없이 다시 작성함 |
| R-7'' | **조건부 승인**: 비활성화 시 대기 중인 콜백에 Failure 전달. 6초는 재시도 대기 시간일 뿐 전체 지연 상한이 아님. 비활성화 이후 호출의 동작을 명시 |
| R-8''b | **조건부 승인**: 코드와 `messageId`가 일치할 때만 삭제. 20건에 도달하면 보류. 큐 처리가 멈출 수 있음을 문서화 |
| R-2''a | **미채택** (`syncUser` 없음. Identity 불일치를 자동 복구하지 않는다는 한계를 문서화) |
| 통합 | 승인된 항목을 모두 `v1-design.md`에 반영함 (2026-11-04) |

검토 우선순위(사용자 지정)는 다음과 같습니다.
① identify 이벤트와 Identity 저장의 원자성
② `reset()` 직후 프로세스 종료
③ 이벤트 순서 보장
④ 비동기 버퍼 유실 가능성

이 순서대로 R-1~R-4에서 다루고, 그 밖에 누적된 검토 항목은 R-5~R-11에서 다룹니다.

---

## 요약

검토 결과 가장 큰 문제는 **Identity와 이벤트가 SQLite로 들어가는 경로가 두 개(StateFlow, Channel)로 나뉘어 있다**는 점입니다(C-1). 원자성, 순서, 콜백 의미, 복원 전 버퍼 문제(C-1, C-3, C-4, C-5)가 모두 여기서 생깁니다.

**핵심 제안(R-1)**: 모든 호출을 **하나의 순서 있는 명령 큐**에 넣고, **단일 storage coroutine**이 명령마다 Identity 상태를 적용하면서 SQLite 트랜잭션 하나로 함께 commit합니다.

이렇게 하면 다음 장치들이 필요 없어집니다. 이 장치들이 지키려던 **요구사항은 모두 유지**됩니다.

| 제거 대상 | 원래 목적 | R-1에서 그 목적을 달성하는 방법 |
|---|---|---|
| `AtomicReference<Identity>` + CAS (Q9) | 호출 시점 스냅샷, 갱신 손실 방지 | Identity를 storage coroutine 한 곳에서만 바꾸므로 경쟁 자체가 없음 |
| Identity용 `MutableStateFlow` (Q15) | 식별자 저장이 이벤트 압력 때문에 버려지지 않게 함 | 큐의 유실 정책이 Identity 명령을 **절대 버리지 않음** (R-4) |
| 복원 전 명령 버퍼 (Q20) | DB를 읽기 전 호출을 순서대로 보존 | 같은 큐에 쌓여 있다가 load가 끝나면 그대로 처리됨 (별도 구조 불필요) |
| lock 안의 `sequence` 카운터 (Q10) | 이벤트 순서 정의 | SQLite `AUTOINCREMENT` rowid (R-3) |
| `identityVersion` 기반 "version ≤ N" 완료 (Q17) | conflation 상황에서 콜백 의미 정의 | conflation이 없으므로 명령과 트랜잭션이 1:1로 대응. 콜백은 해당 명령이 포함된 트랜잭션이 commit되면 완료 |

남는 동시성 장치는 **짧은 lock 하나**(명령 큐 보호), **단일 storage coroutine**(DB와 Identity 소유), **단일 uploader coroutine**(네트워크, single in-flight), 그리고 둘을 깨우는 **conflated 신호 `Channel<Unit>`** 입니다.

---

## R-1. identify 이벤트와 Identity 저장의 원자성 (C-1, C-3, C-5 해결)

### 문제
원래 설계에서는 다음과 같습니다.
- `identify("B")`를 호출하면 lock 안에서 메모리의 Identity가 바뀝니다.
- 그 뒤 **(1)** identify 메시지는 이벤트 Channel로, **(2)** 바뀐 Identity는 StateFlow로 각각 따로 writer에 전달됩니다.

| 강제 종료 시점 | 결과 |
|---|---|
| (2)만 commit됨 | 재시작 후 Identity는 B인데, 서버는 "익명 X → B" 연결을 영원히 받지 못함 |
| (1)만 commit됨 | 서버는 X → B를 받았지만, 재시작 후 Identity가 이전 값으로 복원되어 이후 이벤트가 이전 식별자로 기록됨 |
| Channel `DROP_OLDEST`로 (1)이 버려짐 | 콜백은 Success(Identity 저장됨)인데 identify 메시지는 없음 → **Q25 보완 5의 모순** |

### 제안: 단일 명령 큐 + 명령 단위 트랜잭션

```
호출 스레드 (lock 밖)                     호출 스레드 (lock 안, I/O 없음)
──────────────────────                   ─────────────────────────────
Track/Screen: messageId, timestamp,      pending.addLast(command)
              properties 깊은 복사        in-memory 상한 적용 (R-4)
Identify:     messageId, timestamp,
              userId                  ──► (lock 해제 후) storageSignal.trySend(Unit)
Reset:        새 anonymousId(UUID)           ※ conflated 채널이라 항상 즉시 성공
```

```
storage coroutine (Dispatchers.IO.limitedParallelism(1), DB와 Identity를 독점 소유)
────────────────────────────────────────────────────────────────────────────
시작: DB 열기 → identity 행 읽기 (없으면 새 anonymousId 생성 후 저장)
loop:
  storageSignal 대기
  commands = lock { pending에서 최대 K개 꺼냄 }      // K는 예: 50
  next = current                                      // 지역 변수
  db.transaction {
    for cmd in commands:
      Track/Screen → events INSERT (payload = next 스냅샷을 담아 직렬화)
      Identify     → next = next.copy(userId)
                     events INSERT (type=identify, next 스냅샷)
      Reset        → next = Identity(userId=null, anonymousId=cmd.newAnonymousId)
    identity 행 UPSERT (next)       // 바뀐 경우에만
    저장 상한 정리 (R-4)
  }
  commit 성공 → current = next, 콜백들을 메인 스레드로 post (Success)
  개수 ≥ flushAt → uploadSignal.trySend(Unit)
```

### 원래 요구사항과의 대응
| 요구사항 | 충족 방식 |
|---|---|
| Q8 이벤트는 생성 시점의 Identity를 가짐 | 큐의 순서가 곧 호출 순서이고, storage coroutine이 순서대로 Identity를 적용하므로, 각 이벤트의 스냅샷은 "호출 시점까지의 모든 명령을 적용한 상태"와 **결정적으로 같음** |
| Q8 `reset()` 이후 기존 미전송 이벤트의 식별자 불변 | 이미 INSERT된 행의 payload는 바뀌지 않음 |
| Q9 갱신 손실 없음 | Identity를 쓰는 주체가 하나뿐 |
| Q9 메인 스레드에서 디스크 I/O 없음 | lock 안에서는 `ArrayDeque.addLast`만 함 |
| Q10 단일 순서 기준 | lock 획득 순서 = 큐 순서 = INSERT 순서 = rowid (R-3) |
| Q13 `messageId`는 lock 밖에서 생성 | 그대로 |
| Q16 같은 DB, 단일 writer | storage coroutine이 유일한 writer |
| Q17 성공 = "해당 변경의 효과를 포함하는 Identity가 영구 저장됨" | 그 명령이 포함된 트랜잭션이 commit된 뒤에만 Success |
| Q20 복원 전 호출 보존, `messageId`/`timestamp`는 호출 시점에 확정 | load가 끝날 때까지 명령이 큐에 남아 있음. 두 값은 명령 객체에 이미 들어 있음 |
| Q21 exactly-once | 명령마다 콜백 슬롯은 하나. commit 성공이나 실패 처리에서 꺼낼 때 한 번만 post |
| Q25 보완 1, 5 | identify 메시지와 identity 행이 **같은 트랜잭션**에 들어감. Success면 둘 다 저장된 것이고, 실패면 둘 다 저장되지 않은 것이므로 모순이 없음 |

### 결과적으로 바뀌는 Q9의 의미
원래 Q9는 "호출 스레드에서 스냅샷"이었고, R-1은 "storage coroutine에서 **논리적으로 같은** 스냅샷"입니다. 관찰할 수 있는 결과(각 이벤트에 붙는 식별자)는 같습니다. 다만 다음이 달라집니다.
- **호출 스레드에서 현재 Identity를 즉시 읽을 수 없습니다.** v1에는 `getAnonymousId()` 같은 공개 getter가 없으므로 문제가 되지 않습니다. 나중에 getter가 필요해지면, 그때 "commit된 Identity의 읽기 전용 미러"를 추가하는 것으로 충분합니다.

### 승인 요청
- **R-1a**: 단일 명령 큐와 명령 단위 트랜잭션 구조를 채택하고, Q9의 `AtomicReference`, Q15의 StateFlow, Q20의 별도 버퍼, Q17의 version 기반 완료 로직을 제거한다.
- **R-1b**: `identity` 테이블의 `version` 컬럼은 **디버깅용으로만** 남긴다(명령마다 1씩 증가). 콜백 판정에는 쓰지 않는다. 필요 없다고 보시면 컬럼도 제거합니다.

---

## R-2. `reset()` 직후 프로세스 종료

### R-1 적용 후의 유실 창
1. 큐에 들어간 뒤 storage coroutine이 꺼내기까지: 보통 ms 이하
2. 트랜잭션을 실행하고 commit하기까지: 보통 수 ms (A-2에서 측정)
3. **앱 시작 직후**: DB 열기와 identity 로드가 끝날 때까지 창이 더 길어질 수 있음

이 창 안에서 강제 종료되면 `reset()`이 사라지고, 재시작할 때 **이전 사용자 A의 Identity가 복원**됩니다. 클라이언트만으로는 이 창을 0으로 만들 수 없습니다. 메인 스레드에서 동기 commit을 하면 Q9 원칙을 어기게 됩니다.

### 실제 위험
문제가 되는 것은 "SDK는 A인데 호스트 앱은 로그아웃 상태"라는 **불일치**입니다. 이때 로그아웃한 사용자의 익명 활동이 A에게 기록됩니다.

### 제안: 콜백 기반 로그아웃 순서를 문서화 (새 API 없음)
호스트 앱의 로그아웃 절차를 다음 순서로 하라고 문서에 권장합니다.

```
1. MiniAnalytics.reset { result -> ... }   // SDK 저장
2. Success를 받은 뒤 호스트 앱의 세션(토큰 등) 삭제
```

| 종료 시점 | SDK | 호스트 앱 | 불일치 |
|---|---|---|---|
| 1단계 commit 전 | A | A (세션 남아 있음) | 없음. 재시작하면 A로 로그인된 상태라 일관됨 |
| 1단계 commit 후, 2단계 전 | 익명 | A | 사용자에게는 로그인 상태로 보이지만 SDK는 익명이라 **A의 데이터가 익명으로 기록됨**. 개인정보 노출 방향이 아니라 데이터 누락 방향 |
| 2단계 이후 | 익명 | 로그아웃 | 없음 |

- 이 순서를 따르면 **이전 사용자의 식별자가 다른 사용자의 활동에 붙는 경우가 없어집니다.**
- 콜백이 `Failure(STORAGE_ERROR)`이면 호스트 앱은 로그아웃을 그대로 진행하고 오류를 로그로 남기면 됩니다. 이때 SDK의 메모리 상태는 익명으로 바뀌어 있습니다(R-7).
- 콜백을 쓰지 않는 호스트 앱에는 위험이 남습니다. 이 사실을 §6 유실 조건에 그대로 둡니다.

### 다른 선택지 (권장하지 않음)
- **시작 시 정합성 보정 API**(예: `resetIfIdentified()`): 호스트 앱이 "로그아웃 상태"를 알 때 호출하는 멱등 API입니다. 효과는 있지만 공개 API가 늘어나고, 위의 순서 규칙만으로 대부분 해결됩니다. 필요해지면 하위 호환되게 추가할 수 있습니다.
- **명령 우선순위**(identity 명령을 앞당겨 처리): 큐 순서가 깨지므로 R-3와 충돌합니다. 제외합니다.

### 승인 요청
- **R-2**: 새 API 없이 "`reset` 콜백 Success 후 호스트 세션 삭제" 순서를 통합 가이드에 명시한다. 남는 위험은 유실 조건 문서에 유지한다.

---

## R-3. 이벤트 순서 보장 (C-4 해결)

### 제안
- `events.id INTEGER PRIMARY KEY AUTOINCREMENT`를 순서 기준으로 씁니다. lock 안의 `sequence` 카운터는 없앱니다.
  - `AUTOINCREMENT`가 없어도 새 rowid는 "현재 최대값 + 1"이므로 남아 있는 행들 사이의 순서는 맞습니다. 하지만 테이블이 비면 rowid가 다시 작은 값부터 시작할 수 있습니다.
  - 그러면 메모리에 기억해 둔 rowid(R-8의 격리 경계)가 새 행과 겹칠 수 있습니다. `AUTOINCREMENT`는 **한 번 쓴 값을 다시 쓰지 않으므로** 이 문제를 막습니다.
- 재시작한 뒤에도 순서를 이어 가는 문제가 SQLite 안에서 해결됩니다.

### 보장하는 순서 (문서에 명시)
1. **같은 스레드**에서 차례로 호출하면 그 순서가 곧 DB 순서이고 전송 순서입니다.
2. **서로 다른 스레드**에서 동시에 호출하면 lock 획득 순서를 따릅니다. 어느 쪽이 먼저인지는 정해지지 않지만, 한번 정해진 순서는 식별자 변화와 항상 일관됩니다.
3. identify 메시지는 그 뒤에 호출된 이벤트보다 **먼저** 저장되고 먼저 전송됩니다.
4. **서버가 받는 순서는 보장하지 않습니다.** 재전송과 중복(Q13) 때문에 같은 이벤트가 나중에 다시 도착할 수 있습니다.
5. 버려지거나 지워진 이벤트는 순서에 빈자리를 남깁니다. 순서 자체는 깨지지 않습니다.

### 검토 결과
- 413로 배치를 줄이거나, 단건 격리를 하거나, 재시도를 해도 항상 "큐의 맨 앞부터 rowid 순서로" 읽으므로 순서는 유지됩니다.
- 전송 본문에 `sequence`를 포함하지 **않습니다**. rowid는 설치 단위의 내부 값이라 서버 계약으로 만들 이유가 아직 없습니다. 서버에서 정렬이 필요하면 v1 이후에 검토합니다.

### 승인 요청
- **R-3**: `AUTOINCREMENT` rowid를 순서 기준으로 삼고, 위 1~5를 순서 보장 문서로 채택한다.

---

## R-4. 비동기 버퍼 유실 가능성 (C-2 해결)

### 유실 지점 전체 목록 (R-1 적용 후)
| ID | 지점 | 원인 | 관측 수단 |
|---|---|---|---|
| L-1 | 명령 큐 (메모리) | commit 전 프로세스 종료 | 관측 불가 (문서화) |
| L-2 | 명령 큐 (메모리) | 상한 초과 | `droppedInMemory` |
| L-3 | SQLite | 저장 상한 초과 정리 | `evictedFromStore` |
| L-4 | 직렬화 | 32KB 초과 | `droppedOversized` |
| L-5 | 서버 | `rejected`, 단건 400/422/413 | `rejectedByServer` |
| L-6 | SQLite | 쓰기 실패 (R-7) | `droppedStorageError` |
| L-7 | 호출 | `initialize()` 전 호출 | 경고 로그 |
| L-8 | 앱 | 삭제, 데이터 삭제 | 해당 없음 |

### 제안: Identity 명령과 identify 메시지는 버리지 않는다
- **메모리 큐**: 상한(기본 1,000)은 `Track`/`Screen` 명령 수로만 계산합니다. 넘으면 **가장 오래된 Track/Screen 명령**을 버립니다. `Identify`/`Reset` 명령은 절대 버리지 않습니다. 보통 큐의 맨 앞이 Track이므로 O(1)이고, 최악의 경우에도 큐를 한 번 훑는 O(n)입니다.
- **SQLite 저장 상한**: 넘으면 `type IN ('track','screen')`인 가장 오래된 행부터 지웁니다. identify 행은 지우지 않습니다.
  - 그 결과 identify 행만 남는 극단적인 경우에는 상한을 넘을 수 있습니다. R-10(같은 `userId`로의 중복 identify 무시)을 적용하면 현실적으로 identify 행은 몇 개를 넘지 않습니다. 이 점을 문서에 명시합니다.
- **유실 창(L-1) 최소화**: storage coroutine은 꺼낼 수 있는 명령을 최대 K개(예: 50)씩 하나의 트랜잭션으로 처리합니다. 트랜잭션 하나가 길어져서 창이 넓어지는 것을 막기 위해서입니다.
- **SQLite 내구성**: 기본 journal 모드(rollback journal, `synchronous=FULL`)를 유지합니다. commit이 반환되면 **프로세스가 강제 종료돼도** 데이터가 보존됩니다. WAL은 단일 연결 구조에서 얻는 이점이 작아서 v1에서는 쓰지 않습니다. 전원 차단 같은 OS 수준의 장애는 SQLite의 보장 범위를 따릅니다.

### 승인 요청
- **R-4**: 유실 지점 목록 L-1~L-8과 카운터 6개, Identity 명령과 identify 행을 보호하는 정책, K개 단위 트랜잭션, 기본 journal 모드를 채택한다.

---

## R-5. 저장소 소유권과 업로드 구조 (C-6 해결, Q23 정합성 검토)

### 제안
- **storage coroutine이 모든 DB 접근을 독점합니다.** 삽입, 상한 정리, 배치 읽기, 전송 성공분 삭제 모두 여기서 합니다. Uploader는 `withContext(storageDispatcher)`로 `readBatch(limit, maxBytes)`와 `deleteByIds(ids)`를 요청합니다.
- **Uploader는 coroutine 하나**입니다. 그 자체로 single in-flight가 됩니다.
  ```
  loop:
    uploadSignal 대기                    // 개수 trigger, 30초 ticker, flush()
    now < nextAttemptAt 이면 delay(nextAttemptAt - now)
                                         // 그 사이의 신호는 conflate → 중복 요청 없음
    batch = readBatch(...)               // 비어 있으면 continue
    응답 분류 → 삭제 / backoff 갱신 / 격리 (R-8)
    남은 이벤트가 flushAt 이상이면 다시 신호
  ```
- **Q23 정합성 확인 결과**:
  - backoff 중에 들어온 trigger는 conflated 신호 하나로 합쳐지고, `nextAttemptAt` 전에는 소비되지 않습니다. 따라서 요구사항(주기적 flush와 수동 flush 모두 backoff를 앞당기지 않음)이 구조적으로 지켜집니다.
  - 전송 중에 상한 정리가 그 배치의 행을 지울 수 있습니다. 2xx를 받으면 `deleteByIds`가 없는 행을 무시하고, 실패하면 그 행은 이미 L-3으로 기록되어 있습니다. 둘 다 모순이 없습니다.
  - 413로 줄인 배치 한도(`batchLimit`)는 다음 전송이 성공하면 최대값(100)으로 되돌립니다.
  - `401`/`403`은 최대 backoff(5분)로 대기합니다. 데이터는 L-3 상한 정리에만 영향을 받습니다.

### 승인 요청
- **R-5**: storage coroutine의 DB 독점, 단일 Uploader coroutine, conflated `uploadSignal`, `nextAttemptAt` 기반 backoff를 채택한다.

---

## R-6. 다른 설정으로 다시 초기화할 때 (Q19 검토 요청)

- **경고만으로는 부족합니다.** 호스트 앱이 잘못된 endpoint로 계속 보내는데도 알아차리지 못할 수 있습니다.
- 그렇다고 예외를 던지면 "호스트 앱을 크래시시키지 않는다"는 원칙을 어깁니다.

### 제안
- 두 번째 `initialize()`는 계속 **무시**합니다. 다만 로그 수준을 `Log.e`로 높이고, **어떤 설정 필드가 다른지**를 로그에 남깁니다. `apiKey`는 마스킹합니다.
- `initialize()`는 `Boolean`을 반환합니다. 이번 호출로 초기화가 이루어졌으면 `true`, 이미 초기화되어 있어서 무시했으면 `false`입니다. 테스트에서 이 동작을 검증할 수 있고, 호스트 앱도 필요하면 확인할 수 있습니다.
- 디버그 빌드에서만 크래시를 내는 방식은 **채택하지 않습니다.** 릴리스와 디버그의 동작이 달라지면 문제를 재현하기 어려워집니다.

### 승인 요청
- **R-6**: 무시 + `Log.e`(바뀐 필드 표시, `apiKey` 마스킹) + `Boolean` 반환을 채택한다.

---

## R-7. SQLite 오류 정책 (C-7, 미결정)

### 제안
- **모든 SQLite 예외를 SDK 경계 안에서 잡고, 호스트 앱으로 전파하지 않습니다.**
- **트랜잭션 실패**:
  - 트랜잭션은 롤백됩니다. 그 트랜잭션에 있던 Track/Screen/Identify 메시지는 **버리고** `droppedStorageError`에 기록합니다.
  - Identity 변경(`Identify`/`Reset`)은 **메모리에는 적용**합니다. 그래야 이후 이벤트가 올바른 식별자로 기록됩니다. 그리고 `identityDirty`로 표시해서 **다음 트랜잭션 때 identity 행을 다시 저장**합니다.
  - 해당 콜백은 `Failure(STORAGE_ERROR)`로 완료합니다.
  - 모순 여부: 콜백이 실패했는데 메모리는 바뀌어 있는 상태입니다. 그러나 Failure의 의미는 "영구 저장되지 않았다"이고, 메모리 상태와는 관계가 없습니다. Q17의 정의와 충돌하지 않습니다.
- **DB 열기 실패 / identity 로드 실패**:
  - 이 프로세스에서는 SDK를 **비활성(no-op)** 상태로 바꿉니다. `Log.e`를 남기고, 대기 중인 콜백은 모두 `STORAGE_ERROR`로 완료합니다.
  - 메모리에서만 동작하는 대체 모드는 만들지 않습니다. 복잡도가 크고 at-least-once 계약을 흐리게 만들기 때문입니다.
- **DB 손상**: Android의 `SQLiteOpenHelper` 기본 `DatabaseErrorHandler`는 손상된 DB 파일을 삭제하고 새로 만듭니다. 이 경우 저장된 이벤트와 `anonymousId`를 잃게 되며, 이것을 유실 조건으로 문서화합니다.

### 승인 요청
- **R-7**: 위의 트랜잭션 실패 정책, 열기 실패 시 비활성화, 기본 손상 처리기 사용을 채택한다.

---

## R-8. 단건 격리 상태 단순화 (Q24 검토 요청)

### 제안
격리 상태를 `messageId` 집합 대신 **rowid 경계값 하나**로 표현합니다.

```
isolateThroughId: Long?   // 400/422를 받은 배치의 마지막 rowid
batchLimit: Int           // 413 축소용 (기본 100)

effectiveLimit = if (isolateThroughId != null && headId <= isolateThroughId) 1 else batchLimit
headId > isolateThroughId 가 되면 isolateThroughId = null
```

- 격리 대상은 언제나 큐의 맨 앞에 연속해서 있으므로(R-3), 경계값 하나로 충분합니다.
- `AUTOINCREMENT` 덕분에 rowid가 재사용되지 않아서 경계값이 새 행과 겹치지 않습니다.
- 격리 중 일시적 실패가 나면 상태를 그대로 두고 backoff합니다. 앱을 재시작하면 상태가 사라지는데, 다시 400을 받으면 다시 격리하므로 정확성은 유지됩니다.
- Uploader에 새로 생기는 상태는 필드 2개(`isolateThroughId`, `batchLimit`)와 기존의 `nextAttemptAt`이 전부입니다.

### 승인 요청
- **R-8**: rowid 경계값 기반 격리를 채택한다.

---

## R-9. `properties` 복사 제한 (Q25 보완 2)

메인 스레드에서 하는 작업량을 상수로 묶기 위해 다음 제한을 제안합니다. 모두 넘으면 **해당 key만 빼고 경고**를 남깁니다.

| 항목 | 제한 |
|---|---|
| 중첩 깊이 | 5 |
| 전체 노드 수 (모든 key와 원소) | 500 |
| key 길이 | 256자 |
| 문자열 값 길이 | 8,192자 |
| `event` / `screen` 이름 길이 | 256자 (넘으면 호출 무시) |

- **순환 참조**: 현재 경로의 컨테이너를 `IdentityHashMap` 기반 집합으로 추적합니다. 순환이 발견되면 그 key를 뺍니다. 깊이 제한만으로도 무한 재귀는 막을 수 있지만, 경고 메시지를 정확하게 남기기 위해 함께 씁니다.
- 노드 예산을 모두 쓰면 그 시점에 복사를 멈춥니다. 따라서 호스트 앱이 아주 큰 Map을 넘겨도 메인 스레드 비용은 상한을 넘지 않습니다.

### 승인 요청
- **R-9**: 위 제한값을 채택한다. 숫자는 구현 단계에서 측정한 뒤 조정할 수 있다.

---

## R-10. 같은 `userId`로의 중복 `identify()` (새 제안)

많은 앱이 **앱을 실행할 때마다** `identify(currentUserId)`를 호출합니다. 그러면 identify 메시지가 계속 쌓여 R-4의 "identify 행 보호" 정책과 맞물려 저장소를 차지합니다.

### 제안
- storage coroutine에서 `cmd.userId == current.userId`이면 **메시지를 만들지 않고 Identity도 바꾸지 않습니다.**
- 콜백은 그 명령까지 포함한 트랜잭션이 끝난 뒤 Success로 완료합니다. "효과가 영구 저장된 상태"라는 Q17의 의미가 이미 성립하기 때문입니다.

### 승인 요청
- **R-10**: 같은 `userId`로의 중복 identify는 무시한다.

---

## R-11. `SimpleDateFormat` 사용 범위 (Q27 보완 2)

- storage coroutine은 `limitedParallelism(1)`이라 동시에 실행되지는 않지만, **실행되는 스레드가 바뀔 수 있습니다.**
- 따라서 **트랜잭션 하나당 인스턴스 하나**를 만들어 쓰고 그 트랜잭션 안에서만 씁니다(이벤트 payload 직렬화용). Uploader는 **요청 하나당** 인스턴스를 하나 만들어 `sentAt`에 씁니다. 공유하는 인스턴스는 없습니다.

### 승인 요청
- **R-11**: 인스턴스를 공유하지 않고, 트랜잭션 또는 요청 단위로 만들어 쓴다.

---

## 검토 후 아키텍처 (R-1~R-11을 모두 승인한 경우)

```
호출 스레드                              storage coroutine (DB·Identity 독점)
───────────                             ─────────────────────────────────
command 생성 (lock 밖)                   load identity
[lock] pending.addLast + 상한 적용  ──►  drain K → 트랜잭션 하나
storageSignal.trySend                     (events INSERT, identity UPSERT,
                                           상한 정리)
                                          commit → 콜백 post(main)
                                          개수 ≥ flushAt → uploadSignal
                                                 │
ticker(30초), flush() ───────────────────► uploadSignal (conflated)
                                                 ▼
                                        Uploader coroutine (single in-flight)
                                          nextAttemptAt / batchLimit /
                                          isolateThroughId
                                          readBatch, deleteByIds → storage
                                          HttpURLConnection → endpoint
```

동시성 장치는 lock 1개, coroutine 2개(storage, uploader), conflated 신호 채널 2개입니다.

---

# 2차 검토

R-1, R-3, R-5, R-11이 승인된 상태를 전제로 합니다. 아래의 `R-n'`은 1차 제안을 대체하는 수정안입니다.

---

## R-2'. `reset()` 실패 및 프로세스 종료 시나리오

### 전제
호스트 앱은 "`reset` 콜백 Success를 받은 뒤 호스트 세션 삭제" 순서를 따른다고 가정합니다.

### 시나리오 검토
| # | 상황 | 재시작 후 SDK | 재시작 후 호스트 | 결과 |
|---|---|---|---|---|
| S1 | 명령이 commit되기 전 종료 | A | A | 일관됨 |
| S2 | commit 후, 메인 스레드 콜백 실행 전 종료 | 익명 | A | 불일치. 호스트가 시작할 때 `identify(A)`를 호출하면 해소됨 (아래 가이드 G-2) |
| S3 | 콜백 실행 후, 호스트 세션 삭제 도중 종료 | 익명 | 호스트 구현에 따라 다름 | 호스트 앱 책임 범위 |
| S4 | `Failure(STORAGE_ERROR)` → 호스트는 로그아웃 진행 | **A (디스크)** | 로그아웃 | **개인정보 위험**: 이전 사용자 식별자가 복원됨 |
| S5 | `initialize()` 전에 `reset` 호출 → `NOT_INITIALIZED` | **A (디스크)** | 로그아웃 | **개인정보 위험** (S4와 같음) |
| S6 | DB 로드 전 `reset` (명령이 큐에서 대기) | S1 또는 S2와 같음 | | 창만 길어짐 |
| S7 | SDK 비활성 상태에서 `reset` (R-7') | **A (디스크, DB가 다시 열리면)** | 로그아웃 | **개인정보 위험** (S4와 같음) |
| S8 | 콜백을 쓰지 않는 호스트 | A 또는 익명 | 로그아웃 | 위험이 남음 (문서화) |

**결론**: 1차 제안(순서 가이드)만으로는 **실패 경로(S4, S5, S7)에서 이전 사용자 식별자가 복원되는 위험**이 남습니다.

### 수정안: 시작할 때의 정합성 보정을 기존 API로 가능하게 함
호스트 앱이 **매번 시작할 때** 자신의 로그인 상태를 SDK에 알리게 합니다. 이를 위해 다음 두 가지가 필요합니다.

**(a) `reset()`을 멱등으로 만든다 (Q8 의미 변경, 승인 필요)**
- `userId != null`이면 `userId`를 지우고 `anonymousId`를 새로 만듭니다. 기존 Q8 동작과 같습니다.
- `userId == null`(이미 익명)이면 **아무것도 바꾸지 않고** Success를 돌려줍니다.
- 이렇게 해야 로그아웃 상태인 호스트가 시작할 때마다 `reset()`을 호출해도 익명 사용자의 `anonymousId` 연속성이 깨지지 않습니다.
- 대가: "익명 상태에서 `anonymousId`만 새로 받는" 기능이 사라집니다. v1 요구사항에는 이 기능이 없습니다.

**(b) 통합 가이드**
- **G-1**: `initialize()`는 `Application.onCreate()`에서 호출한다. 그래야 S5가 생기지 않습니다.
- **G-2**: 앱을 시작할 때마다 `initialize()` 직후 로그인 상태면 `identify(currentUserId)`를, 로그아웃 상태면 `reset()`을 호출한다.
  - S2, S4, S5, S7 모두 다음 시작 때 보정됩니다. 명령 큐의 순서 덕분에 보정은 그 뒤의 모든 이벤트보다 먼저 적용됩니다.
  - 같은 `userId`로 반복되는 `identify`는 R-10'에 따라 처리됩니다.
- **G-3**: 로그아웃할 때는 `reset { result -> }`의 결과를 받은 뒤 호스트 세션을 삭제한다. Failure여도 로그아웃은 진행하고, G-2가 다음 시작 때 보정한다.

### 남는 위험 (문서화)
- 보정이 적용되는 것은 "다음 시작 이후"입니다. S4/S7에서 **같은 프로세스 안에서는** 메모리 상태가 이미 익명이므로 이벤트가 섞이지 않습니다(R-7'). 위험은 "재시작 후 G-2의 `reset()`이 처리되기 전"의 창으로 줄어들고, 그 창에서도 명령 순서상 G-2가 먼저 적용됩니다. 실질적인 위험은 G-2를 지키지 않는 호스트로 한정됩니다.

### 승인 요청
- **R-2'a**: `reset()`을 멱등으로 바꾼다 (이미 익명이면 no-op + Success).
- **R-2'b**: 통합 가이드 G-1~G-3을 채택한다.

---

## R-4'. identify 메시지 보호와 저장 공간 부족 처리 구분

### 먼저 바로잡을 점
1차 R-4의 "identify 행은 지우지 않는다"는 표현은 **저장 상한 정리(eviction)에만** 해당합니다. identify 메시지도 다른 이벤트와 똑같이 **서버가 2xx로 처리 완료를 알리면 삭제**합니다(Q24). 아래에서 세 가지 경우를 분명히 나눕니다.

### (1) 정상 전송 후 삭제
- identify 행도 `type`과 관계없이 2xx를 받으면 `deleteByIds`로 삭제합니다. 단건 격리에서 영구 거부되면 `rejectedByServer`로 처리합니다.

### (2) 미전송 identify 메시지 보호 (논리적 상한)
- **메모리 큐**: `Identify`/`Reset` 명령은 in-memory 상한(Track/Screen 1,000개) 계산에 넣지 않고, 버리지도 않습니다.
- **SQLite 개수 상한**: 상한을 두 개로 나눕니다.
  - `maxQueuedEvents`(기본 1,000): `track`/`screen` 행에만 적용합니다. 넘으면 가장 오래된 track/screen 행부터 지웁니다. identify 행은 건드리지 않습니다.
  - `maxQueuedIdentifies`(기본 100, 내부 상수): identify 행에만 적용합니다. 넘으면 가장 오래된 identify 행부터 지우고 `evictedIdentify`에 기록합니다.
  - 이로써 **저장 행 수의 상한이 항상 존재합니다** (최대 1,100행). 1차 R-4의 "identify만 남으면 상한을 넘을 수 있다"는 예외가 사라집니다.
  - identify 행 100개를 넘으려면 서버 장애가 이어지는 동안 서로 다른 사용자로 100번 넘게 전환해야 합니다. 정상적인 사용에서는 생기지 않는 상황입니다.

### (3) 물리적 저장 공간 부족 (`SQLiteFullException`)
논리적 상한과는 다른 문제입니다. 기기 디스크 자체가 가득 찬 상황입니다.
1. 트랜잭션이 `SQLiteFullException`으로 실패하면 롤백합니다.
2. **공간 확보 시도를 한 번** 합니다. 가장 오래된 `track`/`screen` 행을 일정 비율(예: 현재 행 수의 25%) 지우는 별도 트랜잭션을 실행합니다. SQLite는 지워진 페이지를 재사용하므로 파일을 늘리지 않고도 다음 INSERT가 성공할 수 있습니다. 지운 행은 `evictedFromStore`에 기록합니다.
3. 원래 트랜잭션을 **한 번 재시도**합니다.
4. 그래도 실패하면 R-7'의 트랜잭션 실패 정책을 따릅니다. 이때 identify 메시지는 버리지 않고 **메모리의 재시도 목록**에 남깁니다(R-7' 참고).

### 승인 요청
- **R-4'**: 위 (1)~(3)의 구분, 두 개의 상한(`maxQueuedEvents`, `maxQueuedIdentifies`), 디스크 부족 시 1회 공간 확보와 1회 재시도를 채택한다.

---

## R-6'. `initialize()` 반환값의 계약

### 문제
`Boolean`을 반환하면 `true`가 "SDK가 준비되었다(DB 열기 성공)"로 오해될 수 있습니다. 하지만 DB 열기는 비동기라서 반환 시점에는 그 결과를 알 수 없습니다.

### 수정안: `Boolean` 대신 의미가 이름에 드러나는 enum
```kotlin
public enum class InitializeResult {
    STARTED,              // 이번 호출로 설정을 등록하고 비동기 초기화를 "시작"함
    ALREADY_INITIALIZED,  // 이미 초기화됨. 이번 호출은 무시함 (설정이 다르면 Log.e)
    INVALID_CONFIGURATION // 동기 검증 실패 (빈 apiKey, HTTPS가 아닌 endpoint 등). 초기화하지 않음
}
```

**계약 (KDoc과 문서에 명시)**
- 반환값은 **이 호출을 받아들였는지만** 나타냅니다. DB 열기, Identity 로드, 네트워크 상태 같은 **비동기 초기화의 성공 여부를 나타내지 않습니다.**
- `STARTED`를 반환한 뒤 비동기 초기화가 실패하면 SDK는 비활성 상태가 됩니다(R-7'). 이 사실은 `Log.e`와 이후 `identify`/`reset` 콜백의 `Failure(STORAGE_ERROR)`로 알 수 있습니다. v1에는 별도의 초기화 완료 콜백을 두지 않습니다.
- `INVALID_CONFIGURATION`이면 SDK는 초기화되지 않은 상태로 남습니다. 이후 호출은 `NOT_INITIALIZED`로 처리됩니다. 설정을 고쳐서 다시 `initialize()`를 호출할 수 있습니다.
- 어떤 경우에도 예외를 던지지 않습니다.

### 승인 요청
- **R-6'**: `InitializeResult` enum과 위 계약을 채택한다.

---

## R-10'. 같은 `userId`로 identify할 때 메시지를 생략해도 되는가

### 서버의 익명 사용자 연결 계약을 명시
지금까지 "identify 메시지가 연결 수단"이라고만 했고, 정확한 계약은 정하지 않았습니다. 다음과 같이 정의할 것을 제안합니다.

> **연결 계약**: 모든 메시지(`track`, `screen`, `identify`)는 `anonymousId`와, 있으면 `userId`를 함께 담는다. 서버는 **어떤 메시지에서든** `(anonymousId, userId)` 쌍을 관찰하면 둘을 연결할 수 있다. `identify` 메시지는 그 연결을 **명시적으로 알리는 신호**이며, 이벤트가 하나도 없어도 연결이 이루어지게 한다.

### 생략이 안전한지 검증
생략 조건은 `cmd.userId == current.userId`(그리고 `current.userId != null`)입니다.

- **불변식**: `current.userId`가 X가 되는 유일한 경로는 `identify(X)`이고, 그때 `(current.anonymousId, X)`를 담은 identify 메시지가 **같은 트랜잭션에서** commit됩니다(R-1). `anonymousId`가 바뀌는 유일한 경로는 `reset()`이고, 이때 `userId`도 null이 됩니다.
- 따라서 생략 조건이 참이면 **같은 `(anonymousId, userId)` 쌍의 identify 메시지가 이미 commit된 적이 있습니다.**

| 그 이전 identify 메시지의 운명 | 생략하면 연결이 사라지는가 |
|---|---|
| 전송되어 2xx 처리됨 | 아니오. 이미 연결됨 |
| 아직 큐에 있음 | 아니오. 나중에 전송됨 |
| `evictedIdentify`(R-4' 상한)로 지워짐 | 연결 계약에 따라 **그 뒤의 track/screen에도 같은 쌍이 담기므로** 이벤트가 하나라도 전송되면 연결됨 |
| 서버가 영구 거부함 (`rejectedByServer`) | 다시 보내도 같은 이유로 거부될 가능성이 높음. 위와 같이 이벤트로 연결됨 |
| R-7' 재시도 목록에서도 실패해서 유실 | 위와 같음 |

- 남는 차이는 "이전 identify가 유실됐고 그 뒤에 **이벤트가 하나도 없는** 경우"뿐입니다. 이때는 서버가 연결할 근거가 없지만, 보여 줄 행동 데이터도 없으므로 분석에 실질적인 손실은 없습니다.
- **DB 손상으로 Identity가 사라진 경우**: `current.userId == null`이 되므로 생략 조건에 걸리지 않습니다. 새 identify 메시지가 만들어집니다.

### 결론
위 연결 계약을 채택하면 생략해도 **계약을 위반하지 않습니다.** 연결 계약을 "identify 메시지로만 연결"로 좁게 정의한다면 생략은 위험하며, 그 경우에는 R-10을 철회하고 R-4'의 `maxQueuedIdentifies`로만 저장소를 보호해야 합니다.

### 승인 요청
- **R-10'a**: 위 연결 계약을 서버 계약(Q24 문서)에 추가한다.
- **R-10'b**: 그 계약을 전제로, 같은 `userId`로의 identify는 메시지 없이 Success로 완료한다.

---

## R-8'. 단건 격리의 실패 및 종료 시나리오

### 시나리오 검토 (1차 R-8 기준)
| # | 상황 | 결과 |
|---|---|---|
| I1 | 격리 중 종료 | 상태가 사라짐. 재시작 후 배치 400 → 다시 격리. 이미 삭제한 행은 다시 처리하지 않으므로 진행이 유지됨 |
| I2 | 단건 2xx 후 삭제 전 종료 | 다시 보냄 → 서버가 `messageId`로 중복 제거 (at-least-once) |
| I3 | 단건 400 후 삭제 전 종료 | 다시 격리 → 다시 400 → 삭제 |
| I4 | 격리 중 5xx / 타임아웃 / 429 | 상태 유지 + backoff (Q24 보완 3) |
| I5 | 격리 중 401/403 | 상태 유지 + 최대 backoff |
| I6 | 격리 중 상한 정리로 맨 앞 행이 지워짐 | `headId`가 경계를 넘으면 격리가 자동으로 끝남 |
| I7 | 격리 중 새 이벤트가 들어옴 | rowid가 경계보다 커서 격리 대상이 아님 |
| **I8** | **서버 결함이나 잘못된 endpoint 때문에 모든 요청에 400** | **1차 R-8은 큐 전체를 한 건씩 영구 거부로 삭제함 → 대량 유실** |

**I8이 1차 제안의 결함입니다.** 단건 400을 "이벤트 문제의 증거"로 보는 근거(Q24 보완 2)는 **서버가 계약을 지킬 때**에만 성립하는데, 모든 요청이 400이면 서버가 계약을 지키지 않는다는 신호입니다.

### 수정안: 격리 한 번의 결과를 보고 삭제 여부를 결정
```
isolateThroughId: Long?   // 격리 경계 (1차와 같음)
cursorId: Long            // 이번 격리에서 마지막으로 처리한 rowid
rejectedIds: List<Long>   // 단건 400/422를 받은 행 (아직 삭제하지 않음)
anyAccepted: Boolean      // 이번 격리에서 단건 2xx가 하나라도 있었는가
```
- 격리 중에는 `id > cursorId AND id <= isolateThroughId` 조건으로 한 건씩 읽습니다. 그래서 거부된 행을 지우지 않아도 다음 행으로 넘어갈 수 있습니다.
- 단건 2xx → 즉시 삭제하고 `anyAccepted = true`로 둡니다.
- 단건 400/422 → `rejectedIds`에 추가하고, **아직 삭제하지 않습니다.**
- 경계까지 모두 처리했을 때:
  - `anyAccepted == true`: 서버가 정상 이벤트를 받아들였으므로 거부는 이벤트 문제로 판단합니다. `rejectedIds`를 삭제하고 `rejectedByServer`에 기록합니다.
  - `anyAccepted == false`: 모두 거부되었으므로 **서버 문제로 판단**합니다. 아무것도 삭제하지 않고 `Log.e`를 남기고 **최대 backoff**로 전환합니다. 그 뒤 일반 배치 전송부터 다시 시도합니다.
- **정상 이벤트 없이 문제 이벤트 하나만 큐에 있는 경우**: `anyAccepted == false`라서 일단 남겨 둡니다. 새 이벤트가 쌓이면 다음 배치 400 → 격리에서 새 이벤트가 2xx를 받아 `anyAccepted == true`가 되고, 그때 삭제됩니다. 큐의 맨 앞이 영구적으로 막히지는 않습니다.
- **서버가 계속 400인 경우**: 삭제 없이 최대 backoff로 반복합니다. 데이터는 R-4' 상한 정리에만 영향을 받습니다. `401`/`403`과 같은 성격으로 처리합니다.
- 종료 시나리오 I1~I3는 그대로 성립합니다. 다만 I3는 "다시 격리하고, 이번 격리에 정상 이벤트가 있으면 삭제"가 됩니다.

상태가 1차의 필드 1개에서 4개로 늘지만, 모두 Uploader coroutine 안에서만 쓰는 지역적인 상태이고 재시작하면 초기화되어도 정확성에 문제가 없습니다.

### 승인 요청
- **R-8'**: "이번 격리에서 정상 수신이 있었을 때만 거부 행을 삭제, 전부 거부면 서버 문제로 보고 보존 + 최대 backoff"를 채택한다.

---

## R-7'. SQLite 오류와 내부 예외 처리 정책 (구체안)

### 원칙
1. **SDK 내부의 어떤 예외도 호스트 앱으로 전파하지 않는다.** 공개 API는 예외를 던지지 않는다.
2. 오류는 **분류해서** 처리하고, 카운터와 `Log.e`로 관측할 수 있게 한다.
3. 복구할 수 없으면 **그 프로세스 동안 비활성화**하고, 호스트 앱은 정상적으로 계속 동작한다.

### 오류가 생기는 위치별 처리
| 위치 | 예외 | 처리 |
|---|---|---|
| DB 열기 / 스키마 생성·업그레이드 / identity 로드 | `SQLiteCantOpenDatabaseException`, `SQLiteDiskIOException`, `SQLiteFullException`, 그 밖의 `SQLiteException` | **3번 시도**(즉시, 1초 후, 5초 후). 그동안 명령은 큐에 남음(메모리 상한 적용). 모두 실패하면 **비활성화** |
| 〃 | 손상 (`SQLiteDatabaseCorruptException`) | Android 기본 `DatabaseErrorHandler`가 파일을 삭제하고 다시 만듦. **저장된 이벤트와 Identity가 사라지고**, 새 `anonymousId`로 시작함. `Log.e` + 문서화 |
| 명령 트랜잭션 | `SQLiteFullException` | R-4' (3): 1회 공간 확보 → 1회 재시도 → 실패하면 아래 "트랜잭션 실패" |
| 〃 | 그 밖의 `SQLiteException` | 아래 "트랜잭션 실패" |
| 직렬화 (트랜잭션 안) | 32KB 초과, `JSONException` | **그 이벤트만** 빼고 나머지는 계속 처리. `droppedOversized` / `droppedInvalid` |
| `readBatch` (Uploader) | `SQLiteException` | 이번 시도를 건너뛰고 일시적 실패처럼 backoff |
| `deleteByIds` (2xx 이후) | `SQLiteException` | 행이 남아서 다음에 다시 전송됨 → 서버가 중복 제거. at-least-once와 모순 없음. `Log.e` |
| storage/uploader 루프의 예상하지 못한 예외 (버그) | `RuntimeException` 전반 | 루프의 한 번 반복 단위로 잡아서 `Log.e`. **연속 5번**이면 비활성화. coroutine scope는 `SupervisorJob` + `CoroutineExceptionHandler`로 마지막 방어 |
| 콜백 실행 (메인 스레드) | 호스트 코드의 예외 | 잡아서 `Log.e`. SDK 상태에 영향 없음 (Q21) |

### "트랜잭션 실패" 처리
트랜잭션은 롤백되고, 그 안에 있던 명령을 다음과 같이 처리합니다.

| 명령 | 처리 |
|---|---|
| Track / Screen | 버림 → `droppedStorageError` |
| Identify / Reset (Identity 변경) | **메모리에는 적용**해서 이후 이벤트가 올바른 식별자를 갖게 함. `identityDirty = true`로 표시해서 다음 트랜잭션에서 identity 행을 다시 저장 |
| Identify 메시지 | **메모리 재시도 목록**(최대 10개, 넘으면 가장 오래된 것부터 버리고 `droppedStorageError`)에 넣고 다음 트랜잭션 맨 앞에서 다시 INSERT |
| 해당 콜백 | `Failure(STORAGE_ERROR)`. 나중에 재시도가 성공해도 **다시 호출하지 않음** (exactly-once) |

- 명령 트랜잭션이 **연속 5번** 실패하면 비활성화합니다.
- `identityDirty`인 상태에서 새 명령이 없어도 재시도되도록, storage coroutine은 30초 뒤 한 번 더 저장을 시도합니다. 그 뒤에는 다음 명령이 들어올 때 시도합니다.

### 비활성 상태
- 들어오는 모든 명령은 버리고, `identify`/`reset` 콜백은 즉시 `Failure(STORAGE_ERROR)`로 완료합니다. 새 `FailureReason`은 추가하지 않습니다.
- Uploader를 멈춥니다.
- 재활성화는 **다음 프로세스 시작** 때 `initialize()`로만 이루어집니다. 같은 프로세스 안에서 자동 복구는 v1에서 하지 않습니다.
- 진입할 때 한 번 `Log.e`를 남깁니다. 그 뒤 호출마다 로그를 남기지는 않습니다(로그 폭주 방지).

### 문서화할 유실 조건 (§6에 추가)
- DB 손상 → 저장된 이벤트와 Identity 유실, 새 `anonymousId`
- 트랜잭션 실패 → 그 트랜잭션의 track/screen 유실, identify 메시지는 최대 10개까지 메모리에서 재시도
- 비활성화 → 그 프로세스 동안 모든 호출이 유실

### 승인 요청
- **R-7'**: 위 표의 처리, 3회 열기 재시도, 트랜잭션 실패 정책, 연속 5회 실패 시 비활성화, 다음 프로세스 시작 때만 재활성화를 채택한다.

---

## R-9'. `properties` 검증·복사 정책 (구체안)

### 실행 위치와 원칙
- **호출 스레드(lock 밖)**에서 실행합니다. 결과는 SDK가 소유한 불변 구조(`Map`/`List`/원시값)입니다.
- **예외를 던지지 않습니다.** 규칙을 위반하면 그 부분만 빼고 계속 진행합니다.
- 잘라내기(truncate)는 하지 않습니다. 값을 몰래 바꾸면 분석 데이터가 왜곡되므로, **그대로 담거나 빼거나** 둘 중 하나만 합니다.

### 값 타입 처리
| 입력 | 처리 |
|---|---|
| `String`, `Boolean`, `Int`, `Long`, `Double` | 그대로 |
| `Float` | `Double`로 변환 |
| `Byte`, `Short` | `Int`로 변환 |
| `NaN`, `±Infinity` | 그 key 제외 |
| `null` | JSON `null`로 그대로 |
| `Map<*, *>` | key가 `String`인 항목만 재귀 복사 (Java 호출자가 String이 아닌 key를 넘길 수 있음) |
| `List<*>` | 재귀 복사 |
| `Array`, `Set`, `Char`, `BigDecimal`, `BigInteger`, `enum`, `Date` 등 그 밖의 모든 타입 | 그 key 또는 원소 제외 |

### 제한
| 항목 | 제한 | 넘었을 때 |
|---|---|---|
| 중첩 깊이 | 5 | 그 컨테이너 제외 |
| key 길이 | 1~256자 | 그 항목 제외 (빈 key 포함) |
| 문자열 값 길이 | 8,192자 | 그 항목 제외 |
| 전체 노드 수 | 500 | **properties 전체를 비우고** 이벤트는 보냄 (아래 참고) |
| 순환 참조 | 현재 경로 위의 컨테이너를 identity 기준으로 추적 | 그 항목 제외 |
| `event` / `screen` 이름 | 공백 아닌 1~256자 | **호출 무시** |

- **노드 수 초과 시 전체를 비우는 이유**: `HashMap` 순회 순서는 정해져 있지 않습니다. 그래서 "앞의 500개만 담기"를 하면 같은 입력에도 어떤 key가 남을지 매번 달라집니다. 이벤트 이름과 Identity만이라도 정확히 남기는 편이 낫다고 판단합니다.
- **호스트가 넘긴 Map을 다른 스레드에서 동시에 수정**하면 `ConcurrentModificationException` 등이 날 수 있습니다. 잡아서 properties 전체를 비우고 이벤트는 보냅니다. Kotlin의 읽기 전용 `Map`도 실제로는 가변일 수 있으므로 반드시 처리해야 합니다.
- 그 밖의 예상하지 못한 예외도 properties 전체를 비우는 것으로 처리합니다. 이벤트를 만드는 경로에서 절대 예외가 새어 나가지 않게 합니다.

### 관측
- 위반 내용은 이벤트 하나당 **요약 경고 로그 한 줄**로 남깁니다(빠진 key 경로 최대 5개). 위반 하나마다 로그를 찍지 않습니다.
- 카운터 `propertiesSanitized`(일부 제외)와 `propertiesDropped`(전체를 비움)를 둡니다.

### 백그라운드 단계 (storage coroutine)
- 직렬화한 크기가 32KB를 넘으면 그 이벤트를 버립니다 (`droppedOversized`, L-4).
- 직렬화 중 예외가 나면 그 이벤트를 버립니다 (`droppedInvalid`).

### 승인 요청
- **R-9'**: 위 타입 처리, 제한값, 위반 시 처리, 관측 방식을 채택한다. 제한값은 구현 단계에서 측정한 뒤 조정할 수 있다.

---

## 2차 검토 승인 요청 요약
| 항목 | 요청 |
|---|---|
| R-2'a | `reset()`을 멱등으로 바꾼다 (이미 익명이면 no-op) — **Q8 의미 변경** |
| R-2'b | 통합 가이드 G-1~G-3 |
| R-4' | 전송 후 삭제 / 논리 상한 2종 / 디스크 부족 처리를 구분 |
| R-6' | `InitializeResult` enum과 반환값 계약 |
| R-7' | SQLite 오류와 내부 예외 처리 정책 |
| R-8' | 정상 수신이 있을 때만 거부 행 삭제, 전부 거부면 보존 |
| R-9' | `properties` 검증·복사 정책 |
| R-10'a | 서버 연결 계약 명시 — **Q24 계약 확장** |
| R-10'b | 같은 `userId` identify는 메시지 생략 |

---

# 3차 검토

승인 상태: R-1, R-3, R-5, R-11 (1차), R-4', R-6', R-9' (2차).
이 섹션의 `R-n''`은 2차의 R-2', R-7', R-8', R-10'을 대체합니다.

사용자 지시에 따라 지켜야 할 조건은 다음과 같습니다.
- `reset()`은 Q8 그대로 **호출할 때마다 새 `anonymousId`를 발급**한다.
- 영속화에 실패한 Identity를 메모리에만 적용하지 않는다. Identity 변경과 identify 메시지의 원자성을 유지한다.
- 서버 연결 계약은 **identify 메시지 중심**으로 유지한다.

---

## R-7''. 저장 실패 시 "멈추고 재시도, 안 되면 비활성화"

### 핵심 아이디어
트랜잭션이 실패하면 그 명령들을 **버리지도, 메모리에만 적용하지도 않습니다.** 큐의 맨 앞에 그대로 둔 채 **같은 명령들을 다시 시도**합니다. 정해진 횟수만큼 시도해도 실패하면 SDK를 비활성화합니다.

```
storage coroutine:
  commands = 큐 앞에서 최대 K개를 "꺼내지 않고" 확인(peek)
  next = current
  트랜잭션 { 명령 순서대로 적용 }
  성공 → 큐에서 제거, current = next, 콜백 Success, attempts = 0
  실패 → 롤백, current 그대로, 큐 그대로
         attempts += 1
         attempts < 3 → 1초, 5초 뒤 재시도
         attempts == 3 → 비활성화
```

### 지켜지는 것
| 요구사항 | 방법 |
|---|---|
| Identity 변경과 identify 메시지의 원자성 | 둘이 같은 트랜잭션에 있고, 실패하면 함께 롤백되며 함께 재시도됨 |
| 영속화에 실패한 Identity를 이후 이벤트에 적용하지 않음 | `current`는 **commit이 성공했을 때만** 바뀜. 실패한 Identity는 어디에도 반영되지 않음 |
| 이벤트 순서 | 큐가 그대로 유지되므로 재시도해도 순서가 같음 |
| 콜백 exactly-once | 콜백은 **최종 결과**(commit 성공 또는 비활성화)에서 한 번만 호출됨. 재시도 중에는 호출하지 않음 |

### 저장 실패 뒤 큐에 남아 있는 `track()` 명령의 처리
예시: `track(T1)`, `reset(R)`, `track(T2)`가 순서대로 큐에 있고, 이 트랜잭션이 실패한 경우

| 시점 | T1 | R | T2 | 그 뒤 새로 들어오는 `track(T3…)` |
|---|---|---|---|---|
| 재시도 대기 중 | 큐에 남음 | 큐에 남음 | 큐에 남음 | 큐 뒤에 쌓임. 메모리 상한(Track 1,000개)을 넘으면 가장 오래된 Track부터 버림 (`droppedInMemory`). R은 버리지 않음 |
| 재시도 성공 | 이전 Identity로 저장 | 새 Identity 저장 | **새** Identity로 저장 | 새 Identity로 저장 |
| 3번 모두 실패 → 비활성화 | 버림 | 버림, 콜백 Failure | 버림 | 버림 |

- **어떤 경우에도 T2가 "저장되지 않은 새 Identity"를 가진 채 기록되는 일은 없습니다.** T2는 R이 commit된 뒤에만, 혹은 아예 기록되지 않습니다.
- 대가: 저장소에 장애가 생기면 최대 약 6초(즉시, 1초, 5초) 동안 처리가 멈춥니다. 그동안 들어오는 명령은 메모리에 쌓이고(상한 있음), `reset` 콜백도 그만큼 늦어집니다. 이 지연 상한을 문서에 명시합니다.

### 그 밖의 오류도 같은 경로로 통일
| 오류 | 처리 |
|---|---|
| DB 열기 / 스키마 / identity 로드 실패 | 같은 "3번 시도 → 비활성화" |
| `SQLiteFullException` | R-4' (3)의 공간 확보를 **첫 번째 재시도 전에 한 번** 수행. 그 뒤로는 같은 경로 |
| 손상 (`SQLiteDatabaseCorruptException`) | 기본 `DatabaseErrorHandler`가 파일을 다시 만들고, 새 `anonymousId`와 `userId = null`로 시작함. 이전 사용자의 식별자가 남지 않는 방향이므로 개인정보 문제는 없음. 연결이 끊기는 문제는 R-2''의 `syncUser`가 복구함 |
| 직렬화 실패 / 32KB 초과 | **그 이벤트만** 빼고 트랜잭션은 계속 진행 (데이터 문제이지 저장소 문제가 아님, R-9'과 같음) |
| `readBatch` / `deleteByIds` 실패 (Uploader) | 2차 R-7'과 같음 (backoff 후 다시 시도 / 다시 전송되면 서버가 중복 제거) |
| 루프 안의 예상하지 못한 예외 | 저장소 실패와 같은 "3번 시도 → 비활성화" 경로로 합침 |

### 비활성 상태 (2차와 같음)
- 대기 중인 명령과 이후 명령은 모두 버립니다. `identify`/`reset`/`syncUser` 콜백은 `Failure(STORAGE_ERROR)`입니다. Uploader를 멈추고, 다음 프로세스 시작 때만 다시 활성화합니다.
- **비활성화되면 그 프로세스에서는 어떤 이벤트도 기록되지 않습니다.** 따라서 같은 프로세스 안에서 식별자가 섞일 일이 없습니다. 디스크에 남은 이전 Identity 문제는 R-2''에서 다룹니다.

### 2차 R-7' 대비 상태 변화
- **제거**: `identityDirty`, 30초 재저장 타이머, identify 메시지 재시도 목록, "트랜잭션 연속 5회" 카운터와 "루프 예외 연속 5회" 카운터의 구분
- **유지/추가**: `attempts`(0~3), `disabled`
- 결과적으로 2차안보다 **상태와 분기가 줄어듭니다.**

### 승인 요청
- **R-7''**: "실패한 명령을 큐 앞에 두고 같은 명령을 재시도(즉시, 1초, 5초) → 실패하면 비활성화"를 채택하고, 열기·쓰기·예상하지 못한 예외를 같은 경로로 처리한다.

---

## R-2''. 이전 Identity가 복원되는 문제의 안전한 정책

### 왜 기존 API만으로는 안 되는가
이전 Identity가 디스크에 남는 경우는 네 가지입니다.
- commit 전 종료
- `reset` 실패 (R-7'' 비활성화)
- `initialize()` 전 `reset`
- 콜백을 기다리지 않는 호스트

공통점은 **다음 시작 때 SDK의 디스크 상태와 호스트의 로그인 상태가 다르다**는 것입니다. 시작할 때마다 보정하면 되는데, 기존 API로는 안전하게 보정할 수 없습니다.
- 로그아웃 상태에서 시작할 때마다 `reset()`을 호출하면, Q8에 따라 **매번 새 `anonymousId`가 발급**되어 익명 사용자의 연속성이 깨집니다.
- SDK가 A로 남아 있는데 `identify(B)`를 호출하면, A의 `anonymousId`가 B와 연결됩니다. 이것도 Q8이 막으려던 섞임입니다.

### 제안: 선언형 보정 API `syncUser(userId: String?)` 하나 추가
"호스트가 알고 있는 현재 로그인 사용자"를 선언하면, SDK가 **commit된 Identity와 비교해서** 필요한 동작만 합니다. 비교는 storage coroutine에서 명령 순서대로 하므로, 그 뒤의 모든 이벤트보다 먼저 적용됩니다.

| 호스트가 선언 | SDK의 commit된 Identity | 동작 |
|---|---|---|
| `null` | `userId == null` | **아무것도 하지 않음** (`anonymousId` 유지) |
| `null` | `userId == A` | `reset()`과 같음 (새 `anonymousId`) |
| `X` | `userId == null` | `identify(X)`와 같음 (identify 메시지 생성) |
| `X` | `userId == X` | `identify(X)`와 같음 (R-10'' 정책에 따라 메시지 생성) |
| `X` | `userId == A` (A ≠ X) | `reset()` 후 `identify(X)` (새 `anonymousId`로 X 연결) |

- `reset()`과 `identify()`의 의미는 **바뀌지 않습니다.** Q8도 그대로입니다.
- 새 `anonymousId`와 `messageId`는 다른 명령처럼 호출 시점에 미리 만들어 명령에 담습니다. 쓰이지 않으면 버립니다.
- 콜백과 결과 타입은 `identify`/`reset`과 같습니다 (`IdentityCallback`).
- 명령 종류가 하나 늘지만, 처리는 기존 Reset/Identify 적용 로직을 조합하는 것이라 새로운 상태가 생기지 않습니다.

### 통합 가이드 (G-2 수정)
- **G-1**: `initialize()`는 `Application.onCreate()`에서 호출한다.
- **G-2**: `initialize()` **바로 다음에**, 다른 이벤트보다 먼저 `syncUser(호스트의 현재 userId 또는 null)`를 호출한다.
- **G-3**: 로그아웃할 때는 `reset { result -> }`의 결과를 받은 뒤 호스트 세션을 삭제한다. Failure여도 로그아웃은 진행한다. G-2가 다음 시작 때 보정한다.

### 시나리오 재검토
| # | 상황 | 다음 시작 때 |
|---|---|---|
| S1 | commit 전 종료 (호스트 세션이 남아 있음) | `syncUser(A)`, SDK도 A → identify(A) 재전송. 일관됨 |
| S2 | commit 후 콜백 전 종료 | `syncUser(A)`, SDK는 익명 → identify(A). 일관됨 |
| S4 | reset 실패 → 비활성화, 호스트는 로그아웃 | `syncUser(null)`, SDK는 A → reset. **복원 위험 해소** |
| S5 | `initialize()` 전 reset | S4와 같음. 해소 |
| S7 | 비활성 상태에서 reset | S4와 같음. 해소 |
| S8 | `syncUser`를 쓰지 않는 호스트 | 위험이 남음 → 문서화 |

### 남는 한계 (문서화)
- 보정은 **`syncUser`가 처리된 시점부터** 적용됩니다. 호스트가 그 전에 `track()`을 호출하면 그 이벤트에는 복원된 이전 Identity가 붙습니다. 그래서 G-2에서 "다른 이벤트보다 먼저"를 요구합니다.
- 다음 시작 때 저장소가 다시 실패하면 그 프로세스도 비활성화되므로 이벤트가 기록되지 않습니다. 섞임은 없습니다.

### 같이 확인한 사항 (결정 요청 아님)
- `identify(B)`를 A 상태에서 바로 호출하면 A의 `anonymousId`가 B와 연결되는 동작은 **기존 Q8/Q25 그대로 유지**합니다. 사용자를 전환할 때는 `reset()` 후 `identify()`를 하거나 `syncUser(B)`를 쓰라고 가이드에 적습니다.

### 승인 요청
- **R-2''a**: 공개 API `syncUser(userId: String?, callback: IdentityCallback? = null)`를 추가한다 (위 표의 동작).
- **R-2''b**: 통합 가이드 G-1~G-3을 채택한다.

---

## R-8''. 정상적인 영구 거부와 대량 거부의 구분

### 2차안의 문제
"정상 수신이 하나라도 있으면 거부 행을 모두 삭제"는 서버 일부가 고장 난 경우를 막지 못합니다. 예를 들어 특정 이벤트 타입이나 특정 필드 처리만 고장 나면 정상 이벤트 일부는 통과하므로, 고장 때문에 거부된 이벤트가 모두 삭제됩니다.

### 원칙: 서버가 명시적으로 "이 이벤트가 문제"라고 말할 때만 삭제하고, 그 삭제량에도 상한을 둔다

**(1) 서버 계약 확장 (Q24 보완)**
- 단건 요청을 거부할 때 이벤트 자체의 문제라면, 서버는 `400`/`422` 본문에 **기계가 읽을 수 있는 오류 코드**를 담습니다.
  ```json
  { "error": { "code": "invalid_event", "messageId": "..." } }
  ```
- 그 밖의 400/422(코드가 없거나, 다른 코드이거나, HTML 응답이거나, `messageId`가 요청과 다른 경우)는 **요청 수준 또는 서버 문제**로 간주합니다.
- 잘못된 endpoint, 프록시, 게이트웨이가 돌려주는 일반적인 400은 이 형식을 갖지 않으므로 자연스럽게 걸러집니다.

**(2) 클라이언트 처리**
| 상황 | 처리 |
|---|---|
| 배치 400/422 | 단건 격리 시작 (1차 R-8과 같음: `isolateThroughId`) |
| 단건 400/422 + `invalid_event` 코드 + `messageId` 일치 | 그 이벤트 삭제, `rejectedByServer` |
| 단건 400/422 + 위 조건 불충족 | **서버 문제로 판단**: 삭제하지 않음, 격리 종료, `Log.e`, 최대 backoff |
| 프로세스당 400 경로 삭제가 **상한(20건)에 도달** | 이후의 `invalid_event`도 서버 문제로 취급 (보존 + 최대 backoff + `Log.e`) |
| 격리 중 5xx / 타임아웃 / 429 / 401 / 403 | 상태 유지 + 해당 backoff (Q24 보완 3) |

- **상한의 역할**: 서버 구현이 코드를 잘못 붙이는 버그가 있어도, 한 프로세스에서 400 경로로 잃을 수 있는 이벤트는 최대 20건입니다. 정상적인 영구 거부(계약을 지키는 서버의 드문 데이터 오류)는 이 상한에 거의 도달하지 않습니다.
- **2xx `rejected` 목록**은 서버가 "처리 완료"를 선언하는 정상 경로이므로 상한 없이 그대로 따릅니다(Q24). 다만 배치의 **전부가** `rejected`에 있으면 `Log.e`를 남겨 서버 이상 징후를 드러냅니다. 동작은 바꾸지 않습니다.

### 종료 시나리오
| # | 상황 | 결과 |
|---|---|---|
| I1 | 격리 중 종료 | 재시작 후 다시 격리. 이미 삭제된 행은 다시 처리하지 않음 |
| I2 | 단건 2xx 후 삭제 전 종료 | 다시 전송 → `messageId` 중복 제거 |
| I3 | `invalid_event` 판정 후 삭제 전 종료 | 다시 격리 → 다시 같은 판정 → 삭제 |
| I8 | 모든 요청이 일반 400 | 첫 단건에서 "서버 문제"로 판단 → **삭제 0건**, 최대 backoff |
| I9 | 서버가 모든 이벤트에 `invalid_event`를 붙이는 버그 | 프로세스당 최대 20건 삭제 후 보존 + 최대 backoff |

### 2차 R-8' 대비 상태 변화
- **제거**: `cursorId`, `rejectedIds`, `anyAccepted`
- **유지/추가**: `isolateThroughId`(1차와 같음), `rejectionDeletes`(프로세스당 카운터, 메모리)
- 거부 행을 보류했다가 건너뛰는 로직이 없어지므로 **2차안보다 단순합니다.**

### 승인 요청
- **R-8''a**: 단건 거부 응답에 `invalid_event` 코드와 `messageId`를 담도록 서버 계약을 확장한다 (Mock Server도 구현).
- **R-8''b**: 코드가 있을 때만 삭제하고, 프로세스당 20건 상한을 둔다. 그 밖의 경우는 서버 문제로 보고 보존한다.

---

## R-10''. `identify()`는 항상 메시지를 생성한다

### 정책
- `identify(userId)`는 `userId`가 현재와 같아도 **항상 identify 메시지를 생성**합니다(새 `messageId`). 생략 로직은 없습니다. `syncUser(X)`가 이미 X인 상태에서 호출되는 경우도 같습니다.
- 서버 연결 계약은 **identify 메시지 중심**으로 유지합니다. 2차 R-10'a(모든 메시지로 확장)는 철회합니다.

### 저장 공간
- 반복 identify로 쌓이는 행은 R-4'에서 승인한 `maxQueuedIdentifies`(100)가 막습니다. 따라서 저장 공간 측면에서 생략 로직은 필요 없습니다.
- 다만 이 상한 때문에 오래된 identify가 지워질 수 있습니다. 같은 사용자의 identify가 반복되는 상황이라면 지워지는 것도 같은 `(anonymousId, userId)` 쌍의 오래된 사본이므로 연결 정보는 남습니다. 서로 다른 사용자 전환이 100번 넘게 쌓이는 경우에만 실제 손실이 생기며, 이는 R-4'의 문서화 범위 안에 있습니다.

### `messageId` 중복 제거와의 관계 (구분해서 문서화)
| 구분 | 원인 | 성격 | 처리 주체 |
|---|---|---|---|
| **재전송 중복** | 같은 메시지를 응답 유실 등으로 다시 보냄 | 같은 `messageId` | 서버의 `messageId` 중복 제거 (Q13) |
| **반복 호출** | 호스트가 `identify(X)`를 여러 번 호출함 | **서로 다른 `messageId`**를 가진 별개의 메시지 | `messageId`로는 제거되지 않음. 서버는 identify를 `(anonymousId, userId)` 기준으로 **멱등하게** 처리해야 함 (같은 연결은 한 번만 반영) |

- 그래서 서버 계약에 다음 한 줄을 추가합니다. "**identify 처리는 `(anonymousId, userId)` 쌍에 대해 멱등해야 한다.**" 연결 계약을 확장하는 것이 아니라, identify 메시지의 처리 방식을 명확히 하는 것입니다.
- 앱을 실행할 때마다 `syncUser`를 호출하면(G-2) 로그인 사용자는 실행할 때마다 identify 메시지 하나를 보냅니다. 크기는 작고, 연결이 유실됐을 때 **자동으로 복구하는 효과**도 있습니다.

### 승인 요청
- **R-10''**: identify는 항상 메시지를 생성하고, 서버 계약에 "identify는 `(anonymousId, userId)`에 대해 멱등" 조항을 추가한다.

---

## 복잡도 재검토 (3차안을 모두 적용한 경우)

### 상태 목록
| 위치 | 상태 | 비고 |
|---|---|---|
| facade | 초기화 여부 | R-6' |
| 호출 스레드 | lock, `pending` 큐, Track 개수 | R-1, R-4' |
| storage coroutine | `current: Identity`, `attempts`, `disabled` | R-1, R-7'' |
| Uploader | `nextAttemptAt`, backoff 단계, `batchLimit`, `isolateThroughId`, `rejectionDeletes` | R-5, R-8'' |
| 관측 | 카운터 (`droppedInMemory`, `evictedFromStore`, `evictedIdentify`, `droppedOversized`, `droppedInvalid`, `rejectedByServer`, `propertiesSanitized`, `propertiesDropped`, `droppedStorageError`) | 읽기 전용, 로직에 영향 없음 |

### 2차안 대비
| | 2차안 | 3차안 |
|---|---|---|
| storage 오류 상태 | `identityDirty`, 재시도 목록, 30초 타이머, 연속 실패 카운터 2개 | `attempts`, `disabled` |
| 격리 상태 | `isolateThroughId`, `cursorId`, `rejectedIds`, `anyAccepted` | `isolateThroughId`, `rejectionDeletes` |
| identify 분기 | 같은 userId 생략 분기 | 없음 |
| 공개 API | — | `syncUser` +1 |
| 명령 종류 | Track, Screen, Identify, Reset | + SyncUser (기존 적용 로직을 조합) |

### 판단
- 상태와 예외 분기는 2차안보다 **줄었습니다.** 늘어난 것은 공개 API 하나(`syncUser`)와 서버 계약 조항 두 개(`invalid_event` 코드, identify 멱등성)입니다.
- **오류 처리 경로가 세 갈래로 정리됩니다.**
  - storage: 재시도 후 비활성화
  - 전송: Q23 응답 분류와 backoff
  - 데이터: 그 항목이나 그 이벤트만 제외
- 남는 비용: 저장소 장애 시 최대 약 6초 동안 처리가 멈추고, 그 결과 콜백이 늦어집니다(R-7''). 문서에 명시합니다.

---

## 3차 검토 승인 요청 요약
| 항목 | 요청 |
|---|---|
| R-7'' | 실패한 명령을 큐 앞에 두고 재시도(즉시/1초/5초) → 비활성화. 오류 경로 통일 |
| R-2''a | 공개 API `syncUser(userId: String?, callback?)` 추가 |
| R-2''b | 통합 가이드 G-1~G-3 |
| R-8''a | 서버 계약: 단건 거부 시 `invalid_event` 코드 + `messageId` |
| R-8''b | 코드가 있을 때만 삭제, 프로세스당 20건 상한, 그 밖에는 보존 |
| R-10'' | identify는 항상 메시지 생성. 서버 계약: identify는 `(anonymousId, userId)`에 대해 멱등 |
