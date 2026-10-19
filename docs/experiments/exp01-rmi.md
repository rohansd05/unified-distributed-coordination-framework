# Experiment 1: Client-Server Communication using Java RMI

Module id `rmi` · page `/experiments/1-rmi` · package `com.udcf.modules.rmi`

## Aim

Implement client-server communication using RPC or Java RMI.

## Theory

A remote procedure call (RPC) lets a program call a function that runs in another process, possibly on
another machine, with the same syntax as a local call. Java RMI (Remote Method Invocation) is Java's
object-oriented RPC. The server exports a *remote object* that implements an interface extending
`java.rmi.Remote` and binds it in an *RMI registry* under a name. A client looks the name up in the
registry and receives a *stub*, a local proxy object. Calling a method on the stub *marshals*
(serialises) the arguments, sends them over a TCP connection, runs the method on the server, and
unmarshals the reply.

The call looks local but is not. It costs a registry lookup, serialisation and a network round trip,
and it can fail in ways a local call cannot: the server may refuse the connection, time out or vanish
mid-call. RMI reports all of these as a `RemoteException`, so a caller must always handle them.

## How UDCF implements it

- **Remote interface.** `NodeEndpoint` (extends `Remote`, bound under the name `NodeEndpoint`) offers
  `ping`, `nodeInfo`, `echo` and `computeHash(rounds)`. Every method takes a `CallContext`
  (sender node id, sender Lamport time) as its first argument.
- **Server side.** `NodeEndpointLogic` does the real work; `computeHash` is chained SHA-256 from the
  seed `udcf-rmi-node-<id>`.
- **Transport.** `RmiNodeService` (a `NodeService`, started on each node) creates a registry with
  `LocateRegistry.createRegistry` on `127.0.0.1:ports().rmi()`, exports the endpoint on the same port,
  and binds it. A crash unexports the objects and closes accepted connections; recovery exports again.
  `RmiHostName` sets `java.rmi.server.hostname=127.0.0.1`; `RmiSerialFilter` is a JEP 290
  deserialisation filter; `LoopbackSocketFactory` adds connect and read timeouts.
- **Client side.** `RmiInvoker` makes and times one call through `RemoteEndpointLookup` (registry
  lookup, then the call; no stub caching) or `LocalEndpointLookup` (a direct call, for the comparison).
  `RemoteFailureClassifier` maps exceptions to kinds such as `CONNECTION_REFUSED`, `TIMED_OUT`,
  `NO_SUCH_OBJECT` or `NOT_BOUND`. `PayloadSizer` measures the serialised size of arguments and result.
- **Module and API.** `RmiModule` (lab 1) and `RmiController` under `/api/modules/rmi`: `POST /start`,
  `GET /registries`, `POST /calls`, `POST /comparisons`, `GET /calls`.
- **Ports.** RMI registry on `1100 + k` for node k: 1101 to 1105 locally (5 nodes), 1101 to 1103 in the
  public profile (3 nodes). Configuration `udcf.rmi`: call timeout 3000 ms, connect timeout 1000 ms.
- **Roles.** None. The calling node and the target node are chosen per request.
- **Events** (module `rmi`): `ENDPOINT_EXPORTED`, `CALL_SERVED` (on the target), `CALL_SUCCEEDED` and
  `CALL_FAILED` (on the caller), `COMPARISON_FINISHED`.

## Links to other experiments

- **L4, Lamport time on every message.** RMI has no message header, so the Lamport time travels in the
  `CallContext` argument. `RmiInvoker` ticks the caller's clock before sending; `NodeEndpointLogic`
  calls `clock.update(senderLamport)` and replies with `clock.tick()`; the caller applies `update` to the
  reply's Lamport time.
- **L6, Experiment 10 over Experiment 9 over Experiment 1.** Other modules plug extra remote objects into
  the same registry and port through `RmiExtraExport`: Experiment 9's `CollectiveEndpointExport` and
  Experiment 10's `MatrixEndpointExport`. Their transports look nodes up through `RmiModule.remoteLookup()`.
- **L5, event log to MapReduce.** `CALL_SUCCEEDED` events for remote calls carry `latencyMillis`, which
  the event log exporter writes as `latency=` for Experiment 7 to analyse.

## How to run it in the UI

1. In the sidebar, click **Client-Server via Java RMI** (`/experiments/1-rmi`). The page starts RMI on
   every live node by itself ("Starting RMI on every live node...").
2. In **Controls**, leave **Calling node** and **Target node** at their defaults, keep **Method** on
   `ping`, and click **Invoke call**.
3. Set **Method** to `computeHash`, keep the default **SHA-256 rounds**, and click **Invoke call**.
4. Click **Compare local and remote**.
5. Click **Refresh registries**.
6. Under **Crash or recover a node**, click **Crash node N** for the target node, then click **Invoke
   call** again. Finish with **Recover node N** for the same node.

## What to observe

- **"Nodes and the last call"** (live visualisation): a solid arrow from caller to target for a
  successful call. After the crash the arrow is dashed and labelled with the failure kind. A remote
  failure comes back as data with its exception, stage and time to failure.
- **Measurements:** "Last round trip", "Last registry lookup", "Arguments marshalled" and "Reply
  marshalled" show what a remote call costs. "Last comparison overhead" is remote minus direct time for
  the same method.
- **Registries:** each node's registry port and the name `NodeEndpoint` bound to its stub class. The
  client only ever holds this stub, never the real object.
- **Event log:** `CALL_SERVED` on the target and `CALL_SUCCEEDED` on the caller. The caller's Lamport
  time after the reply is larger than the target's reply time (Lamport rule 3).

## What is simulated

Only the co-hosting. The Simulated badge next to "Nodes and the last call" gives this reason: "All nodes
run inside one backend process on one computer, so they are separate nodes sharing a machine, not
separate machines. Every call on this page still goes through the target node's own RMI registry and a
stub over a real loopback socket, so the lookup, the marshalling and the round trip are real and
measured; only the network distance between the nodes is missing."

The local-versus-remote comparison is one measurement per side with no warm-up, so the first call after
a start can show a negative overhead. The page says so and shows the figures as measured.

## Tests that prove it

- Backend (`backend/src/test/java/com/udcf/modules/rmi`): `RmiEndToEndTest`, `RmiNodeServiceTest`,
  `RmiCrashCycleTest`, `RmiInvokerTest`, `NodeEndpointLogicTest`, `RemoteFailureClassifierTest`,
  `RmiFailureCharacterisationTest`, `RmiConcurrencyTest`, `RmiSerialFilterTest`,
  `LocalRemoteComparisonTest`, `RegistrySnapshotTest`, `RmiModuleTest`, `RmiControllerTest`, and the
  other test classes in the package.
- Integration: `L4LamportOnMessagesIntegrationTest` (`rmiCallCarriesTheCallersLamportTime`) and
  `L6MatrixOverCollectivesOverRmiIntegrationTest`.
- Frontend (`frontend/src/modules/rmi`): `RmiPage.test.jsx`, `useRmi.test.js`, `NodeMap.test.jsx`,
  `CallResult.test.jsx`, `RegistryList.test.jsx`, `contract.test.js`, and others.

## Viva questions

1. **What is the difference between RPC and RMI?**
   RPC calls a procedure in another process. RMI is Java's object-oriented form: it calls methods on a
   remote object through an interface, passes serialisable objects, and uses a registry and stubs.
2. **What is a stub?**
   A local proxy that implements the remote interface. It marshals the arguments, sends them to the
   server, waits for the reply and unmarshals it. The page lists the stub class bound in each registry.
3. **What does the RMI registry do?**
   It is a name service: the server binds a remote object under a name, and a client looks the name up
   to get a stub. Each UDCF node runs its own registry on port `1100 + k`.
4. **Why must every remote method declare `RemoteException`?**
   Because the network can fail: the connection can be refused or time out, or the server can die
   mid-call. The caller is forced to handle that. UDCF classifies each failure into a kind and shows it.
5. **What is marshalling, and why does UDCF measure it?**
   Converting arguments and results into bytes (Java serialisation) for the wire. Its size is part of
   the cost of a remote call, so the page shows the marshalled size of the arguments and the reply.
6. **Why is a remote call slower than a local call?**
   It adds a registry lookup, serialisation, a socket round trip and deserialisation. "Compare local and
   remote" measures the same method both ways.
7. **How is Lamport time carried over RMI?**
   As the first argument of every method: a `CallContext` with the sender's id and Lamport time. The
   server applies `update`, and the reply carries the server's time back to the caller.
8. **Why set `java.rmi.server.hostname=127.0.0.1`?**
   So the stubs the registry hands out point at the loopback address the nodes listen on, rather than at
   a host name that may resolve to another network interface.
