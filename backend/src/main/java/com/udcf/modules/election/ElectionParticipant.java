package com.udcf.modules.election;

public interface ElectionParticipant {
    int getNodeId();
    boolean isCrashed();
    Integer getCoordinatorId();
}
