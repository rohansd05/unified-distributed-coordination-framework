package com.udcf.modules.rmi;

import java.io.ObjectInputFilter;
import java.util.List;
import java.util.Objects;

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
 * <p><b>Not filtered.</b> The reply direction (a caller reading a result) and the size of a
 * String argument, which the JDK reads without a filter checkpoint; see the track file.</p>
 */
public final class RmiSerialFilter implements ObjectInputFilter {

    /** The application classes a node accepts as arguments (track file, E1a interfaces). */
    public static final List<String> ALLOWED_CLASSES = List.of(CallContext.class.getName());

    private final String pattern;
    private final ObjectInputFilter delegate;

    private RmiSerialFilter(String pattern) {
        this.pattern = pattern;
        this.delegate = ObjectInputFilter.Config.createFilter(pattern);
    }

    public static RmiSerialFilter from(RmiTransportProperties.SerialFilter limits) {
        Objects.requireNonNull(limits, "limits must not be null");
        return new RmiSerialFilter("maxdepth=" + limits.maxDepth()
                + ";maxrefs=" + limits.maxRefs()
                + ";maxbytes=" + limits.maxBytes()
                + ";maxarray=" + limits.maxArray()
                + ";" + String.join(";", ALLOWED_CLASSES)
                + ";!*");
    }

    /** The JDK filter pattern this filter delegates to. */
    public String pattern() {
        return pattern;
    }

    @Override
    public Status checkInput(FilterInfo info) {
        Status status = delegate.checkInput(info);
        Class<?> type = info.serialClass();
        if (type != null && (type.isArray() || status == Status.UNDECIDED)) {
            return Status.REJECTED;   // an array (even of an allowed class), or a class nobody allowed
        }
        return status;
    }

    @Override
    public String toString() {
        return "RmiSerialFilter[" + pattern + "]";
    }
}
