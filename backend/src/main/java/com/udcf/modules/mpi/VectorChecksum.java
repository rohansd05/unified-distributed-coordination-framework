package com.udcf.modules.mpi;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The checksum Experiment 9 compares: SHA-256 over the elements' IEEE-754 bits
 * ({@link Double#doubleToLongBits}, so every NaN counts as the canonical NaN), each written as
 * 8 big-endian bytes, in order; lower-case hex. The same vector always has the same checksum on
 * every machine, and any change of a value or of the order changes it.
 */
public final class VectorChecksum {

    private VectorChecksum() {
    }

    /** The checksum of {@code values}: 64 lower-case hex characters. */
    public static String of(double[] values) {
        Objects.requireNonNull(values, "values must not be null");
        MessageDigest digest = sha256();
        ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES);   // big-endian by default
        for (double value : values) {
            bytes.clear();
            bytes.putLong(Double.doubleToLongBits(value));
            digest.update(bytes.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Whether {@code text} has the form of a checksum: 64 lower-case hex characters. */
    public static boolean isChecksum(String text) {
        return text != null && text.length() == 64 && text.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform must provide SHA-256", e);
        }
    }
}
