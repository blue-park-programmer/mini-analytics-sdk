package io.github.dduddupark.minianalytics.internal

/** SDK 빌드 정보. 공개 API가 아니다. */
internal object BuildInfo {
    /** 전송 본문의 `context.library.name`. */
    val LIBRARY_NAME: String = "mini-analytics-android"

    /** 전송 본문의 `context.library.version`. 배포 설정(7단계)에서 빌드 버전과 연결한다. */
    val LIBRARY_VERSION: String = "0.1.0-SNAPSHOT"
}
