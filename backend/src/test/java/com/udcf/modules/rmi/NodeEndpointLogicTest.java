package com.udcf.modules.rmi;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.NodePorts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The real work behind each remote method, without RMI. */
class NodeEndpointLogicTest {

    static final RmiProperties PROPERTIES = new RmiProperties(1000, 16, 10, 3000, 40);
    private static final CallContext FROM_NODE_2 = new CallContext(2, 0);

    // Computed outside Java (sha256sum + xxd, openssl and Python hashlib agree): round 1 hashes
    // the UTF-8 seed, every later round hashes the raw 32 bytes of the previous digest.
    private static final String NODE_1_ROUND_1 = "c4a528a0e3625afa791077dc34c38182cd15f399eb0d174f62eecfa2c044ffec";
    private static final String NODE_1_ROUND_2 = "a823b2eb47bbc0eee70091d36b9fca5280a831106d39b3fa3c1c86975ca8bf49";
    private static final String NODE_1_ROUND_3 = "cc78af155fd9d9e5f2ce073d04fc943d3e61a99e9f8be9a9f7712b2cfac90df3";
    private static final String NODE_2_ROUND_1000 = "6e1fc59b0c5e92c56d6275b418b4ddd3fbbd260d4a8dc928e0bc05639b05fef5";

    static NodeEndpointLogic logic(int nodeId, LamportClock clock) {
        return new NodeEndpointLogic(nodeId, 1100 + nodeId, NodeCapacity.FAST, clock, System::nanoTime,
                "21.0.12", PROPERTIES);
    }

    @Test
    @DisplayName("computeHash is chained SHA-256 over raw digest bytes, equal to vectors computed outside Java")
    void digestMatchesIndependentVectors() {
        NodeEndpointLogic node1 = logic(1, new LamportClock());
        assertThat(node1.computeHash(FROM_NODE_2, 1).digestHex()).isEqualTo(NODE_1_ROUND_1);
        assertThat(node1.computeHash(FROM_NODE_2, 2).digestHex()).isEqualTo(NODE_1_ROUND_2);
        assertThat(node1.computeHash(FROM_NODE_2, 3).digestHex()).isEqualTo(NODE_1_ROUND_3);
        assertThat(logic(2, new LamportClock()).computeHash(FROM_NODE_2, 1000).digestHex())
                .isEqualTo(NODE_2_ROUND_1000);
    }

    @Test
    @DisplayName("the same rounds on the same node give the same digest; another node gives another")
    void sameInputSameDigest() {
        HashResult first = logic(1, new LamportClock()).computeHash(FROM_NODE_2, 500);
        HashResult again = logic(1, new LamportClock()).computeHash(FROM_NODE_2, 500);
        HashResult other = logic(3, new LamportClock()).computeHash(FROM_NODE_2, 500);

        assertThat(again.digestHex()).isEqualTo(first.digestHex());
        assertThat(other.digestHex()).isNotEqualTo(first.digestHex());
        assertThat(first.seed()).isEqualTo("udcf-rmi-node-1").isEqualTo(NodeEndpointLogic.seedFor(1));
        assertThat(first.rounds()).isEqualTo(500);
        assertThat(first.nodeId()).isEqualTo(1);
    }

    @Test
    @DisplayName("the server time is measured around the hash loop only")
    void serverTimeMeasuredAroundTheLoop() {
        ScriptedNanoClock nanos = new ScriptedNanoClock(100, 1_000, 1_750);
        NodeEndpointLogic node = new NodeEndpointLogic(1, 1101, NodeCapacity.FAST, new LamportClock(), nanos,
                "21", PROPERTIES);

        assertThat(node.computeHash(FROM_NODE_2, 10).serverNanos()).isEqualTo(750);
        assertThat(nanos.reads()).isEqualTo(3);
    }

    @Test
    @DisplayName("rounds must be 1 to max-hash-rounds")
    void roundsBounds() {
        NodeEndpointLogic node = logic(1, new LamportClock());
        for (int rounds : new int[] {0, -1, PROPERTIES.maxHashRounds() + 1}) {
            assertThatThrownBy(() -> node.computeHash(FROM_NODE_2, rounds))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("rounds must be 1-1000, was " + rounds);
        }
        assertThat(node.computeHash(FROM_NODE_2, PROPERTIES.maxHashRounds()).rounds()).isEqualTo(1000);
    }

    @Test
    @DisplayName("echo returns the text unchanged; empty is allowed, null and over max-echo-length are not")
    void echoBounds() {
        NodeEndpointLogic node = logic(1, new LamportClock());
        assertThat(node.echo(FROM_NODE_2, "a\nb|c\t").text()).isEqualTo("a\nb|c\t");
        assertThat(node.echo(FROM_NODE_2, "").text()).isEmpty();
        assertThat(node.echo(FROM_NODE_2, "x".repeat(16)).text()).hasSize(16);
        assertThatThrownBy(() -> node.echo(FROM_NODE_2, "x".repeat(17)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("echo text must be at most 16 characters, was 17");
        assertThatThrownBy(() -> node.echo(FROM_NODE_2, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("echo text must not be null");
    }

    @Test
    @DisplayName("every method rejects a null call context")
    void nullContextRejected() {
        NodeEndpointLogic node = logic(1, new LamportClock());
        List<Runnable> calls = List.of(() -> node.ping(null), () -> node.nodeInfo(null),
                () -> node.echo(null, "x"), () -> node.computeHash(null, 1));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("call context must not be null");
        }
    }

    @Test
    @DisplayName("nodeInfo has exactly six fields: id, Lamport time, registry port, capacity, uptime, Java version")
    void nodeInfoFields() {
        ScriptedNanoClock nanos = new ScriptedNanoClock(0, 2_500_000);
        NodeEndpointLogic node = new NodeEndpointLogic(4, 1104, NodeCapacity.MEDIUM, new LamportClock(), nanos,
                "21.0.12", PROPERTIES);

        NodeInfo info = node.nodeInfo(FROM_NODE_2);

        assertThat(Arrays.stream(NodeInfo.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("nodeId", "lamportTime", "registryPort", "capacityProfile", "uptimeMillis",
                        "javaVersion");
        assertThat(info).isEqualTo(new NodeInfo(4, 2, 1104, "MEDIUM", 2, "21.0.12"));
    }

    @Test
    @DisplayName("nodeInfo carries no user name, home, working directory or computer name")
    void nodeInfoHasNoMachineIdentifyingData() throws Exception {
        LamportClock clock = new LamportClock();
        ClusterNode clusterNode = mock(ClusterNode.class);
        when(clusterNode.id()).thenReturn(1);
        when(clusterNode.ports()).thenReturn(new NodePorts(1101, 6001, 7001, 7101, 7201, 7301));
        when(clusterNode.capacity()).thenReturn(NodeCapacity.FAST);
        when(clusterNode.clock()).thenReturn(clock);
        NodeInfo info = NodeEndpointLogic.forNode(clusterNode, PROPERTIES, System::nanoTime).nodeInfo(FROM_NODE_2);

        List<String> machineValues = new ArrayList<>();
        for (String key : new String[] {"user.name", "user.home", "user.dir", "os.name"}) {
            machineValues.add(System.getProperty(key));
        }
        machineValues.add(System.getenv("COMPUTERNAME"));
        machineValues.add(System.getenv("HOSTNAME"));
        for (RecordComponent component : NodeInfo.class.getRecordComponents()) {
            if (component.getType() != String.class) {
                continue;
            }
            String value = (String) component.getAccessor().invoke(info);
            for (String machine : machineValues) {
                if (machine != null && machine.length() >= 3) {
                    assertThat(value).as(component.getName()).doesNotContainIgnoringCase(machine);
                }
            }
        }
        assertThat(info.javaVersion()).isEqualTo(Runtime.version().toString());
    }

    @Test
    @DisplayName("uptime counts from construction, by the injected clock")
    void uptimeFromClock() {
        ScriptedNanoClock nanos = new ScriptedNanoClock(1_000_000, 4_999_999);
        NodeEndpointLogic node = new NodeEndpointLogic(1, 1101, NodeCapacity.FAST, new LamportClock(), nanos,
                "21", PROPERTIES);

        assertThat(node.nodeInfo(FROM_NODE_2).uptimeMillis()).isEqualTo(3);
    }

    @Test
    @DisplayName("receive rule max(local, sent) + 1 on the call, then a tick for the reply, on every method")
    void lamportReceiveRule() {
        LamportClock clock = new LamportClock();
        NodeEndpointLogic node = logic(1, clock);
        for (int i = 0; i < 5; i++) {
            clock.tick();
        }
        PingReply behind = node.ping(new CallContext(2, 10));
        assertThat(behind.lamportTime()).isEqualTo(12);      // max(5, 10) + 1 = 11, reply 12
        assertThat(behind.nodeId()).isEqualTo(1);

        while (clock.current() < 20) {
            clock.tick();
        }
        assertThat(node.nodeInfo(new CallContext(2, 3)).lamportTime()).isEqualTo(22);    // max(20, 3) + 1, + 1
        assertThat(node.echo(new CallContext(2, 30), "x").lamportTime()).isEqualTo(32);
        assertThat(node.computeHash(new CallContext(2, 0), 1).lamportTime()).isEqualTo(34);
        assertThat(clock.current()).isEqualTo(34);
    }

    @Test
    @DisplayName("an invalid call changes nothing: the clock does not move")
    void invalidCallDoesNotMoveTheClock() {
        LamportClock clock = new LamportClock();
        NodeEndpointLogic node = logic(1, clock);

        assertThatThrownBy(() -> node.computeHash(new CallContext(2, 50), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node.echo(new CallContext(2, 50), "x".repeat(17)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node.ping(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.current()).isZero();
    }

    @Test
    @DisplayName("forNode uses the node's id, RMI port, capacity and its own shared clock")
    void forNodeUsesTheNodesOwnClock() {
        LamportClock shared = new LamportClock();
        ClusterNode clusterNode = mock(ClusterNode.class);
        when(clusterNode.id()).thenReturn(3);
        when(clusterNode.ports()).thenReturn(new NodePorts(1103, 6003, 7003, 7103, 7203, 7303));
        when(clusterNode.capacity()).thenReturn(NodeCapacity.SLOW);
        when(clusterNode.clock()).thenReturn(shared);

        NodeEndpointLogic node = NodeEndpointLogic.forNode(clusterNode, PROPERTIES, System::nanoTime);
        NodeInfo info = node.nodeInfo(new CallContext(1, 7));

        assertThat(node.nodeId()).isEqualTo(3);
        assertThat(info.registryPort()).isEqualTo(1103);
        assertThat(info.capacityProfile()).isEqualTo("SLOW");
        assertThat(info.lamportTime()).isEqualTo(9);
        assertThat(shared.current()).as("the node's own clock moved").isEqualTo(9);
    }

    @Test
    @DisplayName("the constructor rejects invalid ids, ports and missing collaborators")
    void constructorValidation() {
        LamportClock clock = new LamportClock();
        assertThatThrownBy(() -> new NodeEndpointLogic(0, 1101, NodeCapacity.FAST, clock, System::nanoTime, "21",
                PROPERTIES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeEndpointLogic(1, 0, NodeCapacity.FAST, clock, System::nanoTime, "21",
                PROPERTIES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeEndpointLogic(1, 65536, NodeCapacity.FAST, clock, System::nanoTime, "21",
                PROPERTIES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeEndpointLogic(1, 1101, null, clock, System::nanoTime, "21", PROPERTIES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NodeEndpointLogic(1, 1101, NodeCapacity.FAST, null, System::nanoTime, "21",
                PROPERTIES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NodeEndpointLogic(1, 1101, NodeCapacity.FAST, clock, System::nanoTime, "21",
                null)).isInstanceOf(NullPointerException.class);
    }
}
