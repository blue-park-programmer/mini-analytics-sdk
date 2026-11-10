# 5단계 보고서: 전송 파이프라인 (+ 4단계 보완)

- 작성일: 2026-11-09
- 계획: [`v1-implementation-plan.md`](./v1-implementation-plan.md) 5단계
- 이 단계가 끝나면 SDK는 **수집 → 영구 저장 → 배치 전송 → 재시도**까지 동작합니다. 단건 격리(400/422)는 6단계입니다.

## A. 4단계 보완

### C-1: `Error` 전달 경로 검토 (설계 §8.4)
kotlinx-coroutines 1.10.2 소스(`handleCoroutineException`, `StandaloneCoroutine.handleJobException`)로 확인한 결과입니다.
- context에 `CoroutineExceptionHandler`가 있으면 coroutines는 **그 handler만 호출하고 끝냅니다.** 전역 handler나 스레드의 uncaught handler를 따로 부르지 않습니다.
- 그래서 우리 handler가 `Error`를 명시적으로 넘기는 경로가 **유일한 전파 경로**입니다. 중복 전파는 없고, 명시적으로 넘기지 않으면 `Error`가 조용히 사라집니다.
- `Exception`은 넘기지 않으므로 불필요한 프로세스 종료가 없습니다.
- 전제: storage coroutine의 부모는 `SupervisorJob`이어야 합니다(`AnalyticsClient.create`). 설계 §8.4에 실제 전달 경로를 그림으로 남겼습니다.

**새 테스트 2개** (운영 기본값인 스레드의 uncaught handler를 그대로 사용):
- `Error`는 uncaught handler에 **정확히 한 번** 도달함
- 새어 나온 `Exception`은 uncaught handler에 **도달하지 않음**

**mutation check**
- 명시적 전달을 제거 → `Error`가 사라져서 2개 실패
- 두 번 전달하도록 바꿈 → "정확히 한 번" 테스트 포함 2개 실패

### 그 밖의 반영
- `identify`/`reset` 크기와 호출 빈도 추정은 **실측 전 가정 A-9**로 표시했습니다(설계 §11.7, §13). 상한 없는 현재 정책은 유지합니다.
- C-2(검증 순서)와 C-3(`@JvmStatic`)은 구현을 그대로 유지했습니다. Java 공개 시그니처는 아래 "공개 API"에서 다시 확인했습니다.

## B. 5단계: 전송 파이프라인

### 구현한 것
| 파일 | 역할 |
|---|---|
| `internal/upload/Transport.kt` | `Transport` 인터페이스. `HttpUrlConnectionTransport`: HTTPS 강제(테스트에서 명시적으로 켠 경우만 http), 헤더 3종, 15초 타임아웃, 리다이렉트를 따라가지 않음, 본문 최대 64K자, `Retry-After`(초와 HTTP-date) 해석 |
| `internal/upload/ResponsePolicy.kt` | 응답 분류(설계 §6.3), 지수 backoff + full jitter(1초 시작, 최대 5분), `Retry-After` 우선 적용(상한 있음) |
| `internal/upload/EnvelopeBuilder.kt` | `{sentAt, context, batch[]}`. 저장된 payload를 다시 파싱하지 않고 그대로 이어 붙임. `sentAt`은 요청마다 새 formatter로 만듦. context는 라이브러리, OS, 앱 버전만 담음 |
| `internal/upload/Uploader.kt` | 단일 전송 coroutine, conflated trigger, DB는 storage dispatcher, 네트워크는 network dispatcher, 성공한 배치의 id만 삭제, 2xx `rejected` 집계 |
| `internal/AnalyticsClient.kt` | Uploader 연결: commit 후 개수 trigger, `flush()`, 비활성화 시 정지 |
| `internal/pipeline/EventPipeline.kt` | `onDisabled` 훅 추가 |
| `AndroidManifest.xml` | `android.permission.INTERNET` 선언 (호스트 manifest에 병합됨) |

### 사용자 지정 검증 항목
| # | 항목 | 검증 | 결과 |
|---|---|---|---|
| 1 | 서버 수신이 확인된 `messageId`만 삭제 | 요청이 진행되는 동안 새 이벤트 2건을 저장 → 2xx 후 **보낸 3건만** 삭제되고 새 2건은 남음. 500이면 아무것도 삭제하지 않음 | 통과 |
| 2 | 성공 후 삭제 전 종료 → 같은 `messageId`로 재전송 | (a) 2xx 뒤 로컬 삭제 실패 → backoff 후 **같은 messageId 목록**으로 재전송. (b) **실제 SQLite**: 2xx 뒤 삭제 전에 "종료"하고 DB를 다시 열어 새 Uploader를 만듦 → 같은 messageId 목록 `[e-1, e-2, e-3]`을 재전송 | 통과 |
| 3 | 여러 flush가 동시에 실행되지 않음 | 실제 스레드 8개 × `flush()` 200회(총 1,600회)를 첫 요청이 블로킹된 동안 호출 → 동시에 진행된 요청은 최대 **1개**, 실제 요청은 배치 수 그대로 3번(100, 100, 50) | 통과 |
| 4 | 네트워크 요청 중에도 `track()` 저장이 지연되지 않음 | 실제 dispatcher(운영과 같은 구성)에서 전송을 블로킹한 상태로 `track()` 300회 → 모두 1초 안에 반환되고 **301건 모두 commit됨**. 그동안 요청은 여전히 1개만 진행 중 | 통과 |
| 5 | 429, 5xx, 네트워크 오류와 영구 오류를 구분 | 아래 표 | 통과 |
| 6 | 서버 멱등성 문서화 | 설계 §6.5에 "SDK의 전제와 중복 수집 가능성" 추가 (아래) | 문서화 |

**#5 재시도 정책 테스트 (가상 시간, jitter를 항상 최댓값으로 고정)**
| 응답 | 동작 | 확인한 시도 시각 |
|---|---|---|
| 503 × 3 | 지수 backoff | 0, 1초, 3초, 7초 |
| 네트워크 오류, 408 | 일시적 실패와 같음 | 0, 1초, 3초 |
| 429 + `Retry-After: 10` / `3600` | `Retry-After` 우선, 5분 상한 | 0, 10초, 310초 |
| backoff 중 `flush()` 50회 + 개수 trigger + ticker 9회 | 시도를 앞당기지 않음 | 1초 전에는 요청 1번뿐 |
| 401, 403 | 유지 + 최대 backoff + `Log.e` | 0, 300초. 삭제 0건 |
| 400 (6단계 전 임시), 404 (설계에 없음) | 유지 + 최대 backoff + `Log.e` | 0, 300초, 600초. 삭제 0건 |
| 413 | 배치를 절반으로 → 1건도 413이면 그 이벤트만 삭제 | 배치 크기 8 → 4 → 2 → 1(삭제) → 1 → 6. `rejectedByServer` = 1 |
| 2xx + `rejected` | 배치 전체 삭제, 거부 수 집계. 전부 거부되면 `Log.e` | — |

**그 밖의 테스트**: 시작 시 이전 실행에서 남은 이벤트를 바로 전송, 개수 trigger는 `flushAt`에서만 발생, 주기 trigger(30초), `flush()`는 `flushAt` 미만도 전송, `flushAt` 이상 남으면 계속 전송하고 미만이면 다음 trigger를 기다림, 배치 512KB 상한, 읽기 실패 후 backoff, 정지 후 전송 없음, HTTP 계약(MockWebServer: 메서드·경로·헤더·본문, 오류 코드 반환, 리다이렉트 미추적, 연결 실패와 읽기 타임아웃은 `IOException`, http 거부).

### 테스트 결과
- `./gradlew :mini-analytics:assembleRelease :mini-analytics:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL, 263개 통과 (실패 0)**. 4단계 220개에서 43개가 늘었습니다.
- 늘어난 테스트: `UploaderTest` 24, `HttpUrlConnectionTransportTest` 7, `ResponsePolicyTest` 5, `EnvelopeBuilderTest` 3, `UploaderConcurrencyTest` 2, C-1 2
- 실제 스레드와 네트워크를 쓰는 테스트 6종을 `--rerun-tasks`로 **10번 반복해서 10번 모두 통과**했습니다.

### mutation check
| 일부러 넣은 결함 | 결과 |
|---|---|
| 네트워크 요청을 storage dispatcher에서 실행 | #4 테스트 실패 |
| 성공한 배치가 아니라 저장된 전체를 삭제 | 5개 실패 (#3, 413, 배치 상한 등) |
| 첫 시도: `flush()`가 대기 시각을 초기화 | **잡히지 않음**. 루프가 이미 `delay()` 중이라 동작이 바뀌지 않는, 무효한 mutation이었음 |
| 다시 시도: 일시적 실패 뒤 backoff 대신 다음 trigger에 바로 재시도 | 5개 실패 (`triggers during backoff…` 포함) |
| trigger마다 새 전송 루프 실행 (single in-flight 깨짐) | 4개 실패 (#3, #4 포함) |

모두 원래 코드로 되돌리고 diff로 확인했습니다.

### 테스트를 만들면서 바로잡은 것 (구현 결함 아님)
- JDK `HttpURLConnection`은 고정 길이 스트리밍 모드에서 **401 응답의 본문을 주지 않습니다.** 상태 코드는 제대로 반환되고 본문은 부가 정보이므로, 이 테스트에서 401의 본문은 검사하지 않도록 고쳤습니다.
- "배치 한도 복구" 테스트는 남은 2건이 `flushAt`(20)보다 적어서 설계대로 다음 trigger를 기다렸습니다. 테스트에서 `flushAt = 1`로 고쳤습니다.

## 서버 멱등성 (#6, 설계 §6.5)
- SDK는 같은 이벤트를 **항상 같은 `messageId`**로 보냅니다. 다음 경우 같은 `messageId`가 두 번 이상 도착할 수 있습니다.
  - 응답 유실
  - 2xx 뒤 삭제 전 종료
  - 2xx 뒤 로컬 삭제 실패
  - 일부만 저장한 서버가 `5xx` 응답
- **서버가 `messageId` 중복 제거를 지원하지 않으면, 이 경우들마다 이벤트가 중복 수집됩니다.** SDK는 이를 막거나 보정할 수 없습니다.
- 반복 `identify`는 `messageId`가 서로 다르므로 재전송 중복이 아니며, 계약 5(`(anonymousId, userId)` 멱등)로 다룹니다.
- 실제 운영 서버가 계약을 지키는지는 SDK가 검증할 수 없습니다 (A-1). 6단계 Mock Server는 계약 4와 5를 구현해서 시나리오를 테스트합니다.

## 공개 API와 의존성
- 공개 패키지 클래스 9개는 그대로입니다. Java 공개 시그니처는 의도한 대로 모두 `static`입니다: `initialize(Context, Configuration)`, `track(String[, Map])`, `screen(String[, Map])`, `identify(String[, IdentityCallback])`, `reset([IdentityCallback])`, `flush()`
- 릴리스 런타임 의존성은 그대로 `kotlin-stdlib`, `kotlinx-coroutines-core`입니다. `mockwebserver 4.12.0`은 **테스트 의존성**이라 배포물에 포함되지 않습니다.
- AAR manifest에 `INTERNET` 권한이 포함되었습니다.

## 승인이 필요한 사항
| ID | 내용 | 현재 (임시) 처리 |
|---|---|---|
| D5-1 | **설계 §6.3 표에 없는 응답**: 1xx, 3xx, 그 밖의 4xx(404, 405, 409 등) | 유지 + 최대 backoff(5분) + `Log.e`. 아무것도 삭제하지 않음 (401/403과 같은 보수적 처리). 잘못된 endpoint(404)에서 데이터를 잃지 않는 대신, 그동안 상한 정리로만 해소됨 |
| D5-2 | 리다이렉트를 따라가지 않음 (3xx → D5-1) | POST 본문을 다른 곳으로 다시 보내지 않기 위함 |
| D5-3 | 400/422는 6단계 전까지 D5-1과 같이 처리 | 6단계에서 단건 격리(R-8'')로 대체 |
| D5-4 | 라이브러리 manifest에 `INTERNET` 권한 선언 | 전송에 필수. 설계 §10에 추가함 |
| D5-5 | 2xx 뒤 로컬 삭제 실패 시 backoff 후 재전송 | 설계 §8.2("다시 전송됨")를 따르되, 곧바로 재전송해서 서버를 반복 호출하지 않도록 backoff를 더함 |

## 남은 위험
| ID | 위험 | 대응 |
|---|---|---|
| S5-1 | MockWebServer는 http로 테스트함. 실제 TLS 경로는 검증하지 않음 | 7단계 샘플 앱에서 https endpoint로 확인 (필요하면 MockWebServer TLS) |
| S5-2 | Android의 `HttpURLConnection`(OkHttp 기반)은 JDK 구현과 세부 동작(401 본문 등)이 다를 수 있음 | 상태 코드 기반으로만 판단하도록 설계함. 7단계 기기 테스트로 확인 |
| S5-3 | 앱이 백그라운드로 가거나 프로세스가 종료되면 진행 중인 요청이 끊김 (설계 Q22에서 백그라운드 전송 제외) | at-least-once로 다음 실행에서 재전송. 문서화되어 있음 |
| S5-4 | 401/403/404처럼 오류가 계속되는 동안 이벤트가 쌓여 상한 정리로 유실될 수 있음 (L-9) | 문서화되어 있음. `Log.e`로 드러남 |
