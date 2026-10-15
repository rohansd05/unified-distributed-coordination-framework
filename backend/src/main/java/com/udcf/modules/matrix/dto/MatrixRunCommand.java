package com.udcf.modules.matrix.dto;

/**
 * Request body of {@code POST /api/modules/matrix/runs}: one size per request. Every cluster node takes
 * part; the module checks every field at once (400 with field messages).
 *
 * <p>No dedicated test: a record; validation is tested in MatrixModuleTest and MatrixControllerTest.</p>
 *
 * @param size     the matrix size N, from udcf.matrix.min-size to floor(sqrt(udcf.mpi.max-payload-elements)) (required)
 * @param seed     seed of A and B, 0 to 2^53 - 1 (a JavaScript number holds it exactly); when left out, the run id is
 *                 used
 * @param rootNode the root; when left out, the live leader, else the lowest live node id
 */
public record MatrixRunCommand(Integer size, Long seed, Integer rootNode) {
}
