package com.udcf.modules.mpi.dto;

import com.udcf.modules.mpi.CollectiveOperation;

/**
 * One collective the page can run.
 *
 * <p>No dedicated test: a record; tested through MpiModuleTest.</p>
 *
 * @param minimumCount the smallest count that is not refused now: 1 for a broadcast; for scatter and gather the
 *                     number of live nodes (one element per rank); null when every node is crashed
 */
public record OperationDto(CollectiveOperation operation, String description, Integer minimumCount) {
}
