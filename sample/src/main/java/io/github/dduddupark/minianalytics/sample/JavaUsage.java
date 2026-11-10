package io.github.dduddupark.minianalytics.sample;

import android.util.Log;
import io.github.dduddupark.minianalytics.FailureReason;
import io.github.dduddupark.minianalytics.IdentityResult;
import io.github.dduddupark.minianalytics.MiniAnalytics;
import java.util.HashMap;
import java.util.Map;

/** Java 소비 코드: 정적 호출(@JvmStatic), 오버로드(@JvmOverloads), null 전달, 결과 처리. */
final class JavaUsage {
    private JavaUsage() {
    }

    static void run(String prefix) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("lang", "java");
        MiniAnalytics.track(prefix + "-java", properties);
        MiniAnalytics.track(prefix + "-java-noprops");
        MiniAnalytics.track(null); // 무시되고 예외가 없어야 한다
        MiniAnalytics.screen(prefix + "-java-screen", null);
        MiniAnalytics.identify(null, result -> {
            String reason = result instanceof IdentityResult.Failure
                    ? String.valueOf(((IdentityResult.Failure) result).getReason())
                    : "success";
            Log.i(SampleApplication.TAG, "java identify(null) -> " + reason
                    + " expected=" + FailureReason.INVALID_ARGUMENT);
        });
        MiniAnalytics.flush();
    }
}
