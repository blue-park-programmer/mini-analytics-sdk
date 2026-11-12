# mini-analytics-sdk

Native Android용 경량 분석(analytics) SDK입니다. 학습·포트폴리오 목적의 프로젝트이며, 실제 호스트 앱에 통합할 수 있는 수준의 안정성을 목표로 설계·구현했습니다.

- 이벤트 수집 → SQLite 영구 저장 → 배치 전송 → 실패 재시도
- 메인 스레드에서 디스크·네트워크 I/O를 하지 않습니다. 공개 API는 예외를 던지지 않습니다.
- 설계 문서: [`docs/design/v1-design.md`](docs/design/v1-design.md) / 공개 API 목록: [`docs/api/public-api.txt`](docs/api/public-api.txt)

> **이 SDK는 수신 서버를 포함하지 않습니다.** 이벤트를 받는 서버는 아래 [서버 계약](#서버-계약)을 구현해야 합니다.

---

## 요구사항

| 항목 | 값 |
|---|---|
| minSdk | 23 |
| 호스트 앱의 Kotlin | **2.1 이상** (2.1.21, 2.2.21에서 검증. 2.0.21에서는 컴파일 실패) |
| 런타임 의존성 | `kotlinx-coroutines-core` 1.10.2 (+ `kotlin-stdlib` 2.2.21) |
| 권한 | `android.permission.INTERNET`. SDK manifest에서 자동으로 병합됨 |
| ProGuard/R8 | 추가 규칙 필요 없음 (R8 release 빌드로 검증) |
| 멀티 프로세스 | 지원하지 않음 (메인 프로세스에서만 사용) |

## 설치

v1은 공개 저장소에 배포하지 않습니다. 로컬 Maven 저장소에 배포한 뒤 사용합니다.

```bash
./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository   # build/local-maven
# 또는 ./gradlew :mini-analytics:publishToMavenLocal
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven { url = uri("<this repo>/build/local-maven") } } }
// app/build.gradle.kts
dependencies { implementation("io.github.dduddupark:mini-analytics:0.1.0-SNAPSHOT") }
```

---

## 사용법

### 초기화
`Application.onCreate()`에서 한 번 호출합니다. 호출은 즉시 반환되고, DB 열기는 백그라운드에서 일어납니다.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val configuration = Configuration.Builder(apiKey = "pk_live_…", endpoint = "https://collector.example.com/v1/batch")
            .flushAt(20)                 // 기본 20: 저장된 이벤트가 이 개수 이상이면 전송
            .flushIntervalMillis(30_000) // 기본 30초: 미전송 이벤트 확인 주기 (최소 1초)
            .maxQueuedEvents(1_000)      // 기본 1,000: 저장할 track/screen 최대 개수 (1..10,000)
            .trustInvalidEventResponses(false) // 기본 false: 아래 "서버 계약" 3번을 서버가 구현했다고 확인했을 때만 true
            .build()
        val result = MiniAnalytics.initialize(this, configuration)
    }
}
```

| `InitializeResult` | 의미 |
|---|---|
| `STARTED` | 이 호출로 비동기 초기화를 **시작**했다 (DB가 준비되었다는 뜻은 아님) |
| `ALREADY_INITIALIZED` | 이미 초기화되어 무시함. 설정이 다르면 바뀐 필드를 `Log.e`로 남김 |
| `INVALID_CONFIGURATION` | `context`/`configuration`이 null이거나 설정이 유효하지 않음 (`Log.e`에 원인). 고쳐서 다시 호출할 수 있음 |

- `apiKey`는 앱에 포함되는 **공개 식별자이며 비밀이 아닙니다.**
- endpoint는 **HTTPS만** 허용합니다.
- `initialize()` 전에 들어온 호출은 버리며, 버퍼링하지 않습니다.

### 이벤트

```kotlin
MiniAnalytics.track("Button Clicked", mapOf("screen" to "home", "count" to 3))
MiniAnalytics.screen("Home")
MiniAnalytics.flush() // 전송 요청 (완료를 보장하지 않음)
```

- 이름: 공백이 아닌 1~256자(UTF-16 code unit)여야 합니다. 어기면 경고 후 무시합니다. 값을 잘라내지 않습니다.
- `properties`에 허용하는 값: `String`, `Boolean`, `Int`, `Long`, `Double`, `Float`(→`Double`), `Byte`/`Short`(→`Int`), `null`, 그리고 이 값들로 된 중첩 `Map<String, *>`/`List<*>`(깊이 5까지)
  - 허용하지 않는 값과 `NaN`/`Infinity`는 **그 항목만** 빠집니다.
  - 항목이 500개를 넘으면 properties 전체를 비우고 이벤트는 보냅니다.
  - key는 1~256자, 문자열 값은 8,192자 이하입니다.
- properties는 호출 시점에 복사합니다. 호출한 뒤 원본 Map을 바꿔도 영향이 없습니다.

### 사용자 식별

```kotlin
MiniAnalytics.identify("user-123") { result -> /* 메인 스레드 */ }
MiniAnalytics.reset { result -> }
```

- `identify(userId)`: 같은 `userId`여도 항상 identify 메시지를 보냅니다. `userId`가 유효하지 않으면(null, 공백뿐, 256자 초과) 아무것도 바꾸지 않고 `Failure(INVALID_ARGUMENT)`를 전달합니다.
- `reset()`: `userId`를 지우고, **이미 익명이어도** 새 `anonymousId`를 발급합니다.
- 콜백
  - `Success`: 변경이 디스크에 저장되었다는 뜻입니다.
  - `Failure(reason, cause)`: `STORAGE_ERROR`, `NOT_INITIALIZED`, `INVALID_ARGUMENT` 중 하나입니다.
  - 메인 스레드에서 호출되며, **프로세스가 살아 있는 동안에만** 정확히 한 번 호출됩니다.
- 사용자를 전환할 때(A → B)는 `reset()` 후 `identify(B)`를 호출하세요. `reset()` 없이 `identify(B)`를 호출하면 A의 `anonymousId`가 B와 연결됩니다.

### 로그아웃: SDK가 로그아웃을 막으면 안 됩니다
```kotlin
fun logout() {
    var done = false
    val proceed = { if (!done) { done = true; clearSession() } }   // 결과와 관계없이 진행
    handler.postDelayed(proceed, 2_000)                           // 타임아웃
    MiniAnalytics.reset { proceed() }                             // Success든 Failure든
}
```
- 로그아웃의 성공 여부를 SDK 결과에 의존시키지 마세요.
- 콜백을 기다린다면 반드시 타임아웃을 두세요. 저장소 장애 시 재시도 때문에 늦어질 수 있고, 프로세스가 종료되면 오지 않을 수 있습니다.
- 메인 스레드에서 블로킹으로 기다리지 마세요(`CountDownLatch.await` 등). 콜백도 메인 스레드로 오므로 교착 상태가 됩니다.

### Java
```java
MiniAnalytics.track("Button Clicked");
MiniAnalytics.identify("user-123", result -> { });
new Configuration.Builder(apiKey, endpoint).flushAt(10).build();
```
Java에서 `null`을 넘겨도 NPE가 나지 않습니다. 결과는 "무시 + 경고" 또는 `INVALID_ARGUMENT`입니다.

---

## 전송 정책

| 항목 | 동작 |
|---|---|
| trigger | 저장 행 수 ≥ `flushAt`, `flushIntervalMillis` 주기, `flush()`, 앱 시작 시 1회(이전 실행에서 남은 이벤트) |
| 동시성 | 요청은 항상 한 번에 하나 (`flush()`를 여러 번 불러도 하나로 합쳐짐) |
| 배치 | 최대 100건 / 512KB, 저장 순서대로 |
| 삭제 | 서버가 처리 완료(2xx)를 확인한 배치만 삭제 |
| 보장 수준 | **at-least-once**. 같은 이벤트는 항상 같은 `messageId`로 재전송 |
| 백그라운드 전송 | 별도 작업을 예약하지 않음. 프로세스가 실행 중일 때만 전송 (아래 "백그라운드 전송 정책") |

### 실패 정책
| 응답 | 처리 |
|---|---|
| 2xx | 배치 삭제 (본문의 `rejected` 목록은 집계만) |
| 네트워크 오류, 408, 429, 5xx | 유지. 지수 backoff + full jitter (1초 → 최대 5분). `Retry-After`는 5분 상한 안에서 우선. 재시도 횟수 제한 없음 |
| 413 | 배치 크기를 절반으로 줄임. 1건도 413이면 그 이벤트만 삭제 |
| 401, 403 | 유지. 5분 간격으로 재시도, `Log.e` |
| 400, 422 (`trustInvalidEventResponses = false`, **기본값**) | 격리도 삭제도 하지 않음. 유지하고 5분 간격으로 재시도, `Log.e` |
| 400, 422 (`trustInvalidEventResponses = true`) | 단건 격리: 배치의 이벤트를 한 건씩 다시 보내서, 서버가 `invalid_event`로 지목한 이벤트만 삭제 (프로세스당 최대 20건). 근거 없는 응답이면 보존하고 5분 대기. **격리 한 번에 단건 요청은 최대 20번**(재시도 포함). 넘으면 남은 이벤트는 보존하고 5분 뒤 이어서 진행 |
| 그 밖의 응답 (3xx, 404 등) | 유지. 5분 간격, `Log.e`. 리다이렉트는 따라가지 않음 |
| backoff 중 | 어떤 trigger도 다음 시도를 앞당기지 않음 |
| 로그 | 같은 종류의 실패는 처음 한 번과 그 뒤 1시간마다 한 번 (생략 횟수 표시) |

### 백그라운드 전송 정책 (v1)
v1은 **앱 프로세스가 실행 중일 때만** 전송하고 재시도합니다. WorkManager, `JobScheduler`, `AlarmManager`, foreground service를 사용하지 않습니다.

- **재시도 타이머는 프로세스 안에만 있습니다.** backoff 대기는 SDK 내부의 코루틴 타이머입니다. 프로세스가 실행되지 않으면 타이머도 실행되지 않습니다.
- **프로세스가 종료된 경우**(사용자의 강제 종료, 저메모리 종료, 백그라운드 프로세스 정리 등): 남은 재시도는 사라집니다. 이벤트는 SQLite에 남아 있으므로 **다음에 앱이 실행되어 `initialize()`가 호출될 때** 같은 `messageId`로 전송됩니다.
- **Android가 프로세스를 freeze한 경우**(최근 Android 버전의 cached app freezer 등. 백그라운드로 간 앱은 수십 초 안에 freeze될 수 있으며, 적용 여부와 시점은 OS 버전과 제조사에 따라 다름): 프로세스는 살아 있지만 코드가 실행되지 않으므로, 재시도 타이머가 freeze가 풀릴 때까지 실행되지 않습니다. 앱이 다시 실행되거나 전면으로 오면 이어서 전송합니다. 즉시 전송된다고 보장하지는 않습니다. 실기기(Galaxy S23)에서는 freeze가 풀린 직후의 첫 시도가 `ConnectException`으로 실패했고, 그 뒤 backoff(연속 실패 수에 따라 최대 5분) 때문에 재실행 후 8~60초 뒤에 전달되었습니다. **이 첫 시도가 실패한 원인은 확정하지 않았습니다.** 에뮬레이터에서는 이 실패가 관찰되지 않았고, freeze가 풀린 뒤 1초 안에 전달되었습니다.
- **Doze와 앱 대기 버킷**: Doze 중에는 앱의 네트워크 접근이 막혀 전송이 실패하고 backoff로 이어집니다. Doze가 풀린 뒤에도 프로세스가 freeze되어 있으면 위와 같이 다음 실행까지 늦어집니다.
- 이 경우들에서 **이벤트는 유실되지 않습니다.** 대신 전달이 다음 실행까지 늦어질 수 있습니다. 앱이 오래 실행되지 않으면 그만큼 늦어지고, 그 사이 저장 상한(`maxQueuedEvents`)을 넘으면 가장 오래된 이벤트부터 버려집니다.
- 실기기(Galaxy S23, Android 16) 관찰: Doze 해제 30초 뒤 백그라운드 sample 프로세스가 freeze되었고, 도구가 기다린 약 6분 동안 재시도가 없다가 앱을 다시 실행하자 8~60초 뒤 같은 `messageId`로 전달되었습니다 (`docs/design/v1-physical-device-report.md` 10.4, 11장).

**WorkManager를 쓰지 않는 이유**: v1은 런타임 의존성을 `kotlin-stdlib`와 `kotlinx-coroutines-core`로 제한합니다. WorkManager를 넣으면 호스트 앱에 androidx 의존성과 초기화(App Startup), 작업 예약 정책이 추가되고, 백그라운드 실행 제한과 배터리 정책도 SDK가 책임져야 합니다. 백그라운드 전달이 꼭 필요하다면 호스트 앱이 자기 작업(WorkManager 등) 안에서 `MiniAnalytics.flush()`를 호출할 수 있습니다. 단, 그 작업이 실행되는 동안 backoff 대기 중이면 `flush()`도 다음 시도를 앞당기지 않습니다(위 "backoff 중").

### 이벤트가 유실될 수 있는 경우
- `initialize()` 전에 호출한 경우
- 호출한 뒤 SQLite에 저장되기 전(보통 수 ms)에 프로세스가 종료된 경우
- 메모리 큐(1,000건)나 저장 상한(`maxQueuedEvents`, identify는 100건)을 넘은 경우 (가장 오래된 것부터)
- 32KB를 넘는 이벤트
- 서버가 영구 거부한 경우
- 저장소 장애가 계속되어 SDK가 비활성화된 경우: 그 프로세스 동안 모든 호출이 무시됨
- DB 파일이 손상된 경우 (새로 만들어지며 `anonymousId`도 새로 발급)
- 인증 오류 등으로 전송이 오래 멈춰서 저장 상한을 넘은 경우
- 앱 삭제, 데이터 삭제

### Identity 불일치 (자동 복구하지 않음)
`reset()`이 저장되기 전에 프로세스가 종료되거나 저장에 실패하면, 다음 실행에서 이전 사용자의 Identity가 복원될 수 있습니다. 위 로그아웃 패턴을 따르면 가능성이 줄어들지만 0이 되지는 않습니다.

---

## 서버 계약
SDK와 수신 서버 사이의 **명시적 계약**입니다. 일반적인 HTTP 의미가 아닙니다.

요청: `POST {endpoint}`, `Authorization: Bearer {apiKey}`, `Content-Type: application/json`
```json
{ "sentAt": "…Z", "context": { "library": {…}, "os": {…}, "app": {…} },
  "batch": [ { "messageId": "uuid", "type": "track|screen|identify", "event": "…", "name": "…",
               "properties": {…}, "userId": null, "anonymousId": "uuid", "timestamp": "…Z" } ] }
```

1. **2xx = 배치 전체가 처리 완료**되었다는 뜻입니다(저장, 중복, 영구 거부 중 하나). 부분 거부는 본문의 `{"rejected":[{"messageId","reason"}]}`로 알립니다.
2. **일부만 저장한 뒤 실패**했다면 2xx를 보내지 말고 5xx를 보내세요. SDK가 배치 전체를 재전송합니다.
3. `400`/`422`는 요청 전체를 해석할 수 없을 때 씁니다. 한 건짜리 요청의 이벤트 자체가 문제라면 `{"error":{"code":"invalid_event","messageId":"<그 id>"}}`를 보내세요. **SDK는 호스트 앱이 `trustInvalidEventResponses(true)`를 설정한 경우에만 이 응답으로 이벤트를 삭제합니다.**
4. **`messageId`로 중복을 제거해야 합니다.** 응답 유실, 2xx 후 삭제 전 종료, 일부 저장 + 5xx 상황에서 같은 `messageId`가 다시 도착합니다. **중복 제거를 하지 않으면 이벤트가 중복 수집됩니다.**
5. identify는 `(anonymousId, userId)` 쌍에 대해 멱등하게 처리하세요.

- **SDK가 감지할 수 없는 위반**: 일부만 저장해 놓고 `rejected` 없이 2xx를 보내면, 저장되지 않은 이벤트는 조용히 유실됩니다.
- **서버 부하**: `trustInvalidEventResponses = true`에서 서버가 계약 1을 어기고 배치에 400을 주면, 기기마다 5분에 최대 21번(배치 1 + 단건 20) 요청합니다. 이 상한은 기기 하나의 요청 수를 제한할 뿐, **많은 기기가 동시에 이 상황에 놓였을 때의 전체 서버 부하를 막지는 못합니다.**
- **시각**: `timestamp`는 기기 시계 기준이며 순서의 기준이 아닙니다. 서로 다른 스레드의 동시 호출은 저장 순서와 `timestamp` 순서가 다를 수 있습니다.

---

## 안정성 원칙
- 공개 함수는 예외를 던지지 않습니다. 내부 `Exception`은 로그로 남기고 무시합니다. `OutOfMemoryError` 같은 `Error`는 삼키지 않습니다(설계 §8.4).
- 저장소 장애 시 1초, 5초 뒤 재시도하고, 그래도 실패하면 그 프로세스 동안 비활성화합니다. 비활성 상태에서는 `identify`/`reset` 콜백이 즉시 `STORAGE_ERROR`를 받습니다.
- `io.github.dduddupark.minianalytics.internal` 패키지는 공개 API가 아닙니다. Kotlin `internal`은 Java에서 보일 수 있지만 사용하지 마세요.

## 검증 범위
- JVM 단위 테스트, 에뮬레이터(API 23·33·36)의 instrumented test와 sample 앱 시나리오(debug, R8 release)로 검증했습니다.
- 물리 기기 1대(Galaxy S23, Android 16 / API 36)에서 기본 시나리오와 비행기 모드, Wi-Fi↔LTE 전환, 백그라운드 전환·종료, Doze 시나리오를 검증했습니다(debug, R8 release). Doze는 해제 후 프로세스 freeze 때문에 다음 실행에서 전달된 경우를 포함하며, 판정과 한계는 `docs/design/v1-physical-device-report.md`에 있습니다.
- **검증하지 않은 것**: 다른 제조사·Android 버전의 물리 기기, API 24~32·34~35, 실제 운영 서버, 공인 CA 인증서·핀닝·프록시, 손실·지연이 큰 실제 모바일 네트워크, 자연 발생 Doze(강제 진입만 검증), 저장 지연과 처리량 측정. 자세한 목록은 `docs/design/v1-final-report.md`에 있습니다.

## 개발
```bash
./gradlew :mini-analytics:testDebugUnitTest            # JVM 단위 테스트 (JUnit + Robolectric)
./gradlew :mini-analytics:check                        # 단위 테스트 + lint + 공개 API 검증
./gradlew :mini-analytics:connectedDebugAndroidTest    # 에뮬레이터/기기의 SQLite 테스트
./gradlew :mini-analytics:publishReleasePublicationToLocalBuildRepository
./gradlew :sample:assembleDebug :sample:assembleRelease --refresh-dependencies   # 배포와 따로 실행
python3 tools/device-verification/verify.py --serial emulator-5554 --variant debug --variant release
```
- **공개 API 검증**: `checkPublicApi`(`check`에 포함)는 release AAR의 공개 JVM 시그니처를 [`docs/api/public-api.txt`](docs/api/public-api.txt)와 비교하고, 다르면 빌드를 실패시킵니다. 의도한 변경이라면 `./gradlew :mini-analytics:updatePublicApi`로 기준 파일을 갱신하고 diff를 리뷰하세요.
- **배포와 sample 빌드를 한 번의 Gradle 실행에서 함께 하면**, sample이 이전 스냅샷으로 컴파일될 수 있습니다. 위처럼 나눠서 실행하세요.
`tools/device-verification/verify.py`는 로컬 HTTPS 계약 서버(테스트 전용)를 띄우고, adb로 `:sample` 앱의 시나리오를 실행합니다. 시나리오에는 TLS, StrictMode, 프로세스 종료 후 복원, 리다이렉트, 단건 격리가 포함됩니다. 물리 기기 사용법은 `verify.py` 머리말과 `docs/design/v1-physical-device-report.md`에 있습니다.
- **sample의 테스트 인증서 신뢰 범위**: sample 앱은 `-PsampleEndpoint`(기본 `https://10.0.2.2:8443/v1/batch`)의 **호스트 하나에만** 테스트 CA를 신뢰합니다(API 24 이상은 빌드 때 생성되는 network security config, API 23은 호스트별 `SSLSocketFactory`). https가 아니거나 loopback·사설 IPv4가 아닌 주소는 빌드가 실패합니다. 이것은 sample 전용이며, SDK는 인증서 검증을 바꾸지 않고 플랫폼 기본 `HttpsURLConnection` 설정을 따릅니다.
