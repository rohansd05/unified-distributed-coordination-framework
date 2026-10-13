package com.udcf.modules.rmi;

import java.io.ObjectInputFilter;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The JEP 290 filter installed on each node's exported {@link NodeEndpoint} (step E1b), through
 * the five-argument {@code UnicastRemoteObject.exportObject}. It checks the arguments a node
 * reads; nothing else in the JVM is affected.
 *
 * <p><b>What it allows.</b> Exactly {@link #ALLOWED_CLASSES}: {@link CallContext}. The other
 * arguments are a {@link String} (written as a string, which the JDK never class-checks) and a
 * primitive {@code int}. Everything else is rejected: the JDK pattern ends in {@code !*}, every
 * array class is rejected (the JDK pattern matches an array by its element type, so it would
 * allow {@code CallContext[]}), and a class check the pattern leaves {@code UNDECIDED} is turned
 * into {@code REJECTED} (the JDK leaves arrays of primitives undecided). Both were measured in
 * E1b. The limits come from {@code udcf.rmi.serial-filter}.</p>
 *
 * <p><b>Process-wide state is never touched:</b> no {@code ObjectInputFilter.Config.setSerialFilter},
 * no filter factory, no {@code jdk.serialFilter}. They are once-only and would affect Spring and
 * every other test. The registry keeps the JDK's built-in registry filter.</p>
 *
 * <p><b>Allowed arrays (step E9b).</b> {@link #allowing} builds a filter that also accepts the
 * listed array types (Experiment 9's {@code double[]}) up to its {@code maxarray}: an array is
 * allowed only if its type is listed and the JDK pattern did not reject it (the JDK leaves a
 * primitive array within {@code maxarray} undecided and rejects a longer one). Every other array is
 * still rejected. {@link #from} lists no array, so the E1b filter is unchanged.</p>
 *
 * <p><b>Not filtered.</b> The reply direction (a caller reading a result) and the size of a
 * String argument, which the JDK reads without a filter checkpoint; see the track file.</p>
 */
public final class RmiSerialFilter implements ObjectInputFilter {

    /** The application classes a node accepts as arguments (track file, E1a interfaces). */
    public static final List<String> ALLOWED_CLASSES = List.of(CallContext.class.getName());

    private final String pattern;
    private final ObjectInputFilter delegate;
    private final Set<Class<?>> allowedArrays;

    private RmiSerialFilter(String pattern, Set<Class<?>> allowedArrays) {
        this.pattern = pattern;
        this.delegate = ObjectInputFilter.Config.createFilter(pattern);
        this.allowedArrays = Set.copyOf(allowedArrays);
    }

    public static RmiSerialFilter from(RmiTransportProperties.SerialFilter limits) {
        Objects.requireNonNull(limits, "limits must not be null");
        return new RmiSerialFilter("maxdepth=" + limits.maxDepth()
                + ";maxrefs=" + limits.maxRefs()
                + ";maxbytes=" + limits.maxBytes()
                + ";maxarray=" + limits.maxArray()
                + ";" + String.join(";", ALLOWED_CLASSES)
                + ";!*", Set.of());
    }

    /**
     * A filter that allows {@code classes} and the array types in {@code arrayTypes} (each at most
     * {@code maxArray} long), with the depth and reference limits of {@code limits} and its own byte
     * and array limits; everything else is rejected.
     *
     * @param maxBytes most bytes read before a filter checkpoint (at least 1)
     * @param maxArray longest array (at least 1)
     */
    public static RmiSerialFilter allowing(RmiTransportProperties.SerialFilter limits, long maxBytes, long maxArray,
                                           List<String> classes, Set<Class<?>> arrayTypes) {
        Objects.requireNonNull(limits, "limits must not be null");
        Objects.requireNonNull(classes, "classes must not be null");
        Objects.requireNonNull(arrayTypes, "arrayTypes must not be null");
        if (maxBytes < 1 || maxArray < 1) {
            throw new IllegalArgumentException("maxBytes and maxArray must be >= 1, were " + maxBytes + " and " + maxArray);
        }
        for (Class<?> type : arrayTypes) {
            if (!type.isArray()) {
                throw new IllegalArgumentException(type.getName() + " is not an array type");
            }
        }
        StringBuilder pattern = new StringBuilder("maxdepth=" + limits.maxDepth()
                + ";maxrefs=" + limits.maxRefs()
                + ";maxbytes=" + maxBytes
                + ";maxarray=" + maxArray);
        classes.forEach(name -> pattern.append(';').append(name));
        pattern.append(";!*");
        return new RmiSerialFilter(pattern.toString(), arrayTypes);
    }

    /** The JDK filter pattern this filter delegates to. */
    public String pattern() {
        return pattern;
    }

    @Override
    public Status checkInput(FilterInfo info) {
        Status status = delegate.checkInput(info);
        Class<?> type = info.serialClass();
        if (type != null && type.isArray()) {
            // Only a listed array type within the JDK's limits; any other array, even of an allowed class, is rejected.
            return allowedArrays.contains(type) && status != Status.REJECTED ? Status.ALLOWED : Status.REJECTED;
        }
        if (type != null && status == Status.UNDECIDED) {
            return Status.REJECTED;   // a class nobody allowed
        }
        return status;
    }

    @Override
    public String toString() {
        return "RmiSerialFilter[" + pattern + "]";
    }
}
