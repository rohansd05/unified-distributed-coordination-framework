package com.udcf.web;

import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.events.EventLogExporter;
import com.udcf.modules.mapreduce.LogFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards GET /api/events/export (link L5): content type, the line format, filters and
 * parameter validation.
 *
 * <p>Runs in the shared default context, so every test publishes into its own uniquely named
 * module and asserts only on those lines. Binds no sockets.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventExportEndpointTest {

    private static final String EXPORT = "/api/events/export";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClusterEventBus bus;

    private static String uniqueModule() {
        return "export-" + UUID.randomUUID();
    }

    private List<String> lines(String body) {
        return body.isEmpty() ? List.of() : Arrays.asList(body.split("\n"));
    }

    @Test
    @DisplayName("200 with text/plain; charset=UTF-8, one line per event in causal order, in the MapReduce log format")
    void exportsText() throws Exception {
        String module = uniqueModule();
        bus.publish(EventDraft.of(module, 2, "SECOND", 20).withMessage("naïve | text\nwith a line feed"));
        bus.publish(EventDraft.of(module, 1, "FIRST", 10).withPeer(2));

        String body = mockMvc.perform(get(EXPORT).param("module", module))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        List<String> lines = lines(body);
        assertThat(body).endsWith("\n");
        assertThat(lines).hasSize(2);
        assertThat(LogFields.field(lines.get(0), "category")).isEqualTo("FIRST");
        assertThat(LogFields.field(lines.get(0), "peer")).isEqualTo("2");
        assertThat(LogFields.field(lines.get(1), "peer")).isNull();
        assertThat(EventLogExporter.unescape(LogFields.field(lines.get(1), "msg")))
                .isEqualTo("naïve | text\nwith a line feed");
    }

    @Test
    @DisplayName("the node filter and the limit select like GET /api/events")
    void nodeAndLimit() throws Exception {
        String module = uniqueModule();
        for (int i = 1; i <= 4; i++) {
            bus.publish(EventDraft.of(module, i % 2 + 1, "E" + i, i));
        }

        String byNode = mockMvc.perform(get(EXPORT).param("module", module).param("node", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String limited = mockMvc.perform(get(EXPORT).param("module", module).param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(lines(byNode)).extracting(l -> LogFields.field(l, "category")).containsExactly("E2", "E4");
        assertThat(lines(limited)).extracting(l -> LogFields.field(l, "category")).containsExactly("E4");
    }

    @Test
    @DisplayName("a module with no events exports an empty body, still 200")
    void emptyExport() throws Exception {
        mockMvc.perform(get(EXPORT).param("module", uniqueModule()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("400 with a field message for limit 0, a limit above the buffer size, and a negative node")
    void badParameters() throws Exception {
        mockMvc.perform(get(EXPORT).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.limit").exists());
        mockMvc.perform(get(EXPORT).param("limit", "5001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.limit").value("must be less than or equal to 5000"));
        mockMvc.perform(get(EXPORT).param("node", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.node").exists());
        mockMvc.perform(get(EXPORT).param("limit", "many"))
                .andExpect(status().isBadRequest());
    }
}
