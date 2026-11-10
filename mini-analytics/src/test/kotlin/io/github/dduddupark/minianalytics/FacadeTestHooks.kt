package io.github.dduddupark.minianalytics

/** Java 테스트에서 facade의 internal 테스트 훅을 부르기 위한 다리. */
object FacadeTestHooks {
    @JvmStatic
    fun reset() = MiniAnalytics.resetForTesting()
}
