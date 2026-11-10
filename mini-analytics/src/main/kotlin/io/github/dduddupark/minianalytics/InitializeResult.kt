package io.github.dduddupark.minianalytics

/**
 * `MiniAnalytics.initialize()`의 결과.
 *
 * 이 값은 **이번 호출을 받아들였는지만** 나타낸다. DB 열기나 Identity 로드 같은
 * 비동기 초기화가 성공했는지는 나타내지 않는다 (설계 §2.1).
 */
public enum class InitializeResult {
    /** 이번 호출로 설정을 등록하고 비동기 초기화를 시작했다. */
    STARTED,

    /** 이미 초기화되어 있어서 이번 호출을 무시했다. */
    ALREADY_INITIALIZED,

    /** 설정이 유효하지 않아서 초기화하지 않았다. 설정을 고쳐서 다시 호출할 수 있다. */
    INVALID_CONFIGURATION,
}
