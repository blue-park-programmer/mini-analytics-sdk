package io.github.dduddupark.minianalytics

/**
 * `identify()`와 `reset()`의 완료 콜백.
 *
 * - 메인 스레드에서 호출된다.
 * - 프로세스가 살아 있고 SDK가 결과를 처리할 수 있는 경우에 한해 정확히 한 번 호출된다.
 *   프로세스가 강제 종료되면 호출되지 않을 수 있다.
 * - 호스트 앱의 로그아웃을 이 콜백에 의존시키지 말 것. 기다린다면 타임아웃을 둔다 (설계 §12 G-3).
 */
public fun interface IdentityCallback {
    public fun onComplete(result: IdentityResult)
}

/** Identity 변경의 영속화 결과. */
public sealed class IdentityResult {
    /** 이 변경의 효과를 포함하는 Identity가 디스크에 영구 저장되었다. */
    public object Success : IdentityResult() {
        override fun toString(): String = "Success"
    }

    /** 이 변경은 영구 저장되지 않았다. */
    public class Failure(
        public val reason: FailureReason,
        public val cause: Throwable?,
    ) : IdentityResult() {
        override fun toString(): String = "Failure(reason=$reason, cause=$cause)"
    }
}

/**
 * 실패 원인.
 *
 * 이후 버전에서 값이 추가될 수 있다. Java의 `switch`에서는 `default` 분기를 둘 것.
 */
public enum class FailureReason {
    /** SQLite 저장에 실패했거나 SDK가 비활성 상태다. */
    STORAGE_ERROR,

    /** `initialize()` 전에 호출되었거나 초기화에 실패했다. */
    NOT_INITIALIZED,

    /**
     * 인자가 유효하지 않아서 아무것도 바꾸지 않았다.
     * 예: `identify()`의 `userId`가 null이거나, 공백뿐이거나, 256자를 넘는다 (설계 §2.3).
     */
    INVALID_ARGUMENT,
}
