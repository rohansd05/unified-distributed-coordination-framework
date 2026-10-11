package com.udcf.modules.rmi.dto;

import com.udcf.modules.rmi.RemoteFailureKind;

import java.util.List;

/**
 * One node's RMI registry, listed over the network ({@code list()}, then a lookup per name).
 *
 * <p>No dedicated test: a record; tested through RmiDtoMapperTest and RmiControllerTest.</p>
 *
 * @param state          the node's state when it was listed
 * @param reachable      whether the listing was read completely
 * @param names          the bound names, sorted; null (unknown, never an empty list) when not reachable
 * @param failureKind    why it could not be listed; null when reachable
 * @param failureMessage the exception's own message, single line, cut; null if none or reachable
 */
public record RegistryDto(
        int nodeId,
        int registryPort,
        RmiNodeState state,
        boolean reachable,
        List<BoundNameDto> names,
        RemoteFailureKind failureKind,
        String failureDescription,
        String failureClass,
        String failureMessage,
        String failureCauseClass
) {
}
