package com.udcf.modules.election;

import com.udcf.modules.election.dto.ConsensusDto;
import com.udcf.modules.election.dto.ElectionOverviewDto;
import com.udcf.modules.election.dto.ElectionRoundDto;
import com.udcf.modules.election.dto.StartElectionCommand;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Experiment 4's REST API. Thin on purpose: validates, delegates to {@link ElectionModule},
 * maps to HTTP. Starting an election answers 202: the result follows as events and in the
 * overview. GETs never start a service. Errors are ProblemDetail bodies from the shared
 * GlobalExceptionHandler: 400 invalid input, 404 unknown node, 409 node down or module busy.
 * Crashing and recovering nodes use the cluster API.
 */
@RestController
@RequestMapping("/api/modules/election")
public class ElectionController {

    private final ElectionModule module;

    public ElectionController(ElectionModule module) {
        this.module = module;
    }

    /** Status, leader, every node's view, the consensus check, the rounds and the settings. */
    @GetMapping
    public ElectionOverviewDto overview() {
        return module.overview();
    }

    /** Starts Bully or Ring from a node. */
    @PostMapping("/elections")
    public ResponseEntity<ElectionRoundDto> start(@Valid @RequestBody StartElectionCommand command) {
        return ResponseEntity.accepted().body(module.startElection(command));
    }

    /** Do all live nodes agree on one live leader? */
    @GetMapping("/consensus")
    public ConsensusDto consensus() {
        return module.consensus();
    }
}
