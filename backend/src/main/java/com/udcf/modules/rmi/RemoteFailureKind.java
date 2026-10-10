package com.udcf.modules.rmi;

/**
 * Why a remote call failed, in plain English, as decided by {@link RemoteFailureClassifier}.
 *
 * <p>No dedicated test: an enum whose only behaviour is its description, covered by
 * RemoteFailureClassifierTest (R6).</p>
 */
public enum RemoteFailureKind {

    CONNECTION_REFUSED("Nothing accepted the connection at the node's RMI port: the node is down "
            + "or its endpoint is not exported."),
    CONNECTION_FAILED("The connection to the node's RMI port could not be set up: an I/O error "
            + "happened while connecting."),
    TIMED_OUT("The node did not answer within the call timeout."),
    NO_SUCH_OBJECT("The stub points at a remote object that is no longer exported, for example "
            + "after the node crashed and came back."),
    NOT_BOUND("The node's registry has no entry under that name."),
    MARSHAL_FAILED("The call's arguments could not be written to the connection."),
    UNMARSHAL_FAILED("The reply could not be read: the connection broke during the call, or the "
            + "data could not be deserialized."),
    METHOD_THREW("The call reached the node, and the method itself threw an exception, which RMI "
            + "carried back to the caller."),
    OTHER("Another failure; see the exception class and message.");

    private final String description;

    RemoteFailureKind(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
