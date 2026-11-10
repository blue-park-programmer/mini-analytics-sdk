package io.github.dduddupark.minianalytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Java 호스트가 공개 facade에 null을 넘길 때 (설계 §2.4, Q-4a, T-I3).
 * Kotlin 컴파일러가 막는 null 전달을 실제로 해 보기 위해 Java로 작성한다.
 * {@code MiniAnalytics.track(...)}처럼 정적 호출이 되는지(@JvmStatic)도 함께 확인한다.
 */
@RunWith(RobolectricTestRunner.class)
public class JavaFacadeNullTest {

    private final List<IdentityResult> results = new ArrayList<>();
    private final IdentityCallback callback = results::add;
    private Context context;
    private Configuration valid;

    @Before
    public void setUp() {
        FacadeTestHooks.reset();
        context = RuntimeEnvironment.getApplication();
        valid = new Configuration.Builder("key", "https://example.com/v1/batch").build();
    }

    @After
    public void tearDown() {
        FacadeTestHooks.reset();
    }

    private void idleMain() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private FailureReason lastFailure() {
        return ((IdentityResult.Failure) results.get(results.size() - 1)).getReason();
    }

    @Test
    public void initializeWithNullArgumentsReturnsInvalidConfiguration() {
        assertEquals(InitializeResult.INVALID_CONFIGURATION, MiniAnalytics.initialize(null, null));
        assertEquals(InitializeResult.INVALID_CONFIGURATION, MiniAnalytics.initialize(null, valid));
        assertEquals(InitializeResult.INVALID_CONFIGURATION, MiniAnalytics.initialize(context, null));
        assertEquals(InitializeResult.INVALID_CONFIGURATION,
                MiniAnalytics.initialize(context, new Configuration.Builder(null, null).build()));
    }

    @Test
    public void nullArgumentsBeforeInitializeDoNotThrow() {
        MiniAnalytics.track(null);
        MiniAnalytics.track(null, null);
        MiniAnalytics.screen(null);
        MiniAnalytics.screen(null, null);
        MiniAnalytics.identify(null);
        MiniAnalytics.identify(null, null);
        MiniAnalytics.reset(null);
        MiniAnalytics.reset();
        MiniAnalytics.flush();

        MiniAnalytics.identify(null, callback);
        idleMain();
        assertEquals(FailureReason.NOT_INITIALIZED, lastFailure());
    }

    @Test
    public void nullArgumentsAfterInitializeDoNotThrow() {
        assertEquals(InitializeResult.STARTED, MiniAnalytics.initialize(context, valid));
        MiniAnalytics.track(null);
        MiniAnalytics.track("event", null);
        MiniAnalytics.screen(null, null);
        MiniAnalytics.identify(null, null);
        MiniAnalytics.reset(null);
        MiniAnalytics.flush();
    }

    @Test
    public void identifyWithNullUserIdAfterInitializeFailsWithInvalidArgument() { // T-I3
        MiniAnalytics.initialize(context, valid);
        MiniAnalytics.identify(null, callback);
        idleMain();
        assertEquals(1, results.size());
        assertEquals(FailureReason.INVALID_ARGUMENT, lastFailure());
    }

    @Test
    public void rawJavaMapWithNonStringKeysIsAccepted() {
        MiniAnalytics.initialize(context, valid);
        @SuppressWarnings({"rawtypes", "unchecked"})
        java.util.Map raw = new java.util.HashMap();
        raw.put(1, "int key");
        raw.put(null, "null key");
        raw.put("ok", 1);
        //noinspection unchecked
        MiniAnalytics.track("event", raw);
        assertTrue(true); // 예외 없이 반환
    }
}
