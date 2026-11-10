package io.github.dduddupark.minianalytics.internal.model

import io.github.dduddupark.minianalytics.IdentityCallback

/**
 * 호출 스레드에서 만들어 명령 큐에 넣는 명령 (설계 §4.2, R-1).
 *
 * 호출 시점에 확정해야 하는 값(`messageId`, `timestampMillis`, 복사한 properties,
 * `reset`이 쓸 새 `anonymousId`)은 모두 명령에 담긴다. Identity 스냅샷은 담지 않는다.
 * storage coroutine이 명령 순서대로 Identity를 적용하면서 정한다.
 */
internal sealed class Command {

    /** 전송 메시지를 만드는 명령. */
    sealed class Message : Command() {
        abstract val messageId: String
        abstract val timestampMillis: Long
        abstract val type: MessageType
    }

    data class Track(
        override val messageId: String,
        override val timestampMillis: Long,
        val event: String,
        val properties: Map<String, Any?>,
    ) : Message() {
        override val type: MessageType get() = MessageType.TRACK
    }

    data class Screen(
        override val messageId: String,
        override val timestampMillis: Long,
        val name: String,
        val properties: Map<String, Any?>,
    ) : Message() {
        override val type: MessageType get() = MessageType.SCREEN
    }

    /** Identity 변경과 identify 메시지를 함께 만든다 (같은 트랜잭션, R-1). */
    data class Identify(
        override val messageId: String,
        override val timestampMillis: Long,
        val userId: String,
        val callback: IdentityCallback?,
    ) : Message() {
        override val type: MessageType get() = MessageType.IDENTIFY
    }

    /** 메시지를 만들지 않는다. `newAnonymousId`는 호출 시점에 미리 만든 값이다. */
    data class Reset(
        val newAnonymousId: String,
        val callback: IdentityCallback?,
    ) : Command()
}

/** 전송 본문의 `type` 값이자 DB `events.type` 컬럼 값. */
internal enum class MessageType(val wireName: String) {
    TRACK("track"),
    SCREEN("screen"),
    IDENTIFY("identify"),
    ;

    companion object {
        /** DB에서 읽은 값을 되돌린다. 알 수 없는 값이면 null. */
        fun fromWireName(value: String): MessageType? = entries.firstOrNull { it.wireName == value }
    }
}
