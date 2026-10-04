package com.udcf.core.module;

/**
 * Thrown when a module is asked to start an action while another is in progress. The web
 * layer maps it to HTTP 409.
 *
 * <p>No dedicated test: a constructor and accessors only.</p>
 */
public class ModuleBusyException extends RuntimeException {

    private final String moduleId;
    private final String actionInProgress;

    public ModuleBusyException(String moduleId, String actionInProgress) {
        super("Module '" + moduleId + "' is busy: '" + actionInProgress + "' is in progress");
        this.moduleId = moduleId;
        this.actionInProgress = actionInProgress;
    }

    public String moduleId() {
        return moduleId;
    }

    public String actionInProgress() {
        return actionInProgress;
    }
}
