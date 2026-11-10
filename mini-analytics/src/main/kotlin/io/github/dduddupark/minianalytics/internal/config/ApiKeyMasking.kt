package io.github.dduddupark.minianalytics.internal.config

/** 로그용 `apiKey` 마스킹. 12자 이상이면 앞뒤 2자만 보이고, 그보다 짧으면 전부 가린다. */
internal fun maskApiKey(apiKey: String?): String = when {
    apiKey == null -> "null"
    apiKey.length >= 12 -> "${apiKey.take(2)}****${apiKey.takeLast(2)}"
    else -> "****"
}
