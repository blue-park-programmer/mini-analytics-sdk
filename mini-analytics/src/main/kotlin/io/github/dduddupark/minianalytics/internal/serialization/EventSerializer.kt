package io.github.dduddupark.minianalytics.internal.serialization

import io.github.dduddupark.minianalytics.internal.model.Command
import io.github.dduddupark.minianalytics.internal.model.Identity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 메시지 하나를 `batch[]`의 원소 JSON으로 직렬화한다 (설계 §7.2).
 *
 * storage coroutine이 이벤트를 INSERT할 때 호출하며, 결과 문자열이 DB `events.payload`가 된다.
 * 생성자로 받은 [Iso8601UtcFormatter]를 쓰므로 이 객체도 스레드 안전하지 않다.
 * 트랜잭션 하나 단위로 만들어 쓴다.
 *
 * 예외를 던지지 않는다. 실패는 [SerializationResult]로 돌려준다.
 */
internal class EventSerializer(
    private val timestampFormatter: Iso8601UtcFormatter,
) {
    fun serialize(message: Command.Message, identity: Identity): SerializationResult {
        val json = try {
            toJson(message, identity).toString()
        } catch (e: Exception) {
            return SerializationResult.Invalid(e)
        }
        val sizeBytes = json.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MAX_EVENT_BYTES) return SerializationResult.Oversized(sizeBytes)
        return SerializationResult.Success(json, sizeBytes)
    }

    private fun toJson(message: Command.Message, identity: Identity): JSONObject {
        val json = JSONObject()
            .put("messageId", message.messageId)
            .put("type", message.type.wireName)
        when (message) {
            is Command.Track -> json
                .put("event", message.event)
                .put("properties", mapToJson(message.properties))
            is Command.Screen -> json
                .put("name", message.name)
                .put("properties", mapToJson(message.properties))
            is Command.Identify -> Unit
        }
        // userId가 없을 때도 키를 남겨서 서버가 "익명"을 명시적으로 알 수 있게 한다.
        json.put("userId", identity.userId ?: JSONObject.NULL)
        json.put("anonymousId", identity.anonymousId)
        json.put("timestamp", timestampFormatter.format(message.timestampMillis))
        return json
    }

    /** [io.github.dduddupark.minianalytics.internal.properties.PropertiesSanitizer]를 거친 값만 들어온다고 가정한다. */
    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) json.put(key, valueToJson(value))
        return json
    }

    private fun valueToJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            mapToJson(value as Map<String, Any?>)
        }
        is List<*> -> JSONArray().also { array -> value.forEach { array.put(valueToJson(it)) } }
        is String, is Boolean, is Int, is Long, is Double -> value
        else -> throw IllegalArgumentException("Unsupported property type: ${value.javaClass.name}")
    }

    companion object {
        /** 이벤트 한 건의 직렬화 크기 상한 (UTF-8 바이트, 설계 Q14). */
        const val MAX_EVENT_BYTES: Int = 32 * 1024
    }
}

internal sealed class SerializationResult {
    class Success(val json: String, val sizeBytes: Int) : SerializationResult()

    /** [EventSerializer.MAX_EVENT_BYTES]를 넘었다 (`droppedOversized`). */
    class Oversized(val sizeBytes: Int) : SerializationResult()

    /** 직렬화 중 예외가 났다 (`droppedInvalid`). */
    class Invalid(val cause: Throwable) : SerializationResult()
}
