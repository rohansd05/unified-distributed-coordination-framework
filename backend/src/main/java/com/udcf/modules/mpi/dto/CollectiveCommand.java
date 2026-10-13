package com.udcf.modules.mpi.dto;

import com.udcf.modules.mpi.CollectiveOperation;

/**
 * Request body of {@code POST /api/modules/mpi/collectives}. Every cluster node takes part; the
 * module checks every field at once (400 with field messages).
 *
 * <p>No dedicated test: a record; validation is tested in MpiModuleTest and MpiControllerTest.</p>
 *
 * @param operation BROADCAST, SCATTER or GATHER (required)
 * @param count     vector length, 1 to max-payload-elements (required)
 * @param seed      seed of the generated data, 0 to 2^53 - 1 (a JavaScript number holds it exactly);
 *                  when left out, the collective's id is used
 * @param rootNode  the root; when left out, the live leader, else the lowest live node id
 */
public record CollectiveCommand(CollectiveOperation operation, Integer count, Long seed, Integer rootNode) {
}
