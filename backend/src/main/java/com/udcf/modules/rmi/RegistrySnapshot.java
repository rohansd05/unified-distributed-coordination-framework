package com.udcf.modules.rmi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The contents of one node's RMI registry: every bound name and the class of the stub bound
 * under it. Step E1b fills it from the registry; the page lists one per node.
 *
 * @param nodeId       the node whose registry this is
 * @param registryPort the registry's port
 * @param names        the bound names, sorted by name, no duplicates
 */
public record RegistrySnapshot(int nodeId, int registryPort, List<BoundName> names) {

    /**
     * One registry entry.
     *
     * @param name          the bound name
     * @param stubClassName the class of the stub bound under it
     */
    public record BoundName(String name, String stubClassName) {

        public BoundName {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (stubClassName == null || stubClassName.isBlank()) {
                throw new IllegalArgumentException("stubClassName must not be blank");
            }
        }
    }

    public RegistrySnapshot {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        if (registryPort < 1 || registryPort > 65535) {
            throw new IllegalArgumentException("registryPort must be 1-65535, was " + registryPort);
        }
        Objects.requireNonNull(names, "names must not be null");
        List<BoundName> sorted = new ArrayList<>(names.size());
        Set<String> seen = new HashSet<>();
        for (BoundName entry : names) {
            Objects.requireNonNull(entry, "a bound name must not be null");
            if (!seen.add(entry.name())) {
                throw new IllegalArgumentException("name bound twice: " + entry.name());
            }
            sorted.add(entry);
        }
        sorted.sort(Comparator.comparing(BoundName::name));
        names = List.copyOf(sorted);
    }

    public Optional<BoundName> find(String name) {
        return names.stream().filter(entry -> entry.name().equals(name)).findFirst();
    }

    public boolean contains(String name) {
        return find(name).isPresent();
    }
}
