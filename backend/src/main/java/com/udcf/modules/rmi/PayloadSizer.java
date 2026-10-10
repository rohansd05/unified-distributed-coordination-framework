package com.udcf.modules.rmi;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Measures the Java serialization size of call arguments and results.
 *
 * <p><b>What the number is.</b> Java serialization size (java.io ObjectOutputStream, stream
 * header included); not the RMI wire bytes; primitive parameters are sized as primitives, as RMI
 * marshals them. The values are written in order into one {@link ObjectOutputStream}, which is
 * then flushed, and the bytes are counted (and discarded). An {@link Integer} or {@link Long} is
 * written with {@code writeInt} or {@code writeLong} (a primitive {@code int} or {@code long}
 * parameter, as RMI marshals it); every other value with {@code writeObject}. The RMI wire adds
 * the call header, the object identifier and the method hash, which this does not count.</p>
 */
public final class PayloadSizer {

    /** The label to show next to every size this class measures. */
    public static final String LABEL = "Java serialization size (java.io ObjectOutputStream, stream header "
            + "included); not the RMI wire bytes; primitive parameters are sized as primitives, as RMI "
            + "marshals them";

    private PayloadSizer() {
    }

    /**
     * Bytes written by serializing {@code values}, in order, into one stream.
     *
     * @throws IllegalArgumentException if a value is null, is not {@link Serializable}, or holds
     *                                  something that cannot be serialized
     */
    public static long sizeOf(Object... values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        CountingStream counter = new CountingStream();
        try (ObjectOutputStream out = new ObjectOutputStream(counter)) {
            for (Object value : values) {
                write(out, value);
            }
            out.flush();
        } catch (NotSerializableException e) {
            throw new IllegalArgumentException("not serializable: " + e.getMessage(), e);
        } catch (IOException e) {
            // The stream writes to memory, so only a serialization problem can end up here.
            throw new IllegalArgumentException("could not serialize: " + e.getMessage(), e);
        }
        return counter.count;
    }

    private static void write(ObjectOutputStream out, Object value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("a value to size must not be null");
        }
        if (value instanceof Integer i) {
            out.writeInt(i);
        } else if (value instanceof Long l) {
            out.writeLong(l);
        } else if (value instanceof Serializable) {
            out.writeObject(value);
        } else {
            throw new IllegalArgumentException("not serializable: " + value.getClass().getName());
        }
    }

    /** Counts the bytes written to it and keeps none. */
    private static final class CountingStream extends OutputStream {

        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }
}
