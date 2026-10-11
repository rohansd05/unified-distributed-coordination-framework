package com.udcf.modules.rmi.dto;

/**
 * The one simulated aspect of Experiment 1 (docs/HANDOFF.md 6.11): the nodes are co-hosted in one
 * process. The page shows the Simulated badge with {@code reason} next to the node map; the
 * measured figures are real and carry no badge.
 *
 * <p>No dedicated test: a record; tested through RmiControllerTest.</p>
 */
public record CoHostingDto(boolean simulated, String reason) {
}
