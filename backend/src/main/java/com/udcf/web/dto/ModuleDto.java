package com.udcf.web.dto;

import com.udcf.core.module.ExperimentModule;

/** Wire form of one experiment module, for the sidebar. */
public record ModuleDto(String id, int labNumber, String title, String status) {

    public static ModuleDto from(ExperimentModule module) {
        return new ModuleDto(module.id(), module.labNumber(), module.title(), module.status().name());
    }
}
