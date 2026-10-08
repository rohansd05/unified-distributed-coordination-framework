package com.udcf.modules.clocksync;

import com.udcf.modules.clocksync.dto.BerkeleyRoundAcceptedDto;
import com.udcf.modules.clocksync.dto.BerkeleyRoundCommand;
import com.udcf.modules.clocksync.dto.CausalVerificationDto;
import com.udcf.modules.clocksync.dto.ClockSyncOverviewDto;
import com.udcf.modules.clocksync.dto.LocalEventCommand;
import com.udcf.modules.clocksync.dto.LocalEventResult;
import com.udcf.modules.clocksync.dto.NodeDriftResponseDto;
import com.udcf.modules.clocksync.dto.NodeDriftUpdateCommand;
import com.udcf.modules.clocksync.dto.RandomTrafficCommand;
import com.udcf.modules.clocksync.dto.SendLamportResult;
import com.udcf.modules.clocksync.dto.SendMessageCommand;
import com.udcf.modules.clocksync.dto.TimelineResponseDto;
import com.udcf.modules.clocksync.dto.TrafficSessionDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Experiment 3 (Clock Synchronization).
 *
 * <p>Exposes endpoints for Lamport logical clock tracking, simulated drift adjustment,
 * UDP message transmission, asynchronous random traffic bursts, Berkeley synchronization rounds,
 * causal invariant verification, and timeline query.</p>
 */
@RestController
@RequestMapping("/api/modules/clocksync")
public class ClockSyncController {

    private final ClockSyncModule module;

    public ClockSyncController(ClockSyncModule module) {
        this.module = module;
    }

    /** Module overview with per-node Lamport clocks, drift models, daemon selection, limits, and notes. */
    @GetMapping
    public ClockSyncOverviewDto overview() {
        return module.overview();
    }

    /** Records a local event on a node, advancing its Lamport clock (Rule 1). */
    @PostMapping("/nodes/{id}/local-events")
    public LocalEventResult recordLocalEvent(@PathVariable int id,
                                             @RequestBody(required = false) LocalEventCommand command) {
        return module.recordLocalEvent(id, command);
    }

    /** Sends a point-to-point Lamport message over UDP (Rule 2). Supports UDP honesty. */
    @PostMapping("/messages")
    public SendLamportResult sendMessage(@Valid @RequestBody SendMessageCommand command) {
        return module.sendLamportMessage(command);
    }

    /** Initiates an asynchronous burst of random Lamport message traffic between live nodes. */
    @PostMapping("/traffic")
    public ResponseEntity<TrafficSessionDto> startTraffic(@RequestBody(required = false) RandomTrafficCommand command) {
        return ResponseEntity.accepted().body(module.startRandomTraffic(command));
    }

    /** Initiates an asynchronous Berkeley synchronization round coordinated by the time daemon. */
    @PostMapping("/berkeley-rounds")
    public ResponseEntity<BerkeleyRoundAcceptedDto> runBerkeleyRound(@RequestBody(required = false) BerkeleyRoundCommand command) {
        return ResponseEntity.accepted().body(module.runBerkeleyRound(command));
    }

    /** Modifies the simulated physical drift offset or drift rate for a node. */
    @PutMapping("/nodes/{id}/drift")
    public NodeDriftResponseDto updateDrift(@PathVariable int id,
                                            @RequestBody(required = false) NodeDriftUpdateCommand command) {
        return module.updateDrift(id, command);
    }

    /** Checks causal invariants (Lamport Rule 3 and local monotonicity) over the retained event log. */
    @GetMapping("/verification")
    public CausalVerificationDto verification() {
        return module.verifyCausalInvariants();
    }

    /** Returns retained causal events with message IDs for space-time diagram visualization. */
    @GetMapping("/timeline")
    public TimelineResponseDto timeline(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return module.timeline(limit);
    }
}
