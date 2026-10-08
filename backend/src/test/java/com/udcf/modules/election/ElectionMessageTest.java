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
        
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<1030; i++) sb.append("a");
        assertThrows(IllegalArgumentException.class, () -> ElectionMessage.fromWire(sb.toString()));
    }
}
