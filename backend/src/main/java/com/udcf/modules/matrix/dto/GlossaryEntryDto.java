package com.udcf.modules.matrix.dto;

/**
 * One timing figure of Experiment 10, named and defined, with where it was measured or that it is derived.
 *
 * <p>No dedicated test: a record; the entries are tested in MatrixTextsTest.</p>
 *
 * @param id         the DTO field the entry describes
 * @param label      a short sentence-case name
 * @param kind       measured on the root, measured on the node, or derived
 * @param definition what exactly the figure covers
 */
public record GlossaryEntryDto(String id, String label, Kind kind, String definition) {

    /**
     * Where a figure comes from.
     *
     * <p>No dedicated test: an enum without behaviour (R6).</p>
     */
    public enum Kind {
        /** Measured on the root node's clock. */
        MEASURED_ON_ROOT,
        /** Measured on the computing node, around its multiply only. */
        MEASURED_ON_NODE,
        /** Computed from measured figures; never measured itself. */
        DERIVED
    }
}
