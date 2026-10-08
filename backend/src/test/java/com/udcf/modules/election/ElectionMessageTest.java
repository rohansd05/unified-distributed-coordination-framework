package com.udcf.modules.election;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ElectionMessageTest {

    @Test
    void testWireFormat() {
        ElectionMessage msg = new ElectionMessage(ElectionMessageType.ELECTION, 5, 12, "payload|with|pipes");
        String wire = msg.toWire();
        assertEquals("ELECTION|5|12|payload|with|pipes", wire);
        
        ElectionMessage parsed = ElectionMessage.fromWire(wire);
        assertEquals(ElectionMessageType.ELECTION, parsed.type());
        assertEquals(5, parsed.senderId());
        assertEquals(12, parsed.lamportTime());
        assertEquals("payload|with|pipes", parsed.payload());
    }

    @Test
    void testMalformed() {
        assertThrows(IllegalArgumentException.class, () -> ElectionMessage.fromWire(null));
        assertThrows(IllegalArgumentException.class, () -> ElectionMessage.fromWire(""));
        assertThrows(IllegalArgumentException.class, () -> ElectionMessage.fromWire("ELECTION|5"));
        assertThrows(IllegalArgumentException.class, () -> ElectionMessage.fromWire("UNKNOWN|5|12"));
    }

    @Test
    void testMessageOver1024CharactersRejected() {
        StringBuilder sb = new StringBuilder("ELECTION|1|1|");
        while (sb.length() <= 1024) {
            sb.append("x");
        }
        assertTrue(sb.length() > 1024);
        assertThrows(IllegalArgumentException.class, () -> ElectionMessage.fromWire(sb.toString()));
    }

    @Test
    void testHeartbeatRoundTrip() {
        ElectionMessage heartbeat = new ElectionMessage(ElectionMessageType.HEARTBEAT, 3, 0, "");
        String wire = heartbeat.toWire();
        assertEquals("HEARTBEAT|3|0|", wire);
        assertEquals(heartbeat, ElectionMessage.fromWire(wire));
    }
}
