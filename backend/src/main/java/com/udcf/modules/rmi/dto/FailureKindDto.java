package com.udcf.modules.rmi.dto;

import com.udcf.modules.rmi.RemoteFailureKind;

/**
 * A failure kind and its plain-English description, so the page never hard-codes the text.
 *
 * <p>No dedicated test: a record; tested through RmiControllerTest.</p>
 */
public record FailureKindDto(RemoteFailureKind kind, String description) {
}
