package io.github.dduddupark.minianalytics.internal

import android.content.Context
import io.github.dduddupark.minianalytics.Configuration
import io.github.dduddupark.minianalytics.MiniAnalytics

/**
 * 운영용 [AnalyticsClient] 생성기. facade가 쓰고, 테스트에서만 다른 구현으로 바꾼다.
 * facade 안에서 람다나 함수 참조로 만들면 공개 패키지에 합성 클래스가 생기므로 여기에 둔다.
 */
internal object DefaultClientFactory : (Context, Configuration) -> AnalyticsClient {
    override fun invoke(context: Context, configuration: Configuration): AnalyticsClient =
        AnalyticsClient.create(context, configuration, MiniAnalytics.logger)
}
