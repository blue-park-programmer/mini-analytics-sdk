package io.github.dduddupark.minianalytics.benchhost

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.dduddupark.minianalytics.MiniAnalytics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SQLite 저장 처리량·commit 지연·배경 CPU (Step 8 2단계). SDK 공개 API만 쓴다.
 *
 * 용어
 *  - 호출 처리량: track() N건을 호출 스레드에서 끝내는 데 걸린 시간 기준 (저장과 무관).
 *  - 처리 완료: N건 뒤에 넣은 identify의 콜백 시각(= 앞선 명령이 모두 처리됨). 처리는 저장·메모리 큐 폐기·DB 상한 정리를
 *    모두 포함하므로, 저장 처리량은 **실제로 저장된 건수**를 따로 확인한 경우에만 계산한다.
 *  - 콜백은 메인 Looper로 post되므로, 직후에 메인 post 왕복 시간을 재서 함께 기록한다(보정 범위).
 *  - 배경 CPU: /proc/self/task/<tid>/schedstat(ns)의 합에서 호출 스레드를 뺀 값. 메인 스레드(콜백 처리)도 포함된다.
 *
 * 시나리오 (메커니즘이 하나만 일어나도록 설계)
 *  - store_burst: 50/100/500/900건 묶음 × 3회. 대기 명령 < 1,000(메모리 큐 상한) → 폐기 없음.
 *                 상한 10,000에 총 4,650건 → DB 상한 정리 없음. 따라서 저장 = 호출이어야 한다.
 *  - store_commit: identify → 콜백 시간(commit 지연 대리값)을 직전 대기 0/100/500ms별로 30회씩.
 *  - store_sparse_cpu: 30초 무호출(기준) 뒤 100ms 간격 track 300건. 이벤트 1건당 배경 CPU.
 *  - store_evict: cap(예: 1,000)으로 900건 × 3묶음(+장벽). 폐기 없음 → 미저장은 DB 상한 정리여야 한다.
 *  - store_drop: 상한 10,000에서 5,000건 연속 호출. DB 상한 정리 없음 → 미저장은 메모리 큐 폐기여야 한다.
 * 모든 이벤트에 순번(seq) 속성을 넣고, 끝난 뒤 DB와 업로드 서버 양쪽에서 seq 패턴으로 확인한다:
 *  앞부분이 연속으로 빠짐 = DB 상한 정리(오래된 행부터), 중간이 빠짐 = 메모리 큐 폐기(대기 중 오래된 것부터).
 */
object StoreBench {
    const val EVENT = "bench_store"
    private val BURST_SIZES = intArrayOf(900, 50, 500, 100)
    private var seq = 0
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun props(s: Int): Map<String, Any?> =
        mapOf("screen" to "home", "position" to 3, "price" to 12.5, "premium" to true, "tag" to "a", "seq" to s)

    private fun pct(values: LongArray): JSONObject {
        if (values.isEmpty()) return JSONObject().put("n", 0)
        val s = values.copyOf().also { it.sort() }
        fun p(q: Double) = s[((s.size - 1) * q).toInt()]
        return JSONObject().put("n", s.size).put("min", s.first()).put("p50", p(0.50)).put("p95", p(0.95))
            .put("p99", p(0.99)).put("max", s.last()).put("mean", s.average().toLong())
    }

    /** identify를 넣고, 콜백이 메인 스레드에서 실행된 시각(nanoTime)을 돌려준다. */
    private fun barrier(timeoutSec: Long = 120): Long {
        val latch = CountDownLatch(1)
        var at = -1L
        MiniAnalytics.identify("bench-user") { at = System.nanoTime(); latch.countDown() }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return at
    }

    /** 메인 Looper post 왕복 시간 (콜백 지연 보정 범위). */
    private fun mainPostLatency(): Long {
        val latch = CountDownLatch(1)
        var at = -1L
        val t = System.nanoTime()
        main.post { at = System.nanoTime(); latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        return at - t
    }

    /** 호출 스레드를 뺀 모든 스레드의 누적 CPU 시간(ns). */
    private fun otherThreadsCpu(callerTid: Int): Long {
        var sum = 0L
        File("/proc/self/task").listFiles()?.forEach { dir ->
            if (dir.name == callerTid.toString()) return@forEach
            try {
                sum += File(dir, "schedstat").readText().trim().split(' ')[0].toLong()
            } catch (e: Exception) {
                // 그사이 끝난 스레드
            }
        }
        return sum
    }

    fun countTracks(context: Context): Long = query(context) { db ->
        db.rawQuery("SELECT COUNT(*) FROM events WHERE type = 'track'", null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1 }
    } ?: -1

    private fun <T> query(context: Context, block: (SQLiteDatabase) -> T): T? = try {
        SQLiteDatabase.openDatabase(context.getDatabasePath("mini_analytics.db").path, null, SQLiteDatabase.OPEN_READONLY)
            .use(block)
    } catch (e: Exception) {
        null
    }

    /** DB에 남은 track 행의 seq 패턴 (측정 뒤, SDK가 한가할 때만 읽는다. DB 스키마는 SDK 내부 구현이다). */
    private fun seqPattern(context: Context, issued: Int): JSONObject {
        val seqs = query(context) { db ->
            val list = ArrayList<Int>()
            db.rawQuery("SELECT payload FROM events WHERE type = 'track'", null).use { c ->
                while (c.moveToNext()) {
                    val p = JSONObject(c.getString(0)).optJSONObject("properties")
                    if (p != null && p.has("seq")) list += p.getInt("seq")
                }
            }
            list
        } ?: return JSONObject().put("error", "db read failed")
        return pattern(seqs, issued)
    }

    fun pattern(seqs: List<Int>, issued: Int): JSONObject {
        val sorted = seqs.distinct().sorted()
        if (sorted.isEmpty()) return JSONObject().put("rows", 0).put("issued", issued)
        val min = sorted.first()
        val max = sorted.last()
        return JSONObject().put("rows", seqs.size).put("distinct", sorted.size).put("issued", issued)
            .put("minSeq", min).put("maxSeq", max)
            .put("missingPrefix", min) // 0..min-1
            .put("missingInterior", (max - min + 1) - sorted.size)
            .put("missingSuffix", (issued - 1) - max)
    }

    fun run(context: Context, scenario: String, cap: Int): JSONObject {
        seq = 0
        val callerTid = Process.myTid()
        val out = JSONObject().put("scenario", scenario).put("cap", cap).put("device", Build.MODEL)
            .put("sdkInt", Build.VERSION.SDK_INT).put("initializeNanos", BenchApplication.initializeNanos)
            .put("debuggable", (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        barrier() // DB 열기·identity 로드 완료 대기
        val t0 = System.nanoTime()
        when (scenario) {
            "store_burst", "store_evict" -> {
                val sizes = if (scenario == "store_burst") IntArray(12) { BURST_SIZES[it % 4] } else IntArray(3) { 900 }
                val bursts = JSONArray()
                for (n in sizes) {
                    Thread.sleep(1_000)
                    val maps = List(n) { props(seq + it) } // 측정 밖에서 미리 만든다
                    val bg0 = otherThreadsCpu(callerTid)
                    val c0 = Debug.threadCpuTimeNanos()
                    val a = System.nanoTime()
                    for (m in maps) MiniAnalytics.track(EVENT, m)
                    val b = System.nanoTime()
                    val callerCpu = Debug.threadCpuTimeNanos() - c0
                    seq += n
                    val cb = barrier()
                    val loop = mainPostLatency()
                    val bg1 = otherThreadsCpu(callerTid)
                    bursts.put(JSONObject().put("n", n).put("callNanos", b - a).put("callerCpuNanos", callerCpu)
                        .put("processedNanos", cb - a).put("mainPostLatencyNanos", loop).put("bgCpuNanos", bg1 - bg0))
                }
                out.put("bursts", bursts)
            }
            "store_commit" -> {
                val gaps = longArrayOf(0, 100, 500)
                val samples = JSONObject()
                for (gap in gaps) {
                    val lat = LongArray(30)
                    val loops = LongArray(30)
                    val bg = LongArray(30)
                    for (i in 0 until 30) {
                        if (gap > 0) Thread.sleep(gap) else Thread.sleep(20) // 0: 직전 처리 직후(짧은 간격)
                        val bg0 = otherThreadsCpu(callerTid)
                        val a = System.nanoTime()
                        val cb = barrier()
                        lat[i] = cb - a
                        loops[i] = mainPostLatency()
                        bg[i] = otherThreadsCpu(callerTid) - bg0
                    }
                    samples.put(gap.toString(), JSONObject().put("identifyToCallbackNanos", pct(lat))
                        .put("mainPostLatencyNanos", pct(loops)).put("bgCpuPerCommitNanos", pct(bg)))
                }
                out.put("commit", samples)
            }
            "store_sparse_cpu" -> {
                val idle0 = otherThreadsCpu(callerTid)
                val i0 = System.nanoTime()
                Thread.sleep(30_000)
                val idleCpu = otherThreadsCpu(callerTid) - idle0
                val idleDur = System.nanoTime() - i0
                val ev0 = otherThreadsCpu(callerTid)
                val c0 = Debug.threadCpuTimeNanos()
                val e0 = System.nanoTime()
                repeat(300) {
                    Thread.sleep(100)
                    MiniAnalytics.track(EVENT, props(seq++))
                }
                barrier()
                val evCpu = otherThreadsCpu(callerTid) - ev0
                val evDur = System.nanoTime() - e0
                val callerCpu = Debug.threadCpuTimeNanos() - c0
                val ambient = idleCpu.toDouble() * evDur / idleDur
                out.put("sparseCpu", JSONObject().put("idleBgCpuNanos", idleCpu).put("idleDurNanos", idleDur)
                    .put("eventsBgCpuNanos", evCpu).put("eventsDurNanos", evDur).put("events", 300)
                    .put("bgCpuPerEventNanos", ((evCpu - ambient) / 300).toLong())
                    .put("callerCpuPerEventNanos", callerCpu / 300))
            }
            "store_drop" -> {
                val maps = List(5_000) { props(it) }
                val c0 = Debug.threadCpuTimeNanos()
                val a = System.nanoTime()
                for (m in maps) MiniAnalytics.track(EVENT, m)
                val b = System.nanoTime()
                seq = 5_000
                val cb = barrier()
                out.put("drop", JSONObject().put("n", 5_000).put("callNanos", b - a)
                    .put("callerCpuNanos", Debug.threadCpuTimeNanos() - c0).put("processedNanos", cb - a))
            }
            else -> error("unknown scenario: $scenario")
        }
        out.put("measuredWallNanos", System.nanoTime() - t0)
        barrier()
        out.put("issued", seq).put("db", seqPattern(context, seq))

        // 독립 검증용 업로드: flush() 한 번에 배치 하나가 나가므로, DB의 track 행이 0이 될 때까지 반복한다.
        // 실행기는 이 로그를 보고 (store_evict에서 503으로 두었던) 검증 서버를 정상 응답으로 바꾼다.
        android.util.Log.i(BenchActivity.TAG, "UPLOAD_PHASE")
        val u0 = System.nanoTime()
        var remaining = countTracks(context)
        while (remaining > 0 && System.nanoTime() - u0 < 180_000_000_000L) {
            MiniAnalytics.flush()
            Thread.sleep(300)
            remaining = countTracks(context)
        }
        out.put("upload", JSONObject().put("remainingTracks", remaining).put("seconds", (System.nanoTime() - u0) / 1e9))
        return out
    }
}
