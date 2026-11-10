package io.github.dduddupark.minianalytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.dduddupark.minianalytics.internal.config.ConfigurationValidator;
import java.util.List;
import org.junit.Test;

/**
 * Java 호출자가 null을 넘겨도 공개 진입점에서 NPE가 나지 않는지 확인한다 (설계 §2.4, D-4).
 * 이 테스트는 Java로 작성해야 의미가 있다. Kotlin에서는 컴파일러가 null 전달을 막기 때문이다.
 */
public class JavaNullInputTest {

    @Test
    public void builderAcceptsNullApiKeyAndEndpointWithoutThrowing() {
        Configuration configuration = new Configuration.Builder(null, null).build();
        // build()는 검증하지 않는다. 예외 없이 객체가 만들어져야 한다.
        assertFalse(configuration.toString().isEmpty());
    }

    @Test
    public void nullValuesAreReportedByValidation() {
        Configuration configuration = new Configuration.Builder(null, null).build();
        List<String> errors = ConfigurationValidator.INSTANCE.validate(configuration, false);
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).contains("apiKey"));
        assertTrue(errors.get(1).contains("endpoint"));
    }

    @Test
    public void equalsAndHashCodeHandleNull() {
        Configuration a = new Configuration.Builder(null, null).build();
        Configuration b = new Configuration.Builder(null, null).build();
        Configuration c = new Configuration.Builder("key", "https://example.com").build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
    }

    @Test
    public void trustInvalidEventResponsesIsFalseByDefaultAndCanBeEnabledFromJava() {
        Configuration defaults = new Configuration.Builder("key", "https://example.com").build();
        Configuration trusted = IdentityCallbackJavaUsage.configuration();
        assertFalse(defaults.toString().contains("trustInvalidEventResponses=true"));
        assertTrue(trusted.toString().contains("trustInvalidEventResponses=true"));
    }

    @Test
    public void failureReasonCanBeSwitchedWithDefaultBranch() {
        assertEquals("failure:INVALID_ARGUMENT",
                IdentityCallbackJavaUsage.describe(
                        new IdentityResult.Failure(FailureReason.INVALID_ARGUMENT, null)));
    }
}
