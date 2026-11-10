package io.github.dduddupark.minianalytics.internal.model

/**
 * 식별 문자열 검증. 다음 세 곳에 같은 규칙을 쓴다 (설계 §2.2, §2.3, §2.5).
 * - `track()`의 event 이름
 * - `screen()`의 name
 * - `identify()`의 userId
 *
 * 규칙:
 * - **길이**: Kotlin `String.length`, 즉 UTF-16 code unit 수로 잰다. 1~256이어야 한다.
 *   (예: 한글 한 글자는 1, 이모지 하나는 대개 2로 센다.)
 * - **공백**: 모든 문자가 공백(`Char.isWhitespace`)이면 유효하지 않다.
 * - **다듬기 없음**: 앞뒤 공백을 잘라내지 않고 받은 값을 그대로 쓴다. 길이도 원래 값 기준이다.
 * - **잘라내기 없음**: 너무 길면 거부한다.
 *
 * Java 호출자가 null을 넘길 수 있으므로 nullable로 받는다.
 */
internal object NameValidator {
    const val MAX_NAME_LENGTH: Int = 256

    enum class Result { VALID, BLANK, TOO_LONG }

    fun validate(name: String?): Result = when {
        name == null || name.isBlank() -> Result.BLANK
        name.length > MAX_NAME_LENGTH -> Result.TOO_LONG
        else -> Result.VALID
    }
}
