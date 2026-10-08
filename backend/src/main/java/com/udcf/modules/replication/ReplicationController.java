package com.udcf.modules.replication;

import com.udcf.modules.replication.dto.AntiEntropyCommand;
import com.udcf.modules.replication.dto.AntiEntropyDto;
import com.udcf.modules.replication.dto.InjectStaleCommand;
import com.udcf.modules.replication.dto.InjectionDto;
import com.udcf.modules.replication.dto.NodeReplicaDto;
import com.udcf.modules.replication.dto.ReadDto;
import com.udcf.modules.replication.dto.ReplicasDto;
import com.udcf.modules.replication.dto.ReplicationOverviewDto;
import com.udcf.modules.replication.dto.WriteCommand;
import com.udcf.modules.replication.dto.WriteDto;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Experiment 5's REST API. Thin on purpose: validates, delegates to {@link ReplicationModule},
 * maps to HTTP. Writes answer 200 for both models: an asynchronous write is confirmed when it
 * returns, with its pushes PENDING. GETs never start a service, change a role or take over.
 * Errors are ProblemDetail bodies from the shared GlobalExceptionHandler: 400 invalid input or the
 * primary as a target, 404 unknown node, 409 node down, node already in that state, or module busy.
 */
@RestController
@RequestMapping("/api/modules/replication")
public class ReplicationController {

    private final ReplicationModule module;

    public ReplicationController(ReplicationModule module) {
        this.module = module;
    }

    /** Status, roles, settings, texts, per-node state, the health table and the latest results. */
    @GetMapping
    public ReplicationOverviewDto overview() {
        return module.overview();
    }

    /** A client write through the primary, SYNCHRONOUS or ASYNCHRONOUS. */
    @PostMapping("/writes")
    public WriteDto write(@Valid @RequestBody WriteCommand command) {
        return module.write(command);
    }

    /** One key read from one replica over TCP, compared with the reference replica. */
    @GetMapping("/nodes/{nodeId}/values")
    public ReadDto read(@PathVariable int nodeId, @RequestParam String key) {
        return module.read(nodeId, key);
    }

    /** Every replica's store side by side, read over TCP. */
    @GetMapping("/replicas")
    public ReplicasDto replicas() {
        return module.replicas();
    }

    /** Crashes a backup (never the primary) on every protocol. */
    @PostMapping("/nodes/{nodeId}/crash")
    public NodeReplicaDto crash(@PathVariable int nodeId) {
        return module.crashBackup(nodeId);
    }

    /** Recovers a crashed node; a pending takeover runs here. */
    @PostMapping("/nodes/{nodeId}/recover")
    public NodeReplicaDto recover(@PathVariable int nodeId) {
        return module.recover(nodeId);
    }

    /** Pushes the primary's whole store to one backup. */
    @PostMapping("/anti-entropy")
    public AntiEntropyDto antiEntropy(@Valid @RequestBody AntiEntropyCommand command) {
        return module.antiEntropy(command);
    }

    /** Delivers an older version of a key to one backup, after the newer one. */
    @PostMapping("/stale-injections")
    public InjectionDto injectStale(@Valid @RequestBody InjectStaleCommand command) {
        return module.injectStale(command);
    }
}
