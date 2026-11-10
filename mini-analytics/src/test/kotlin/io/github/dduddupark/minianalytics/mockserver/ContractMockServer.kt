package io.github.dduddupark.minianalytics.mockserver

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 설계 §6.5 서버 계약을 구현한 **테스트 전용** 수신 서버 (MockWebServer 기반).
 *
 * 운영 서버 구현이 아니다. SDK가 계약에 맞게 동작하는지, 그리고 계약을 지키지 않는 서버를 만났을 때
 * 데이터를 잃지 않는지 확인하기 위한 것이다.
 *
 * 이벤트의 `properties`에 `"__invalid"` key가 있으면 "이벤트 자체가 잘못된 경우"로 취급한다.
 */
internal class ContractMockServer(
    private val mode: Mode = Mode.CONTRACT,
    private val apiKey: String = "pk_test",
    /** false면 `messageId` 중복 제거를 하지 않는다 (계약 4를 지키지 않는 서버). */
    private val deduplicate: Boolean = true,
) {
    enum class Mode {
        /** 계약을 지키는 서버: 잘못된 이벤트는 2xx + `rejected`로 알린다 (계약 1). */
        CONTRACT,

        /**
         * 방어적 시나리오: 잘못된 이벤트가 섞인 **여러 건** 요청은 400(코드 없음)으로 거부하고,
         * **한 건** 요청이면 `invalid_event` + messageId를 담은 400을 돌려준다 (계약 3). 단건 격리를 검증한다.
         */
        BATCH_400,

        /** 고장 난 서버: 모든 요청에 HTML 400. 계약 응답이 없다. */
        BROKEN_400,
    }

    /** 다음 요청에 한 번 적용할 장애. */
    enum class Fault {
        /** 이벤트를 저장한 뒤 응답 없이 연결을 끊는다 (응답 유실). */
        STORE_THEN_DROP_RESPONSE,

        /** 배치의 앞 절반만 저장하고 503을 돌려준다 (계약 2: 일부만 저장했다면 5xx). */
        STORE_HALF_THEN_503,
    }

    val server = MockWebServer()

    /** 저장된 이벤트 (`deduplicate`면 messageId당 하나). */
    val stored = CopyOnWriteArrayList<JSONObject>()
    private val storedIds = ConcurrentHashMap.newKeySet<String>()

    /** 받은 모든 messageId (재전송 중복 포함). */
    val received = CopyOnWriteArrayList<String>()

    /** identify로 반영된 연결 `(anonymousId, userId)`. 같은 쌍은 한 번만 반영된다 (계약 5). */
    val links = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    val identifyMessages = AtomicInteger()

    val requestCount: Int get() = server.requestCount
    private val faults = ConcurrentLinkedQueue<Fault>()

    fun injectFault(fault: Fault) {
        faults += fault
    }

    fun url(): String = server.url("/v1/batch").toString()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    fun shutdown() = server.shutdown()

    fun storedIds(): List<String> = stored.map { it.getString("messageId") }

    private fun handle(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != "Bearer $apiKey") return json(401, "{\"error\":{\"code\":\"unauthorized\"}}")
        if (mode == Mode.BROKEN_400) return MockResponse().setResponseCode(400).setBody("<html>Bad Request</html>")

        val batch: JSONArray = try {
            JSONObject(request.body.readUtf8()).getJSONArray("batch")
        } catch (e: Exception) {
            return json(400, "{\"error\":{\"code\":\"malformed_request\"}}") // 요청 전체를 해석할 수 없음 (계약 3)
        }
        val events = (0 until batch.length()).map { batch.getJSONObject(it) }
        events.forEach { received += it.getString("messageId") }
        val invalid = events.filter { it.optJSONObject("properties")?.has("__invalid") == true }

        if (mode == Mode.BATCH_400 && invalid.isNotEmpty()) {
            return if (events.size > 1) {
                json(400, "{\"error\":{\"code\":\"invalid_request\"}}")
            } else {
                json(400, "{\"error\":{\"code\":\"invalid_event\",\"messageId\":\"${events.single().getString("messageId")}\"}}")
            }
        }

        val valid = events - invalid.toSet()
        when (faults.poll()) {
            Fault.STORE_THEN_DROP_RESPONSE -> {
                valid.forEach(::store)
                return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
            Fault.STORE_HALF_THEN_503 -> {
                valid.take(valid.size / 2).forEach(::store)
                return json(503, "{\"error\":{\"code\":\"partial_failure\"}}")
            }
            null -> Unit
        }
        valid.forEach(::store)
        val rejected = JSONArray()
        invalid.forEach { rejected.put(JSONObject().put("messageId", it.getString("messageId")).put("reason", "invalid")) }
        return json(200, JSONObject().put("rejected", rejected).toString())
    }

    private fun store(event: JSONObject) {
        val messageId = event.getString("messageId")
        if (deduplicate && !storedIds.add(messageId)) return // 계약 4: 같은 messageId는 한 번만
        stored += event
        if (event.getString("type") == "identify") {
            identifyMessages.incrementAndGet()
            links += event.getString("anonymousId") to event.getString("userId") // 계약 5: 쌍 단위 멱등
        }
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
