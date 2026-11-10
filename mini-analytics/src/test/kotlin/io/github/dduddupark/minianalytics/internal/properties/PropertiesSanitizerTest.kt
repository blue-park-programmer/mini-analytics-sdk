package io.github.dduddupark.minianalytics.internal.properties

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.util.Date

/** 설계 §7.3 (R-9')의 규칙을 하나씩 검증한다. 순수 Kotlin이므로 일반 JUnit으로 실행한다. */
class PropertiesSanitizerTest {

    private fun sanitize(input: Map<*, *>?) = PropertiesSanitizer.sanitize(input)

    // ---------- 타입 처리 ----------

    @Test
    fun `allowed primitive types are kept as is`() {
        val input = mapOf(
            "s" to "text", "b" to true, "i" to 1, "l" to Long.MAX_VALUE, "d" to 1.5, "n" to null,
        )
        val result = sanitize(input)
        assertEquals(input, result.values)
        assertFalse(result.isSanitized)
        assertFalse(result.isDropped)
    }

    @Test
    fun `float is converted to double through its decimal representation`() {
        val result = sanitize(mapOf("f" to 0.1f))
        val value = result.values["f"]
        assertTrue(value is Double)
        assertEquals(0.1, value as Double, 0.0) // 0.10000000149011612가 아님
    }

    @Test
    fun `byte and short are widened to int`() {
        val result = sanitize(mapOf("byte" to 7.toByte(), "short" to 300.toShort()))
        assertEquals(7, result.values["byte"])
        assertEquals(300, result.values["short"])
        assertTrue(result.values["byte"] is Int)
        assertTrue(result.values["short"] is Int)
    }

    @Test
    fun `non finite numbers are excluded`() {
        val result = sanitize(
            mapOf(
                "nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY,
                "fnan" to Float.NaN, "ninf" to Float.NEGATIVE_INFINITY, "ok" to 1.0,
            ),
        )
        assertEquals(mapOf("ok" to 1.0), result.values)
        assertEquals(4, result.excludedCount)
    }

    @Test
    fun `unsupported types are excluded and the rest is kept`() {
        val result = sanitize(
            mapOf(
                "array" to arrayOf(1, 2), "set" to setOf(1), "char" to 'c',
                "decimal" to BigDecimal.ONE, "enum" to Thread.State.NEW, "date" to Date(0),
                "kept" to "yes",
            ),
        )
        assertEquals(mapOf("kept" to "yes"), result.values)
        assertEquals(6, result.excludedCount)
        assertTrue(result.excludedSamples.all { it.contains("UNSUPPORTED_TYPE") })
    }

    @Test
    fun `nested maps and lists are deep copied`() {
        val input = mapOf("user" to mapOf("tags" to listOf("a", "b"), "age" to 3))
        assertEquals(input, sanitize(input).values)
    }

    @Test
    fun `excluded list elements are removed and the list keeps the remaining order`() {
        val result = sanitize(mapOf("list" to listOf(1, Double.NaN, 3, Any())))
        assertEquals(listOf(1, 3), result.values["list"])
        assertEquals(2, result.excludedCount)
        assertTrue(result.excludedSamples.any { it.startsWith("list[1]") })
    }

    @Test
    fun `null input becomes empty properties`() {
        val result = sanitize(null)
        assertTrue(result.values.isEmpty())
        assertFalse(result.isDropped)
    }

    // ---------- key ----------

    @Test
    fun `non string keys are excluded`() {
        val input: Map<Any?, Any?> = linkedMapOf(1 to "int key", null to "null key", "ok" to 1)
        val result = sanitize(input)
        assertEquals(mapOf("ok" to 1), result.values)
        assertEquals(2, result.excludedCount)
    }

    @Test
    fun `key length must be between 1 and 256`() {
        val max = "k".repeat(PropertyLimits.MAX_KEY_LENGTH)
        val tooLong = "k".repeat(PropertyLimits.MAX_KEY_LENGTH + 1)
        val result = sanitize(mapOf(max to 1, tooLong to 2, "" to 3))
        assertEquals(mapOf(max to 1), result.values)
        assertEquals(2, result.excludedCount)
    }

    // ---------- 문자열 길이 ----------

    @Test
    fun `string value up to 8192 chars is kept and longer is excluded without truncation`() {
        val max = "x".repeat(PropertyLimits.MAX_STRING_LENGTH)
        val tooLong = "x".repeat(PropertyLimits.MAX_STRING_LENGTH + 1)
        val result = sanitize(mapOf("max" to max, "long" to tooLong))
        assertEquals(mapOf("max" to max), result.values)
        assertEquals(1, result.excludedCount)
    }

    // ---------- 깊이 ----------

    /** depth 1(최상위) 안에 `levels`개의 Map이 중첩된 구조를 만든다. */
    private fun nestedMaps(levels: Int): Map<String, Any?> {
        var current: Map<String, Any?> = mapOf("leaf" to 1)
        repeat(levels - 1) { current = mapOf("child" to current) }
        return mapOf("root" to current)
    }

    @Test
    fun `containers up to depth 5 are kept`() {
        // 최상위(1) + 중첩 4단계 = 깊이 5
        val input = nestedMaps(levels = 4)
        val result = sanitize(input)
        assertEquals(input, result.values)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun `container at depth 6 is excluded`() {
        val result = sanitize(nestedMaps(levels = 5))
        assertEquals(1, result.excludedCount)
        assertTrue(result.excludedSamples.single().contains("DEPTH_EXCEEDED"))
        // 깊이 5까지는 남고, 6번째 컨테이너만 빠진다.
        var node: Any? = result.values["root"]
        repeat(3) { node = (node as Map<*, *>)["child"] }
        assertEquals(emptyMap<String, Any?>(), node)
    }

    // ---------- 순환 참조 ----------

    @Test
    fun `self referencing map is excluded as cycle`() {
        val map = LinkedHashMap<String, Any?>()
        map["name"] = "loop"
        map["self"] = map
        val result = sanitize(map)
        assertEquals(mapOf("name" to "loop"), result.values)
        assertTrue(result.excludedSamples.single().contains("CYCLE"))
    }

    @Test
    fun `indirect cycle through a list is excluded`() {
        val list = ArrayList<Any?>()
        val map = linkedMapOf<String, Any?>("list" to list)
        list.add("a")
        list.add(map)
        val result = sanitize(map)
        assertEquals(mapOf("list" to listOf("a")), result.values)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `the same object referenced twice without a cycle is copied twice`() {
        val shared = listOf(1, 2)
        val result = sanitize(mapOf("a" to shared, "b" to shared))
        assertEquals(mapOf("a" to shared, "b" to shared), result.values)
        assertEquals(0, result.excludedCount)
    }

    // ---------- 노드 예산 ----------

    @Test
    fun `exactly 500 nodes are allowed`() {
        val input = (1..PropertyLimits.MAX_NODES).associate { "k$it" to it }
        val result = sanitize(input)
        assertEquals(PropertyLimits.MAX_NODES, result.values.size)
        assertFalse(result.isDropped)
    }

    @Test
    fun `nested elements count toward the node budget`() {
        // 최상위 key 1개 + list 원소 499개 = 500
        val ok = sanitize(mapOf("list" to List(PropertyLimits.MAX_NODES - 1) { it }))
        assertFalse(ok.isDropped)
        val over = sanitize(mapOf("list" to List(PropertyLimits.MAX_NODES) { it }))
        assertEquals(DropReason.NODE_LIMIT_EXCEEDED, over.dropReason)
    }

    @Test
    fun `exceeding the node budget drops all properties`() {
        val input = (1..PropertyLimits.MAX_NODES + 1).associate { "k$it" to it }
        val result = sanitize(input)
        assertTrue(result.isDropped)
        assertEquals(DropReason.NODE_LIMIT_EXCEEDED, result.dropReason)
        assertTrue(result.values.isEmpty())
    }

    @Test
    fun `excluded entries also count toward the node budget`() {
        val input = (1..PropertyLimits.MAX_NODES + 1).associate { "k$it" to Any() }
        assertEquals(DropReason.NODE_LIMIT_EXCEEDED, sanitize(input).dropReason)
    }

    // ---------- 노드 예산: 모든 입력 경로에서 실제로 적용되는지 ----------
    // 호스트 컬렉션에서 실제로 꺼낸 원소 수를 세어서, 입력이 아무리 커도 일의 양이 상한을 넘지 않음을 확인한다.

    @Test
    fun `huge top level map is not traversed beyond the budget`() {
        val map = CountingMap(100_000)
        val result = sanitize(map)
        assertEquals(DropReason.NODE_LIMIT_EXCEEDED, result.dropReason)
        assertTrue("pulled=${map.pulled}", map.pulled <= PropertyLimits.MAX_NODES + 1)
    }

    @Test
    fun `huge nested list is not traversed beyond the budget`() {
        val list = CountingList(100_000)
        val result = sanitize(mapOf("a" to mapOf("b" to list)))
        assertEquals(DropReason.NODE_LIMIT_EXCEEDED, result.dropReason)
        // 최상위 key 1 + 중첩 key 1 + list 원소 → 원소는 최대 499개까지만 꺼낸다
        assertTrue("pulled=${list.pulled}", list.pulled <= PropertyLimits.MAX_NODES - 1)
    }

    @Test
    fun `budget is shared across sibling containers`() {
        val first = CountingList(300)
        val second = CountingList(300)
        val result = sanitize(linkedMapOf("first" to first, "second" to second))
        assertEquals(DropReason.NODE_LIMIT_EXCEEDED, result.dropReason)
        assertEquals(300, first.pulled)
        assertTrue("pulled=${second.pulled}", second.pulled <= PropertyLimits.MAX_NODES - 2 - 300 + 1)
    }

    @Test
    fun `entries with non string or invalid keys still consume the budget`() {
        val input = LinkedHashMap<Any?, Any?>()
        repeat(PropertyLimits.MAX_NODES) { input[it] = "int key" }
        input["valid"] = 1
        assertEquals(DropReason.NODE_LIMIT_EXCEEDED, sanitize(input).dropReason)
    }

    @Test
    fun `containers excluded by depth are never traversed`() {
        val tooDeep = CountingMap(10)
        var nested: Map<String, Any?> = mapOf("deep" to tooDeep)
        repeat(4) { nested = mapOf("child" to nested) }
        val result = sanitize(nested)
        assertEquals(1, result.excludedCount)
        assertEquals(0, tooDeep.pulled)
    }

    // ---------- 순회 중 예외 ----------

    @Test
    fun `concurrent modification during traversal drops all properties without throwing`() {
        val result = sanitize(ThrowingMap(ConcurrentModificationException()))
        assertEquals(DropReason.TRAVERSAL_FAILED, result.dropReason)
        assertTrue(result.values.isEmpty())
    }

    @Test
    fun `any exception from a host map implementation is contained`() {
        val result = sanitize(mapOf("nested" to ThrowingMap(IllegalStateException("host bug"))))
        assertEquals(DropReason.TRAVERSAL_FAILED, result.dropReason)
    }

    @Test
    fun `real concurrent mutation from another thread never escapes as an exception`() {
        val shared = HashMap<String, Any?>()
        repeat(200) { shared["k$it"] = it }
        val writer = Thread {
            var i = 0
            while (!Thread.currentThread().isInterrupted) {
                shared["w${i % 50}"] = i
                shared.remove("w${(i + 25) % 50}")
                i++
            }
        }
        writer.start()
        try {
            repeat(2_000) {
                // 결과는 일부 복사, 전체 비움 중 하나일 수 있지만 예외는 나지 않아야 한다.
                sanitize(shared)
            }
        } catch (e: Throwable) {
            fail("sanitize must not throw: $e")
        } finally {
            writer.interrupt()
            writer.join()
        }
    }

    // ---------- 불변성 ----------

    @Test
    fun `mutating the input after sanitize does not affect the result`() {
        val list = mutableListOf<Any?>(1)
        val input = mutableMapOf<String, Any?>("list" to list, "v" to "before")
        val result = sanitize(input)
        input["v"] = "after"
        input["new"] = 1
        list.add(2)
        assertEquals(mapOf("list" to listOf(1), "v" to "before"), result.values)
    }

    @Test
    fun `result collections are unmodifiable`() {
        val result = sanitize(mapOf("list" to listOf(1), "map" to mapOf("a" to 1)))
        assertThrowsUnsupported { (result.values as MutableMap<String, Any?>)["x"] = 1 }
        @Suppress("UNCHECKED_CAST")
        assertThrowsUnsupported { (result.values["list"] as MutableList<Any?>).add(2) }
    }

    @Test
    fun `insertion order is preserved`() {
        val input = linkedMapOf("c" to 1, "a" to 2, "b" to 3)
        assertEquals(listOf("c", "a", "b"), sanitize(input).values.keys.toList())
    }

    // ---------- 관측 ----------

    @Test
    fun `report keeps at most 5 sample paths but counts every exclusion`() {
        val input = (1..8).associate { "bad$it" to Any() }
        val result = sanitize(input)
        assertEquals(8, result.excludedCount)
        assertEquals(PropertyLimits.MAX_REPORTED_PATHS, result.excludedSamples.size)
    }

    @Test
    fun `very long keys are shortened in sample paths`() {
        val result = sanitize(mapOf("k".repeat(1_000) to 1))
        val sample = result.excludedSamples.single()
        assertTrue(sample.length < 100)
    }

    @Test
    fun `nested paths use dots and indexes`() {
        val result = sanitize(mapOf("a" to mapOf("b" to listOf("ok", Any()))))
        assertTrue(result.excludedSamples.single().startsWith("a.b[1]"))
    }

    @Test
    fun `clean input reports nothing`() {
        val result = sanitize(mapOf("a" to 1))
        assertEquals(0, result.excludedCount)
        assertTrue(result.excludedSamples.isEmpty())
        assertNull(result.dropReason)
    }

    private fun assertThrowsUnsupported(block: () -> Unit) {
        try {
            block()
            fail("expected UnsupportedOperationException")
        } catch (expected: UnsupportedOperationException) {
        }
    }

    /** 실제로 꺼낸 entry 수를 세는 크기 [size]의 Map. 원소는 필요할 때 만들어 메모리를 쓰지 않는다. */
    private class CountingMap(private val count: Int) : AbstractMap<String, Any?>() {
        var pulled = 0
        override val entries: Set<Map.Entry<String, Any?>>
            get() = object : AbstractSet<Map.Entry<String, Any?>>() {
                override val size: Int get() = count
                override fun iterator(): Iterator<Map.Entry<String, Any?>> = object : Iterator<Map.Entry<String, Any?>> {
                    var next = 0
                    override fun hasNext() = next < count
                    override fun next(): Map.Entry<String, Any?> {
                        val i = next++
                        pulled++
                        return java.util.AbstractMap.SimpleImmutableEntry("k$i", i)
                    }
                }
            }
    }

    /** 실제로 꺼낸 원소 수를 세는 List. */
    private class CountingList(private val count: Int) : AbstractList<Any?>() {
        var pulled = 0
        override val size: Int get() = count
        override fun get(index: Int): Any? {
            pulled++
            return index
        }
    }

    /** 순회를 시작하면 지정한 예외를 던지는 Map. 호스트 앱의 잘못된 Map 구현이나 동시 수정을 흉내 낸다. */
    private class ThrowingMap(private val error: RuntimeException) : AbstractMap<String, Any?>() {
        override val entries: Set<Map.Entry<String, Any?>>
            get() = object : AbstractSet<Map.Entry<String, Any?>>() {
                override val size: Int get() = 1
                override fun iterator(): Iterator<Map.Entry<String, Any?>> = throw error
            }
    }
}
