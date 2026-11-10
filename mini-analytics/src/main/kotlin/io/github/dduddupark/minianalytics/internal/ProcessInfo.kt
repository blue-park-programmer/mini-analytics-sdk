package io.github.dduddupark.minianalytics.internal

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/** 메인 프로세스 확인 (설계 §2.1: 멀티 프로세스는 지원하지 않으며, 경고만 남긴다). */
internal object ProcessInfo {

    /** @return 판단할 수 없으면 null. Android 9 미만에서는 파일을 읽으므로 메인 스레드에서 부르지 않는다. */
    fun isMainProcess(context: Context): Boolean? =
        isMainProcess(currentProcessName(), context.applicationInfo?.processName)

    fun isMainProcess(currentName: String?, mainName: String?): Boolean? =
        if (currentName.isNullOrEmpty() || mainName.isNullOrEmpty()) null else currentName == mainName

    fun currentProcessName(): String? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            File("/proc/self/cmdline").readText().trimEnd('\u0000').trim().ifEmpty { null }
        }
    } catch (e: Exception) {
        null
    }
}
