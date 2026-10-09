package com.udcf.modules.faulttolerance;

/**
 * What a node does with an epoch it receives, compared with the highest epoch it knows
 * ({@link EpochRules#judge(long, long)}).
 *
 * <p>No dedicated test: an enum without behaviour, covered by EpochRulesTest (R6).</p>
 */
public enum EpochVerdict {

    /** The incoming epoch is below the known one: it comes from a superseded primary. */
    REFUSE_STALE,

    /** The same epoch: the same term, so the message competes normally. */
    ACCEPT,

    /** A higher epoch: the node raises its own to it, and a primary on the lower epoch demotes. */
    ADOPT_HIGHER
}
