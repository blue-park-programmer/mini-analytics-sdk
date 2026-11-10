package io.github.dduddupark.minianalytics.internal.upload

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import io.github.dduddupark.minianalytics.internal.BuildInfo
import io.github.dduddupark.minianalytics.internal.serialization.Iso8601UtcFormatter
import io.github.dduddupark.minianalytics.internal.storage.StoredEvent
import org.json.JSONObject

/**
 * 요청 본문 `{ sentAt, context, batch[] }`를 만든다 (설계 §7.2).
 *
 * - `batch[]`의 원소는 DB에 저장된 payload(이미 직렬화된 JSON)를 **그대로** 이어 붙인다. 다시 파싱하지 않는다.
 * - `sentAt`은 요청마다 새 [Iso8601UtcFormatter]로 만든다 (R-11).
 * - `context`는 최소한의 정보만 담는다: 라이브러리, OS, 앱 버전 (기기 모델·광고 ID·위치는 없음).
 */
internal class EnvelopeBuilder(
    contextJson: () -> String,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val context: String by lazy(contextJson)

    fun build(batch: List<StoredEvent>): ByteArray {
        val sentAt = Iso8601UtcFormatter().format(wallClock())
        val builder = StringBuilder(batch.sumOf { it.sizeBytes } + 512)
        builder.append("{\"sentAt\":").append(JSONObject.quote(sentAt))
        builder.append(",\"context\":").append(context)
        builder.append(",\"batch\":[")
        batch.forEachIndexed { index, event ->
            if (index > 0) builder.append(',')
            builder.append(event.payload)
        }
        builder.append("]}")
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** 운영용 context. 패키지 정보 조회가 있으므로 백그라운드(첫 전송 시)에서만 호출된다. */
        fun androidContextJson(appContext: Context): String {
            val app = JSONObject()
            try {
                val info: PackageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                app.put("version", info.versionName ?: JSONObject.NULL)
                @Suppress("DEPRECATION")
                val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                app.put("build", build.toString())
            } catch (e: Exception) {
                app.put("version", JSONObject.NULL).put("build", JSONObject.NULL)
            }
            return JSONObject()
                .put("library", JSONObject().put("name", BuildInfo.LIBRARY_NAME).put("version", BuildInfo.LIBRARY_VERSION))
                .put("os", JSONObject().put("name", "Android").put("version", Build.VERSION.RELEASE ?: JSONObject.NULL))
                .put("app", app)
                .toString()
        }
    }
}
