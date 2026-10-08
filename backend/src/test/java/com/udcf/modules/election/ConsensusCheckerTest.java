package com.udcf.modules.election;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConsensusCheckerTest {

    record MockParticipant(int id, boolean crashed, Integer coord) implements ElectionParticipant {
        @Override public int getNodeId() { return id; }
        @Override public boolean isCrashed() { return crashed; }
        @Override public Integer getCoordinatorId() { return coord; }
    }

    @Test
    void testConsensus() {
        List<ElectionParticipant> nodes = List.of(
            new MockParticipant(1, false, 5),
            new MockParticipant(2, false, 5),
            new MockParticipant(3, true, 2)
        );
        ConsensusResult result = ConsensusChecker.check(nodes);
        assertTrue(result.reached());
        assertEquals(5, result.coordinatorId());
        assertTrue(result.disagreeingNodes().isEmpty());
    }

    @Test
    void testDisagreement() {
        List<ElectionParticipant> nodes = List.of(
            new MockParticipant(1, false, 5),
            new MockParticipant(2, false, 4),
            new MockParticipant(3, false, null)
        );
        ConsensusResult result = ConsensusChecker.check(nodes);
        assertFalse(result.reached());
        assertNull(result.coordinatorId());
        assertEquals(List.of(2, 3), result.disagreeingNodes());
    }

    @Test
    void testAllCrashed() {
        List<ElectionParticipant> nodes = List.of(
            new MockParticipant(1, true, 5),
            new MockParticipant(2, true, 5)
        );
        ConsensusResult result = ConsensusChecker.check(nodes);
        assertTrue(result.reached());
        assertNull(result.coordinatorId());
        assertTrue(result.disagreeingNodes().isEmpty());
    }

    @Test
    void testUnassignedLeader() {
        List<ElectionParticipant> nodes = List.of(
            new MockParticipant(1, false, null),
            new MockParticipant(2, false, null)
        );
        ConsensusResult result = ConsensusChecker.check(nodes);
        assertFalse(result.reached());
        assertNull(result.coordinatorId());
        assertEquals(List.of(1, 2), result.disagreeingNodes());
    }

    @Test
    void testDeadLeaderIsStillConsensusIfAgreed() {
        // Consensus checker just checks agreement. If everyone agrees the leader is 5, and 5 is dead, 
        // they still agree. But actually, "dead leader" might mean the coordinator is a crashed node.
        // Wait, if the coordinator is crashed, the node itself doesn't report its own coordinator,
        // but other nodes report the crashed node as coordinator. Does consensus checker care if the coordinator is dead?
        // The algorithm says: "check consensus. ignores crashed nodes."
        List<ElectionParticipant> nodes = List.of(
            new MockParticipant(1, false, 5),
            new MockParticipant(2, false, 5),
            new MockParticipant(5, true, 5)
        );
        ConsensusResult result = ConsensusChecker.check(nodes);
        assertTrue(result.reached());
    }

    @Test
    void testDisagreementNamesNodes() {
        List<ElectionParticipant> nodesList = List.of(
            new MockParticipant(1, false, 2),
            new MockParticipant(2, false, 3)
        );
        ConsensusResult result = ConsensusChecker.check(nodesList);
        assertFalse(result.reached());
        assertEquals(List.of(2), result.disagreeingNodes());
        // Disagreement names the specific nodes that don't match the baseline
    }

    @Test
    void testNoLiveNodes() {
        List<ElectionParticipant> nodesList = List.of(
            new MockParticipant(1, true, 1),
            new MockParticipant(2, true, 2)
        );
        ConsensusResult result = ConsensusChecker.check(nodesList);
        assertTrue(result.reached());
        assertNull(result.coordinatorId());
        assertTrue(result.disagreeingNodes().isEmpty());
    }

    @Test
    void testCrashedNodesIgnored() {
        List<ElectionParticipant> nodesList = List.of(
            new MockParticipant(1, false, 2),
            new MockParticipant(2, false, 2),
            new MockParticipant(3, true, 1) // Crashed, thinks it's 1
        );
        ConsensusResult result = ConsensusChecker.check(nodesList);
        assertTrue(result.reached());
        assertEquals(2, result.coordinatorId());
    }
}
