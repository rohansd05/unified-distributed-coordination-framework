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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ------------------------------------------------------------------ allowing (E9b)

    /** As Experiment 9 builds it: CallContext and double[] up to 100 elements, bytes 8 x 100 + the E1b max-bytes. */
    private static final RmiSerialFilter WITH_DOUBLES = RmiSerialFilter.allowing(RmiTestSupport.TRANSPORT.serialFilter(),
            8L * 100 + 16384, 100, java.util.List.of(CallContext.class.getName()), java.util.Set.of(double[].class));

    private static Status checkWithDoubles(Class<?> type, long arrayLength) {
        return WITH_DOUBLES.checkInput(new Info(type, arrayLength, 1, 1, 10));
    }

    @Test
    @DisplayName("allowing: a listed array type is allowed up to maxarray and rejected beyond; listed classes still pass")
    void allowingListedArray() {
        assertThat(checkWithDoubles(double[].class, 100)).isEqualTo(Status.ALLOWED);
        assertThat(checkWithDoubles(double[].class, 0)).isEqualTo(Status.ALLOWED);
        assertThat(checkWithDoubles(double[].class, 101)).isEqualTo(Status.REJECTED);
        assertThat(checkWithDoubles(CallContext.class, -1)).isEqualTo(Status.ALLOWED);
    }

    @Test
    @DisplayName("allowing: every other array type and every other class is rejected")
    void allowingRejectsTheRest() {
        for (Class<?> type : new Class<?>[] {long[].class, int[].class, byte[].class, Object[].class, CallContext[].class,
                double[][].class, Double[].class}) {
            assertThat(checkWithDoubles(type, 1)).as(type.getName()).isEqualTo(Status.REJECTED);
        }
        assertThat(checkWithDoubles(String.class, -1)).isEqualTo(Status.REJECTED);
        assertThat(checkWithDoubles(java.util.HashMap.class, -1)).isEqualTo(Status.REJECTED);
    }

    @Test
    @DisplayName("allowing: the pattern carries its own byte and array limits and the E1b depth and reference limits")
    void allowingPattern() {
        assertThat(WITH_DOUBLES.pattern())
                .isEqualTo("maxdepth=5;maxrefs=20;maxbytes=17184;maxarray=100;com.udcf.modules.rmi.CallContext;!*");
    }

    @Test
    @DisplayName("from() still rejects every array (E1b unchanged); allowing() checks its arguments")
    void fromUnchangedAndAllowingValidates() {
        assertThat(FILTER.checkInput(new Info(double[].class, 1, 1, 1, 10))).isEqualTo(Status.REJECTED);
        assertThatThrownBy(() -> RmiSerialFilter.allowing(RmiTestSupport.TRANSPORT.serialFilter(), 10, 10,
                java.util.List.of(), java.util.Set.of(String.class))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RmiSerialFilter.allowing(RmiTestSupport.TRANSPORT.serialFilter(), 10, 0,
                java.util.List.of(), java.util.Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
