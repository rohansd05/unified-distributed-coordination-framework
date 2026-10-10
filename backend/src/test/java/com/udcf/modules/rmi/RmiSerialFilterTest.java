package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.management.BadAttributeValueExpException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputFilter.Status;
import java.util.ArrayList;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter alone, with fake {@link ObjectInputFilter.FilterInfo} values. The end-to-end checks
 * (a real argument refused; the longest echo accepted) are in RmiNodeServiceTest.
 */
class RmiSerialFilterTest {

    private static final RmiSerialFilter FILTER = RmiSerialFilter.from(RmiTestSupport.TRANSPORT.serialFilter());

    /** What the JDK passes to a filter at one checkpoint. */
    private record Info(Class<?> serialClass, long arrayLength, long depth, long references, long streamBytes)
            implements ObjectInputFilter.FilterInfo {
    }

    private static Status check(Class<?> type, long arrayLength, long depth, long references, long bytes) {
        return FILTER.checkInput(new Info(type, arrayLength, depth, references, bytes));
    }

    @Test
    @DisplayName("the pattern allows exactly CallContext, with the configured limits, and rejects everything else")
    void pattern() {
        assertThat(FILTER.pattern()).isEqualTo(
                "maxdepth=5;maxrefs=20;maxbytes=16384;maxarray=16;com.udcf.modules.rmi.CallContext;!*");
        assertThat(RmiSerialFilter.ALLOWED_CLASSES).containsExactly("com.udcf.modules.rmi.CallContext");
        assertThat(FILTER.toString()).contains(FILTER.pattern());
    }

    @Test
    @DisplayName("CallContext is allowed at the depth, references and bytes a real call has")
    void allowsCallContext() {
        // A real call reaches the filter with depth 1, 2 references and 74 bytes (measured in E1b).
        assertThat(check(CallContext.class, -1, 1, 2, 74)).isEqualTo(Status.ALLOWED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {HashMap.class, ArrayList.class, BadAttributeValueExpException.class, String.class,
            Integer.class, PingReply.class})
    @DisplayName("any other class is rejected")
    void rejectsOtherClasses(Class<?> type) {
        assertThat(check(type, -1, 1, 1, 50)).isEqualTo(Status.REJECTED);
    }

    @Test
    @DisplayName("a primitive array is rejected, although the JDK pattern alone leaves it undecided")
    void rejectsPrimitiveArrays() {
        ObjectInputFilter jdkOnly = ObjectInputFilter.Config.createFilter(FILTER.pattern());
        assertThat(jdkOnly.checkInput(new Info(byte[].class, 4, 1, 1, 50))).isEqualTo(Status.UNDECIDED);

        assertThat(check(byte[].class, 4, 1, 1, 50)).isEqualTo(Status.REJECTED);
        assertThat(check(int[].class, 0, 1, 1, 50)).isEqualTo(Status.REJECTED);
    }

    @Test
    @DisplayName("an array longer than max-array is rejected, and so is an array of an allowed class")
    void arrayLimit() {
        ObjectInputFilter jdkOnly = ObjectInputFilter.Config.createFilter(FILTER.pattern());
        assertThat(jdkOnly.checkInput(new Info(CallContext[].class, 1, 1, 1, 50)))
                .as("the JDK pattern matches an array by its element type").isEqualTo(Status.ALLOWED);

        assertThat(check(byte[].class, 17, 1, 1, 50)).isEqualTo(Status.REJECTED);
        assertThat(check(CallContext[].class, 1, 1, 1, 50)).isEqualTo(Status.REJECTED);
        assertThat(check(CallContext[][].class, 1, 1, 1, 50)).isEqualTo(Status.REJECTED);
    }

    @Test
    @DisplayName("depth, references and bytes beyond the limits are rejected; at the limit a checkpoint stays undecided")
    void limits() {
        assertThat(check(null, -1, 5, 20, 16384)).isEqualTo(Status.UNDECIDED);
        assertThat(check(null, -1, 6, 1, 50)).isEqualTo(Status.REJECTED);
        assertThat(check(null, -1, 1, 21, 50)).isEqualTo(Status.REJECTED);
        assertThat(check(null, -1, 1, 1, 16385)).isEqualTo(Status.REJECTED);
        assertThat(check(CallContext.class, -1, 6, 1, 50)).as("an allowed class beyond a limit").isEqualTo(Status.REJECTED);
    }

    @Test
    @DisplayName("building filters never installs a process-wide filter")
    void processWideFilterUntouched() {
        ObjectInputFilter before = ObjectInputFilter.Config.getSerialFilter();
        RmiSerialFilter.from(new RmiTransportProperties.SerialFilter(1, 1, 1, 1));
        RmiSerialFilter.from(RmiTestSupport.TRANSPORT.serialFilter());

        assertThat(ObjectInputFilter.Config.getSerialFilter()).isSameAs(before);
        assertThat(System.getProperty("jdk.serialFilter")).isNull();
    }
}
