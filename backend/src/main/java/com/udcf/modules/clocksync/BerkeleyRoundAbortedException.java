package com.udcf.modules.clocksync;

/**
 * Thrown by {@link ClockNodeService#runBerkeleyRound} when the daemon's own clock service stopped
 * (its node crashed) during the round: the round is aborted instead of reporting every peer as
 * unresponsive. The message is a plain sentence for the page.
 *
 * <p>No dedicated test: a constructor only; ClockNodeServiceTest and ClockSyncModuleTest check it.</p>
 */
public class BerkeleyRoundAbortedException extends RuntimeException {

    public BerkeleyRoundAbortedException(String message) {
        super(message);
    }
}
