package io.github.dduddupark.minianalytics.internal.model

/**
 * 사용자 식별자. 불변이며, storage coroutine만 현재 값을 소유한다 (설계 §3, R-1).
 *
 * @property anonymousId SDK가 생성한 UUID v4 (설계 Q7).
 * @property userId `identify()`로 설정된 값. 익명 상태면 null.
 */
internal data class Identity(
    val anonymousId: String,
    val userId: String?,
)
