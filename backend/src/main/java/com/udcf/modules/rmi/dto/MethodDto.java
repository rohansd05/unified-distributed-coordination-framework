package com.udcf.modules.rmi.dto;

import com.udcf.modules.rmi.RmiMethod;

import java.util.List;

/**
 * One NodeEndpoint method the page can call.
 *
 * <p>No dedicated test: a record; tested through RmiControllerTest.</p>
 *
 * @param parameters the arguments it takes besides the call context; empty for PING and NODE_INFO
 */
public record MethodDto(RmiMethod method, String description, List<ParameterDto> parameters) {
}
