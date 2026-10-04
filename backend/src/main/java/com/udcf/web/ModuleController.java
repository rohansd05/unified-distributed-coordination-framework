package com.udcf.web;

import com.udcf.core.module.ModuleRegistry;
import com.udcf.web.dto.ModuleDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The module list for the sidebar, sorted by lab number. */
@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private final ModuleRegistry registry;

    public ModuleController(ModuleRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<ModuleDto> modules() {
        return registry.modules().stream().map(ModuleDto::from).toList();
    }
}
