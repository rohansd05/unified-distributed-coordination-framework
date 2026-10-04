package com.udcf.web;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterResetService;
import com.udcf.web.dto.ClusterDto;
import com.udcf.web.dto.NodeDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shared cluster: inspect nodes, crash and recover them on every protocol at once
 * (R10), and reset everything to a clean slate.
 */
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    private final Cluster cluster;
    private final ClusterResetService resetService;

    public ClusterController(Cluster cluster, ClusterResetService resetService) {
        this.cluster = cluster;
        this.resetService = resetService;
    }

    @GetMapping
    public ClusterDto cluster() {
        return ClusterDto.from(cluster);
    }

    @GetMapping("/nodes/{id}")
    public NodeDto node(@PathVariable int id) {
        return NodeDto.from(cluster.node(id));
    }

    /** 409 if the node is already crashed. */
    @PostMapping("/nodes/{id}/crash")
    public NodeDto crash(@PathVariable int id) {
        if (!cluster.crash(id)) {
            throw new NodeStateConflictException(id, "Node " + id + " is already crashed");
        }
        return NodeDto.from(cluster.node(id));
    }

    /** 409 if the node is already up. */
    @PostMapping("/nodes/{id}/recover")
    public NodeDto recover(@PathVariable int id) {
        if (!cluster.recover(id)) {
            throw new NodeStateConflictException(id, "Node " + id + " is already up");
        }
        return NodeDto.from(cluster.node(id));
    }

    /** Clean slate; 409 while a module is busy. */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        resetService.reset();
        return ResponseEntity.noContent().build();
    }
}
