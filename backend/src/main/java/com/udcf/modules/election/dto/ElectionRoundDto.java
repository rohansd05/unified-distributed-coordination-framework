package com.udcf.modules.election.dto;

import com.udcf.modules.election.ElectionRound;

import java.time.Instant;

/**
 * Wire form of one election round. {@code leaderId} and {@code durationMillis} are null until
 * the round ends ELECTED; a TIMED_OUT round has neither (unmeasured, R7).
 *
 * <p>No dedicated test: a plain record. ElectionModuleTest and ElectionControllerTest check it.</p>
 */
public record ElectionRoundDto(
        long roundId,
        String algorithm,
        String trigger,
        int initiatorNodeId,
        Instant startedAt,
        String outcome,
        Integer leaderId,
        Double durationMillis
) {

    public static ElectionRoundDto from(ElectionRound round) {
        return round == null ? null : new ElectionRoundDto(round.roundId(), round.algorithm().name(),
                round.trigger().name(), round.initiatorNodeId(), round.startedAt(), round.outcome().name(),
                round.leaderId(), round.durationMillis());
    }
}
