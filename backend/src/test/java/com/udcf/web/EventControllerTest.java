package com.udcf.web;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards GET /api/events: filters, limit, causal order and parameter validation.
 *
 * <p>Runs in context B, shared with other classes, so every test publishes into its own
 * uniquely named module and asserts only on those events.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClusterEventBus bus;

    private static String uniqueModule() {
        return "evt-" + UUID.randomUUID();
    }

    private static Integer[] asIntegers(List<ClusterEvent> events) {
        return events.stream().map(e -> (int) e.sequence()).toArray(Integer[]::new);
    }

    @Test
    @DisplayName("the module filter returns only that module's events")
    void moduleFilter() throws Exception {
        String mine = uniqueModule();
        String other = uniqueModule();
        ClusterEvent a = bus.publish(EventDraft.of(mine, 1, "A", 1));
        bus.publish(EventDraft.of(other, 1, "B", 2));
        ClusterEvent c = bus.publish(EventDraft.of(mine, 2, "C", 3));

        mockMvc.perform(get("/api/events").param("module", mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sequence", contains(asIntegers(List.of(a, c)))))
                .andExpect(jsonPath("$[*].module", everyItem(is(mine))));
    }

    @Test
    @DisplayName("the node filter returns only that node's events")
    void nodeFilter() throws Exception {
        String mine = uniqueModule();
        ClusterEvent onFour = bus.publish(EventDraft.of(mine, 4, "ON_FOUR", 1));
        bus.publish(EventDraft.of(mine, 3, "ON_THREE", 1));

        mockMvc.perform(get("/api/events").param("node", "4").param("limit", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nodeId", everyItem(is(4))))
                .andExpect(jsonPath("$[*].sequence", hasItems((int) onFour.sequence())));
        mockMvc.perform(get("/api/events").param("module", mine).param("node", "4"))
                .andExpect(jsonPath("$[*].type", contains("ON_FOUR")));
    }

    @Test
    @DisplayName("the limit keeps the most recent events by arrival")
    void limitTakesMostRecent() throws Exception {
        String mine = uniqueModule();
        List<ClusterEvent> published = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            published.add(bus.publish(EventDraft.of(mine, 1, "E" + i, i)));
        }

        mockMvc.perform(get("/api/events").param("module", mine).param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sequence", contains(asIntegers(published.subList(2, 5)))));
    }

    @Test
    @DisplayName("the default limit is 200")
    void defaultLimit() throws Exception {
        String mine = uniqueModule();
        for (int i = 0; i < 201; i++) {
            bus.publish(EventDraft.of(mine, 1, "E", i));
        }

        mockMvc.perform(get("/api/events").param("module", mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(200)));
    }

    @Test
    @DisplayName("results are in causal order: Lamport time, then node, then sequence")
    void causalOrder() throws Exception {
        String mine = uniqueModule();
        bus.publish(EventDraft.of(mine, 1, "L9", 9));
        bus.publish(EventDraft.of(mine, 2, "L3_N2", 3));
        bus.publish(EventDraft.of(mine, 1, "L5", 5));
        bus.publish(EventDraft.of(mine, 1, "L3_N1", 3));

        mockMvc.perform(get("/api/events").param("module", mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", contains("L3_N1", "L3_N2", "L5", "L9")));
    }

    @Test
    @DisplayName("limit 0 and limit above the buffer size return 400 naming the parameter")
    void badLimit() throws Exception {
        mockMvc.perform(get("/api/events").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request parameters"))
                .andExpect(jsonPath("$.errors.limit").value("must be greater than or equal to 1"));

        mockMvc.perform(get("/api/events").param("limit", "5001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request parameters"))
                .andExpect(jsonPath("$.errors.limit").value("must be less than or equal to 5000"));
    }

    @Test
    @DisplayName("node -1 returns 400 naming the parameter")
    void badNode() throws Exception {
        mockMvc.perform(get("/api/events").param("node", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request parameters"))
                .andExpect(jsonPath("$.errors.node").value("must be greater than or equal to 0"));
    }
}
