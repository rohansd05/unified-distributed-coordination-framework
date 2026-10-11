package com.udcf.modules.rmi.dto;

/**
 * One entry of a node's RMI registry.
 *
 * <p>No dedicated test: a record; tested through RmiDtoMapperTest.</p>
 *
 * @param stubClassName the class of the object a lookup of {@code name} returned
 */
public record BoundNameDto(String name, String stubClassName) {
}
