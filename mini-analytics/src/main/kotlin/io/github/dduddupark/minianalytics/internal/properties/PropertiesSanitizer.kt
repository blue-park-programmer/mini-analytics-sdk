package io.github.dduddupark.minianalytics.internal.properties

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 호스트 앱이 넘긴 properties를 검증하고 깊은 복사한다 (설계 §7.3, R-9').
 *
 * - 호출 스레드(lock 밖)에서 실행한다. **예외를 던지지 않는다.**
 * - 값을 잘라내지 않는다. 규칙에 맞으면 그대로 담고, 맞지 않으면 그 항목만 뺀다.
 * - 노드 예산을 넘거나 순회 중 예외가 나면 properties 전체를 비운다.
 * - 결과는 SDK가 소유한 수정 불가능한 `Map`/`List`/원시값이다. 호출 뒤에 호스트가
 *   원본을 바꿔도 결과에 영향이 없다.
 */
internal object PropertiesSanitizer {

    fun sanitize(input: Map<*, *>?): SanitizedProperties {
        if (input == null) return SanitizedProperties.EMPTY
        val session = Session()
        return try {
            session.enter(input)
            val values = session.copyMap(input, depth = 1, path = "")
            SanitizedProperties(
                values = values,
                excludedCount = session.excludedCount,
                excludedSamples = session.excludedSamples.toList(),
                dropReason = null,
            )
        } catch (e: NodeLimitExceeded) {
            SanitizedProperties.dropped(DropReason.NODE_LIMIT_EXCEEDED)
        } catch (e: Exception) {
            // ConcurrentModificationException, 호스트가 구현한 Map의 예외 등
            SanitizedProperties.dropped(DropReason.TRAVERSAL_FAILED)
        }
    }

    private object Excluded

    private class NodeLimitExceeded : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Session {
        private var nodes = 0
        private val visiting: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        var excludedCount = 0
        val excludedSamples = mutableListOf<String>()

        fun enter(container: Any) {
            visiting.add(container)
        }

        fun copyMap(map: Map<*, *>, depth: Int, path: String): Map<String, Any?> {
            val copy = LinkedHashMap<String, Any?>()
            for ((key, value) in map.entries) {
                countNode()
                if (key !is String) {
                    exclude(childPath(path, key.toString()), ExclusionReason.NON_STRING_KEY)
                    continue
                }
                val keyPath = childPath(path, key)
                if (key.isEmpty() || key.length > PropertyLimits.MAX_KEY_LENGTH) {
                    exclude(keyPath, ExclusionReason.INVALID_KEY_LENGTH)
                    continue
                }
                val converted = convert(value, depth, keyPath)
                if (converted !== Excluded) copy[key] = converted
            }
            return Collections.unmodifiableMap(copy)
        }

        private fun copyList(list: List<*>, depth: Int, path: String): List<Any?> {
            val copy = ArrayList<Any?>()
            for ((index, element) in list.withIndex()) {
                countNode()
                val converted = convert(element, depth, "$path[$index]")
                if (converted !== Excluded) copy.add(converted)
            }
            return Collections.unmodifiableList(copy)
        }

        /**
         * @param depth 값을 담고 있는 컨테이너의 깊이. 최상위 properties는 1이다.
         * @return 복사한 값 또는 [Excluded].
         */
        private fun convert(value: Any?, depth: Int, path: String): Any? = when (value) {
            null -> null
            is String ->
                if (value.length <= PropertyLimits.MAX_STRING_LENGTH) value
                else exclude(path, ExclusionReason.STRING_TOO_LONG)
            is Boolean, is Int, is Long -> value
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Double ->
                if (value.isFinite()) value else exclude(path, ExclusionReason.NON_FINITE_NUMBER)
            // Float를 그대로 toDouble()하면 0.1f가 0.10000000149011612가 되므로 10진 표기를 거친다.
            is Float ->
                if (value.isFinite()) value.toString().toDouble()
                else exclude(path, ExclusionReason.NON_FINITE_NUMBER)
            is Map<*, *> -> nested(value, depth, path) { copyMap(value, depth + 1, path) }
            is List<*> -> nested(value, depth, path) { copyList(value, depth + 1, path) }
            else -> exclude(path, ExclusionReason.UNSUPPORTED_TYPE)
        }

        private inline fun nested(container: Any, depth: Int, path: String, copy: () -> Any): Any {
            if (depth + 1 > PropertyLimits.MAX_DEPTH) return exclude(path, ExclusionReason.DEPTH_EXCEEDED)
            if (!visiting.add(container)) return exclude(path, ExclusionReason.CYCLE)
            try {
                return copy()
            } finally {
                visiting.remove(container)
            }
        }

        private fun countNode() {
            nodes++
            if (nodes > PropertyLimits.MAX_NODES) throw NodeLimitExceeded()
        }

        private fun exclude(path: String, reason: ExclusionReason): Any {
            excludedCount++
            if (excludedSamples.size < PropertyLimits.MAX_REPORTED_PATHS) {
                excludedSamples += "$path ($reason)"
            }
            return Excluded
        }

        private fun childPath(parent: String, key: String): String {
            val segment =
                if (key.length > PATH_SEGMENT_MAX) key.take(PATH_SEGMENT_MAX) + "…" else key
            return if (parent.isEmpty()) segment else "$parent.$segment"
        }
    }

    /** 로그에 남기는 경로 조각의 최대 길이. 아주 긴 key가 로그를 채우지 않게 한다. */
    private const val PATH_SEGMENT_MAX = 64
}

internal object PropertyLimits {
    const val MAX_DEPTH: Int = 5
    const val MAX_NODES: Int = 500
    const val MAX_KEY_LENGTH: Int = 256
    const val MAX_STRING_LENGTH: Int = 8_192
    const val MAX_REPORTED_PATHS: Int = 5
}

internal enum class ExclusionReason {
    NON_STRING_KEY,
    INVALID_KEY_LENGTH,
    STRING_TOO_LONG,
    NON_FINITE_NUMBER,
    UNSUPPORTED_TYPE,
    DEPTH_EXCEEDED,
    CYCLE,
}

internal enum class DropReason {
    /** 전체 노드 수가 [PropertyLimits.MAX_NODES]를 넘었다. */
    NODE_LIMIT_EXCEEDED,

    /** 순회 중 예외가 났다 (호스트가 다른 스레드에서 Map을 수정하는 경우 등). */
    TRAVERSAL_FAILED,
}

/**
 * @property values 복사된 properties (수정 불가).
 * @property excludedCount 제외된 항목 수 (전체가 비워진 경우 0).
 * @property excludedSamples 로그용 예시. 최대 [PropertyLimits.MAX_REPORTED_PATHS]개.
 * @property dropReason null이 아니면 properties 전체를 비웠다.
 */
internal class SanitizedProperties(
    val values: Map<String, Any?>,
    val excludedCount: Int,
    val excludedSamples: List<String>,
    val dropReason: DropReason?,
) {
    val isDropped: Boolean get() = dropReason != null
    val isSanitized: Boolean get() = excludedCount > 0

    companion object {
        val EMPTY = SanitizedProperties(emptyMap(), 0, emptyList(), null)

        fun dropped(reason: DropReason) = SanitizedProperties(emptyMap(), 0, emptyList(), reason)
    }
}
