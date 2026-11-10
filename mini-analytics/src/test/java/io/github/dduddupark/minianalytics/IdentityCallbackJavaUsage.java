package io.github.dduddupark.minianalytics;

/**
 * Java 호스트 앱에서의 사용 형태를 컴파일로 검증한다 (설계 Q21).
 * 이 파일이 컴파일되지 않으면 공개 타입의 Java 사용성이 깨진 것이다.
 */
final class IdentityCallbackJavaUsage {
    private IdentityCallbackJavaUsage() {
    }

    /** Java 람다로 콜백을 만들 수 있다. */
    static final IdentityCallback LAMBDA = result -> {
    };

    /** Java에서 결과를 instanceof와 switch로 다루는 형태. */
    static String describe(IdentityResult result) {
        if (result instanceof IdentityResult.Success) {
            return "success";
        }
        if (result instanceof IdentityResult.Failure) {
            IdentityResult.Failure failure = (IdentityResult.Failure) result;
            switch (failure.getReason()) {
                case STORAGE_ERROR:
                    return "failure:STORAGE_ERROR";
                case NOT_INITIALIZED:
                    return "failure:NOT_INITIALIZED";
                case INVALID_ARGUMENT:
                    return "failure:INVALID_ARGUMENT";
                default:
                    return "failure:unknown";
            }
        }
        return "unknown";
    }

    /** Java에서 Configuration을 만드는 형태. */
    static Configuration configuration() {
        return new Configuration.Builder("key", "https://example.com")
                .flushAt(10)
                .flushIntervalMillis(15_000L)
                .maxQueuedEvents(500)
                .trustInvalidEventResponses(true)
                .build();
    }

    /** InitializeResult는 일반 Java enum으로 다룰 수 있다. */
    static boolean accepted(InitializeResult result) {
        return result == InitializeResult.STARTED;
    }
}
