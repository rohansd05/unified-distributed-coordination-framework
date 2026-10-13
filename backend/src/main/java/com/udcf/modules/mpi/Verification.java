package com.udcf.modules.mpi;

/**
 * What a collective proved from the data that actually arrived. A check that could not be made
 * (its transfer failed or was not attempted) is unknown, and an unknown is never "passed".
 *
 * @param passed             true: every check was made and matched; false: at least one mismatched; null:
 *                           nothing mismatched but something is unknown, or there was nothing to check
 * @param checked            remote parts the collective needed (one per rank other than the root)
 * @param matched            parts whose check matched
 * @param mismatched         parts whose check did not match
 * @param unknown            parts that could not be checked
 * @param wholeVectorMatches scatter: the verified chunks tile [0, n), so they concatenate to the original;
 *                           gather: the assembled vector equals the independently recomputed one; null for a
 *                           broadcast (passed covers it) and whenever a part is missing or unverified
 */
public record Verification(Boolean passed, int checked, int matched, int mismatched, int unknown, Boolean wholeVectorMatches) {

    public Verification {
        if (checked < 0 || matched < 0 || mismatched < 0 || unknown < 0 || matched + mismatched + unknown != checked) {
            throw new IllegalArgumentException("matched + mismatched + unknown must equal checked");
        }
    }

    /** The verification of a collective with these counts: false on any mismatch, null on any unknown or nothing checked. */
    public static Verification of(int matched, int mismatched, int unknown, Boolean wholeVectorMatches) {
        int checked = matched + mismatched + unknown;
        Boolean passed;
        if (mismatched > 0 || Boolean.FALSE.equals(wholeVectorMatches)) {
            passed = false;
        } else if (unknown > 0 || checked == 0) {
            passed = null;
        } else {
            passed = true;
        }
        return new Verification(passed, checked, matched, mismatched, unknown, wholeVectorMatches);
    }
}
