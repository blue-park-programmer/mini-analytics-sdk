package io.github.dduddupark.minianalytics.benchhost

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import io.github.dduddupark.minianalytics.MiniAnalytics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * track() 호출 지연·할당 측정 하네스 (Step 8). SDK 공개 API만 호출한다.
 *
 * 측정 대상은 **호출 스레드에서 track()이 반환될 때까지의 시간**이다. 저장(commit) 처리량이 아니다.
 *  - normal: 50건씩 호출하고, 매 묶음 뒤 identify 콜백으로 앞선 명령이 처리될 때까지 기다린다(대기는 측정에서 뺀다).
 *            묶음 안 순번, 대기 뒤 경과 시간(gap), 묶음 시작·끝의 CPU 코어를 함께 기록한다.
 *  - normal_spin: normal과 같되, 묶음 직전에 호출 스레드에서 [SPIN_MICROS]µs 동안 바쁜 대기(SDK 미호출)를 한다.
 *            "대기 뒤 CPU·캐시가 식어 첫 호출들이 느리다"는 가설 확인용 진단 변형이다.
 *  - normal_main: normal과 같되, 묶음(50건, 약 0.5ms 미만)을 메인 스레드에 post해서 실행한다.
 *            메인 스레드를 한 번에 오래 막지 않는다. 호출 지연만 재며, UI 프레임 영향은 재지 않는다.
 *  - normal_prewake: normal과 같되, 묶음 직전에 측정하지 않는 track() 1건을 먼저 호출한다.
 *            "대기 뒤 첫 호출이 storage coroutine을 깨우는 비용을 낸다"는 가설 확인용 진단 변형이다.
 *  - normal_cpuprobe: normal과 같되, 묶음의 0번째와 25번째 호출에서 스레드 CPU 시간도 함께 잰다
 *            (호출 스레드가 CPU를 쓴 것인지, 다른 이유로 기다린 것인지 구분). 이 두 표본은 probe 비용이 섞인다.
 *  - sparse: [intervalMs]마다 track() 1건을 호출한다. 호출 사이에는 Thread.sleep으로 기다린다(바쁜 대기 없음).
 *            실제 앱처럼 이벤트가 드문드문 발생하는 경우의 호출 비용이다. warmup건은 같은 간격으로 호출하고 버린다.
 *            측정 전에 [JIT_WARMUP_CALLS]건을 50건 묶음으로 빠르게 호출해(묶음마다 처리 대기) 코드가 JIT 컴파일될
 *            기회를 준다. normal·saturated와 코드 상태를 맞추기 위해서다.
 *  - sparse_cold: sparse와 같되 빠른 warm-up을 하지 않는다. 드물게만 호출되는 앱에서 아직 컴파일되지 않은
 *            코드로 호출되는 경우에 가깝다(앱의 dexopt 상태는 실행기가 기록한다).
 *  - sparse_control: sparse와 같은 간격·대기를 쓰되, track() 대신 SDK를 부르지 않는 호스트 코드(속성 맵 복사 +
 *            UUID 생성 + 현재 시각)를 잰다. 대기 뒤 기기 자체(CPU 저전력 상태·클록)가 깨어나는 영향을 보는 대조군이다.
 *  - saturated: 기다리지 않고 연속 호출한다. 앞의 warmup건은 버리고 그 뒤만 측정한다.
 *  - overhead: 같은 루프에서 track() 대신 빈 연산을 재서 nanoTime 쌍 자체의 비용을 구한다.
 * 측정 뒤(SDK가 한가할 때) DB의 track 행 수를 세서 "호출 − 저장"(미저장)을 기록한다.
 */
object BenchHarness {
    private const val EVENT = "bench_event"
    const val BURST = 50
    private const val SPIN_MICROS = 3_000L
    private const val JIT_WARMUP_CALLS = 500

    @Volatile private var sink = 0L

    fun props(kind: String): Map<String, Any?>? = when (kind) {
        "none" -> null
        "p5" -> mapOf("screen" to "home", "position" to 3, "price" to 12.5, "premium" to true, "tag" to "a")
        "p20" -> (0 until 20).associate { i ->
            "key_$i" to when (i % 4) {
                0 -> "value_$i"
                1 -> i
                2 -> i * 1.5
                else -> i % 2 == 0
            }
        }
        // 약 4KB: 짧은 값 4개 + 4,000자 문자열 1개 (문자열 상한 8,192자 안이라 정리 경로를 타지 않는다)
        "p4k" -> mapOf("a" to 1, "b" to "x", "c" to 2.0, "d" to false, "blob" to "y".repeat(4_000))
        else -> error("unknown props kind: $kind")
    }

    /** 앞선 명령이 모두 처리될 때까지 기다린다 (identify는 메모리 상한으로 버려지지 않고 FIFO로 처리된다). */
    private fun waitDrained(timeoutSec: Long = 60): Boolean {
        val latch = CountDownLatch(1)
        MiniAnalytics.identify("bench-user") { latch.countDown() }
        return latch.await(timeoutSec, TimeUnit.SECONDS)
    }

    private fun gcStats(): LongArray = longArrayOf(
        Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1,
        Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: -1,
        Debug.getRuntimeStat("art.gc.blocking-gc-count")?.toLongOrNull() ?: -1,
    )

    /** 현재 스레드가 마지막으로 실행된 CPU 코어 (/proc/thread-self/stat 39번째 필드). 측정 구간 밖에서만 읽는다. */
    private fun currentCpu(): Int = try {
        val stat = File("/proc/thread-self/stat").readText()
        stat.substring(stat.lastIndexOf(')') + 2).split(' ')[36].toInt()
    } catch (e: Exception) {
        -1
    }

    private fun pct(values: LongArray): JSONObject {
        if (values.isEmpty()) return JSONObject().put("n", 0)
        val s = values.copyOf().also { it.sort() }
        fun p(q: Double) = s[((s.size - 1) * q).toInt()]
        return JSONObject()
            .put("n", s.size).put("min", s.first()).put("p50", p(0.50)).put("p95", p(0.95))
            .put("p99", p(0.99)).put("max", s.last()).put("mean", s.average().toLong())
    }

    private fun spin(micros: Long) {
        val end = System.nanoTime() + micros * 1_000
        var x = 0L
        while (System.nanoTime() < end) x++
        sink += x
    }

    @Suppress("DEPRECATION")
    fun run(context: Context, scenario: String, propsKind: String, measured: Int, warmup: Int, intervalMs: Long = 0): JSONObject {
        val properties = props(propsKind)
        val out = JSONObject()
            .put("scenario", scenario).put("props", propsKind).put("measured", measured).put("warmup", warmup)
            .put("intervalMs", intervalMs)
            .put("debuggable", (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .put("device", Build.MODEL).put("sdkInt", Build.VERSION.SDK_INT)
            .put("initializeNanos", BenchApplication.initializeNanos).put("initializeResult", BenchApplication.initializeResult)

        // 프로세스 첫 track() (cold): SecureRandom 초기화·클래스 로딩 포함. 측정 표본과 따로 둔다.
        val c0 = System.nanoTime()
        MiniAnalytics.track(EVENT, properties)
        out.put("firstCallNanos", System.nanoTime() - c0)
        var calls = 1L
        waitDrained()

        val samples = LongArray(measured)
        val gc0: LongArray
        val gc1: LongArray
        val t0 = System.nanoTime()
        when (scenario) {
            "overhead" -> {
                repeat(warmup) { val a = System.nanoTime(); sink += it; samples[0] = System.nanoTime() - a }
                gc0 = gcStats()
                for (i in 0 until measured) {
                    val a = System.nanoTime()
                    sink += i
                    samples[i] = System.nanoTime() - a
                }
                gc1 = gcStats()
            }
            "normal", "normal_spin", "normal_main", "normal_prewake", "normal_cpuprobe" -> {
                require(measured % BURST == 0) { "measured must be a multiple of $BURST" }
                val onMain = scenario == "normal_main"
                val spinBefore = scenario == "normal_spin"
                val prewake = scenario == "normal_prewake"
                val cpuProbe = scenario == "normal_cpuprobe"
                val probe = Array(4) { LongArray(measured / BURST) } // wall0, cpu0, wall25, cpu25
                var prewakeCalls = 0L
                val main = Handler(Looper.getMainLooper())
                val bursts = measured / BURST
                val gaps = LongArray(bursts)
                val cpuStart = IntArray(bursts)
                val cpuEnd = IntArray(bursts)
                val alloc = LongArray(2)

                /** 묶음 하나: [record]가 false면 warm-up이다. 호출 스레드(또는 메인 스레드)에서 실행된다. */
                fun burst(b: Int, base: Int, count: Int, record: Boolean, readyAt: Long) {
                    if (spinBefore) spin(SPIN_MICROS)
                    if (prewake) {
                        MiniAnalytics.track(EVENT, properties) // 측정하지 않는다
                        prewakeCalls++
                    }
                    if (record) {
                        cpuStart[b] = currentCpu()
                        Debug.resetThreadAllocCount()
                        Debug.resetThreadAllocSize()
                        gaps[b] = System.nanoTime() - readyAt
                    }
                    for (k in 0 until count) {
                        if (cpuProbe && record && (k == 0 || k == 25)) {
                            val c = Debug.threadCpuTimeNanos()
                            val a = System.nanoTime()
                            MiniAnalytics.track(EVENT, properties)
                            val d = System.nanoTime() - a
                            val cd = Debug.threadCpuTimeNanos() - c
                            samples[base + k] = d
                            val slot = if (k == 0) 0 else 2
                            probe[slot][b] = d
                            probe[slot + 1][b] = cd
                            continue
                        }
                        val a = System.nanoTime()
                        MiniAnalytics.track(EVENT, properties)
                        val d = System.nanoTime() - a
                        if (record) samples[base + k] = d
                    }
                    if (record) {
                        alloc[0] += Debug.getThreadAllocCount().toLong()
                        alloc[1] += Debug.getThreadAllocSize().toLong()
                        cpuEnd[b] = currentCpu()
                    }
                }

                fun runBurst(b: Int, base: Int, count: Int, record: Boolean) {
                    val readyAt = System.nanoTime()
                    if (onMain) {
                        val done = CountDownLatch(1)
                        main.post { burst(b, base, count, record, readyAt); done.countDown() }
                        done.await(30, TimeUnit.SECONDS)
                    } else {
                        burst(b, base, count, record, readyAt)
                    }
                    waitDrained()
                }

                if (onMain) {
                    val started = CountDownLatch(1)
                    main.post { Debug.startAllocCounting(); started.countDown() }
                    started.await(10, TimeUnit.SECONDS)
                } else {
                    Debug.startAllocCounting()
                }
                var w = 0
                while (w < warmup) {
                    val c = minOf(BURST, warmup - w)
                    runBurst(0, 0, c, record = false)
                    w += c
                }
                calls += warmup
                gc0 = gcStats()
                for (b in 0 until bursts) runBurst(b, b * BURST, BURST, record = true)
                gc1 = gcStats()
                Debug.stopAllocCounting()
                calls += measured + prewakeCalls
                if (cpuProbe) {
                    out.put("cpuProbe", JSONObject()
                        .put("pos0Wall", pct(probe[0])).put("pos0ThreadCpu", pct(probe[1]))
                        .put("pos25Wall", pct(probe[2])).put("pos25ThreadCpu", pct(probe[3])))
                }

                // 묶음 안 순번별 분포 (프로세스 안의 표본만 쓴다)
                val pos0 = LongArray(bursts) { samples[it * BURST] }
                val pos1to9 = LongArray(bursts * 9) { samples[(it / 9) * BURST + 1 + it % 9] }
                val pos10to49 = LongArray(bursts * 40) { samples[(it / 40) * BURST + 10 + it % 40] }
                // 상위 1% 표본이 묶음의 어디에 몰리는지
                val threshold = samples.copyOf().also { it.sort() }[((measured - 1) * 0.99).toInt()]
                var topTotal = 0
                var topPos0 = 0
                var topPosLt10 = 0
                for (i in 0 until measured) if (samples[i] >= threshold) {
                    topTotal++
                    if (i % BURST == 0) topPos0++
                    if (i % BURST < 10) topPosLt10++
                }
                val migrated = (0 until bursts).count { cpuStart[it] != cpuEnd[it] }
                val cores = JSONObject()
                cpuStart.groupBy { it }.forEach { (cpu, list) -> cores.put(cpu.toString(), list.size) }
                out.put("byPosition", JSONObject()
                    .put("pos0", pct(pos0)).put("pos1to9", pct(pos1to9)).put("pos10to49", pct(pos10to49)))
                    .put("top1pct", JSONObject().put("count", topTotal).put("pos0", topPos0).put("posLt10", topPosLt10)
                        .put("expectedPos0IfUniform", topTotal / BURST.toDouble())
                        .put("expectedPosLt10IfUniform", topTotal * 10 / BURST.toDouble()))
                    .put("gapNanos", pct(gaps))
                    .put("cpu", JSONObject().put("burstStartCores", cores).put("burstsMigrated", migrated)
                        .put("bursts", bursts))
                out.put("allocPerCall", alloc[0].toDouble() / measured).put("allocBytesPerCall", alloc[1].toDouble() / measured)
            }
            "sparse_control" -> {
                require(intervalMs > 0) { "sparse_control needs intervalMs > 0" }
                fun control(): Int {
                    val copy = HashMap<String, Any?>(properties ?: emptyMap())
                    copy["id"] = java.util.UUID.randomUUID().toString()
                    copy["ts"] = System.currentTimeMillis()
                    return copy.size
                }
                repeat(JIT_WARMUP_CALLS) { sink += control() }
                repeat(warmup) { Thread.sleep(intervalMs); sink += control() }
                gc0 = gcStats()
                for (i in 0 until measured) {
                    Thread.sleep(intervalMs)
                    val a = System.nanoTime()
                    sink += control()
                    samples[i] = System.nanoTime() - a
                }
                gc1 = gcStats()
            }
            "sparse", "sparse_cold" -> {
                require(intervalMs > 0) { "sparse needs intervalMs > 0" }
                if (scenario == "sparse") {
                    var w = 0
                    while (w < JIT_WARMUP_CALLS) {
                        repeat(BURST) { MiniAnalytics.track(EVENT, properties) }
                        w += BURST
                        waitDrained()
                    }
                    calls += JIT_WARMUP_CALLS
                    out.put("jitWarmupCalls", JIT_WARMUP_CALLS)
                }
                repeat(warmup) {
                    Thread.sleep(intervalMs)
                    MiniAnalytics.track(EVENT, properties)
                }
                val sinceMs = LongArray(measured)
                val cpu = LongArray(measured)
                val waited = LongArray(measured) // 경과 시간 − 스레드 CPU 시간 (호출 스레드가 실행되지 않은 시간)
                var alloc = 0L
                var allocBytes = 0L
                Debug.startAllocCounting()
                gc0 = gcStats()
                var last = System.nanoTime()
                for (i in 0 until measured) {
                    Thread.sleep(intervalMs)
                    Debug.resetThreadAllocCount()
                    Debug.resetThreadAllocSize()
                    val c = Debug.threadCpuTimeNanos()
                    val a = System.nanoTime()
                    MiniAnalytics.track(EVENT, properties)
                    val e = System.nanoTime()
                    cpu[i] = Debug.threadCpuTimeNanos() - c
                    samples[i] = e - a
                    waited[i] = maxOf(0L, samples[i] - cpu[i])
                    alloc += Debug.getThreadAllocCount().toLong()
                    allocBytes += Debug.getThreadAllocSize().toLong()
                    sinceMs[i] = (a - last) / 1_000_000
                    last = e
                }
                gc1 = gcStats()
                Debug.stopAllocCounting()
                calls += warmup + measured
                out.put("allocPerCall", alloc.toDouble() / measured).put("allocBytesPerCall", allocBytes.toDouble() / measured)
                    .put("intervalActualMs", pct(sinceMs))
                    .put("threadCpuNanos", pct(cpu)).put("wallMinusCpuNanos", pct(waited))
            }
            "saturated" -> {
                repeat(warmup) { MiniAnalytics.track(EVENT, properties) }
                val cpuSeen = JSONArray()
                Debug.startAllocCounting()
                Debug.resetThreadAllocCount()
                Debug.resetThreadAllocSize()
                gc0 = gcStats()
                cpuSeen.put(currentCpu())
                for (i in 0 until measured) {
                    val a = System.nanoTime()
                    MiniAnalytics.track(EVENT, properties)
                    samples[i] = System.nanoTime() - a
                }
                cpuSeen.put(currentCpu())
                gc1 = gcStats()
                val allocCount = Debug.getThreadAllocCount().toLong()
                val allocBytes = Debug.getThreadAllocSize().toLong()
                Debug.stopAllocCounting()
                calls += warmup + measured
                out.put("allocPerCall", allocCount.toDouble() / measured).put("allocBytesPerCall", allocBytes.toDouble() / measured)
                    .put("cpu", JSONObject().put("startEnd", cpuSeen))
            }
            else -> error("unknown scenario: $scenario")
        }
        out.put("measuredWallNanos", System.nanoTime() - t0)
        out.put("latencyNanos", pct(samples))
        out.put("gcCountDelta", gc1[0] - gc0[0]).put("gcTimeMsDelta", gc1[1] - gc0[1])
            .put("blockingGcDelta", gc1[2] - gc0[2])
        if (scenario != "overhead" && scenario != "sparse_control") {
            val drained = waitDrained(120)
            val stored = countStoredTracks(context)
            out.put("drainedAfterRun", drained).put("trackCalls", calls).put("storedTracks", stored)
                .put("notStored", if (stored >= 0) calls - stored else -1)
        }
        return out
    }

    /**
     * SDK가 만든 DB의 track 행 수를 읽기 전용으로 센다(측정 확인용). DB 이름은 SDK 내부 구현 값이며,
     * 바뀌면 -1을 돌려준다. 업로드는 연결 거부로 실패하므로 측정 중 행이 삭제되지 않는다.
     */
    private fun countStoredTracks(context: Context): Long = try {
        val file = context.getDatabasePath("mini_analytics.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM events WHERE type = 'track'", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1
            }
        }
    } catch (e: Exception) {
        -1
    }
}
