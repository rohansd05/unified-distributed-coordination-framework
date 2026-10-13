package com.udcf.modules.mpi.dto;

/**
 * One rank's block of a gather. The values themselves never go on the wire (up to 100000 doubles);
 * the checksum, computed by the root over what arrived, identifies them.
 *
 * <p>No dedicated test: a record; tested through MpiDtoMapperTest.</p>
 *
 * @param checksum SHA-256 of the block as it arrived; null for a hole
 * @param hole     the block did not arrive (never filled with zeros)
 */
public record GatheredBlockDto(int rank, int nodeId, int offset, int length, String checksum, boolean hole) {
}
