package com.udcf.modules.replication.dto;

import java.util.List;

/**
 * One key across every replica, one cell per node in node order.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 */
public record KeyRowDto(String key, List<CellDto> cells) {
}
