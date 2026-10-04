package com.udcf.core.module;

/**
 * Thrown when no module has the requested id.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class UnknownModuleException extends RuntimeException {

    private final String moduleId;

    public UnknownModuleException(String moduleId) {
        super("No module with id '" + moduleId + "'");
        this.moduleId = moduleId;
    }

    public String moduleId() {
        return moduleId;
    }
}
