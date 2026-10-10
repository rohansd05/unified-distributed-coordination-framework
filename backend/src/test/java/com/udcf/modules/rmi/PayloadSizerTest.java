package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Expected sizes are derived from the Java Object Serialization Specification, not from Java:
 * stream header = STREAM_MAGIC (2) + STREAM_VERSION (2) = 4 bytes; a short string = TC_STRING (1)
 * + length (2) + modified UTF-8 bytes; primitives go into a block-data record = TC_BLOCKDATA (1)
 * + length (1) + the bytes (int 4, long 8).
 */
class PayloadSizerTest {

    private static final int HEADER = 4;
    private static final int BLOCK_HEADER = 2;

    @Test
    @DisplayName("strings: header + TC_STRING + 2-byte length + modified UTF-8 bytes")
    void stringSizesFromTheSpec() {
        assertThat(PayloadSizer.sizeOf("abc")).isEqualTo(HEADER + 1 + 2 + 3);     // 10
        assertThat(PayloadSizer.sizeOf("\u00e9")).isEqualTo(HEADER + 1 + 2 + 2);  // 9: e-acute is 2 bytes
        assertThat(PayloadSizer.sizeOf("abc", "def")).isEqualTo(HEADER + 6 + 6);  // one stream, one header
    }

    @Test
    @DisplayName("Integer and Long are sized as primitive int and long in a block-data record")
    void primitivesFromTheSpec() {
        assertThat(PayloadSizer.sizeOf(7)).isEqualTo(HEADER + BLOCK_HEADER + 4);          // 10
        assertThat(PayloadSizer.sizeOf(7L)).isEqualTo(HEADER + BLOCK_HEADER + 8);         // 14
        assertThat(PayloadSizer.sizeOf(7, 7L)).isEqualTo(HEADER + BLOCK_HEADER + 4 + 8);  // 18: one block
    }

    @Test
    @DisplayName("computeHash's arguments are the context plus one 6-byte primitive int block, far below a boxed Integer")
    void computeHashArgumentsSizePrimitiveRounds() throws IOException {
        CallContext context = new CallContext(1, 42);
        long contextAlone = PayloadSizer.sizeOf(RmiCall.ping().arguments(context).toArray());
        long withRounds = PayloadSizer.sizeOf(RmiCall.computeHash(1000).arguments(context).toArray());

        assertThat(withRounds - contextAlone).isEqualTo(BLOCK_HEADER + 4);

        long boxed = javaSerializedSize(context, Integer.valueOf(1000));
        assertThat(boxed - contextAlone).as("a boxed Integer adds its class descriptors").isGreaterThan(60);
    }

    @Test
    @DisplayName("a longer string grows the size by exactly the extra characters")
    void sizeGrowsWithLength() {
        assertThat(PayloadSizer.sizeOf("x".repeat(100)) - PayloadSizer.sizeOf("x".repeat(10))).isEqualTo(90);
    }

    @Test
    @DisplayName("a value that is not serializable, or holds one, is rejected")
    void rejectsNonSerializable() {
        assertThatThrownBy(() -> PayloadSizer.sizeOf(new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not serializable: java.lang.Object");
        assertThatThrownBy(() -> PayloadSizer.sizeOf(new Holder(new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not serializable");
    }

    @Test
    @DisplayName("null values are rejected")
    void rejectsNull() {
        assertThatThrownBy(() -> PayloadSizer.sizeOf((Object) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayloadSizer.sizeOf((Object[]) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayloadSizer.sizeOf("a", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the label says exactly what is measured")
    void label() {
        assertThat(PayloadSizer.LABEL).isEqualTo("Java serialization size (java.io ObjectOutputStream, stream header "
                + "included); not the RMI wire bytes; primitive parameters are sized as primitives, as RMI marshals them");
    }

    /** Serializable, but its field is not. */
    private record Holder(Object inner) implements Serializable {
    }

    /** What writeObject would cost for every value, boxed Integer included: the alternative C1 rules out. */
    private static long javaSerializedSize(Object... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            for (Object value : values) {
                out.writeObject(value);
            }
        }
        return bytes.size();
    }
}
