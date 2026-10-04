package com.udcf.core.module;

/**
 * What a module is doing, as shown in the sidebar.
 *
 * <ul>
 *   <li>{@code IDLE}: nothing active.</li>
 *   <li>{@code RUNNING}: the module's services are active.</li>
 *   <li>{@code BUSY}: a long-running action is in progress; a second one gets HTTP 409.</li>
 *   <li>{@code ERROR}: the last action failed.</li>
 * </ul>
 *
 * <p>No dedicated test: a plain enum with no behaviour.</p>
 */
public enum ModuleStatus {
    IDLE,
    RUNNING,
    BUSY,
    ERROR
}
