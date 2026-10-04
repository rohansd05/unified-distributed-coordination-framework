package com.udcf.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Guards GET /api/system/health and /info against the real context (context B). */
@SpringBootTest
@AutoConfigureMockMvc
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/system/health returns 200 with status UP")
    void health() throws Exception {
        mockMvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /api/system/info reports the pom version, mode local, 5 nodes and an ISO start time")
    void info() throws Exception {
        String body = mockMvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("UDCF"))
                .andExpect(jsonPath("$.version").value(PomVersion.read()))
                .andExpect(jsonPath("$.mode").value("local"))
                .andExpect(jsonPath("$.clusterSize").value(5))
                .andExpect(jsonPath("$.uptimeSeconds").value(greaterThanOrEqualTo(0)))
                .andReturn().getResponse().getContentAsString();

        String startedAt = com.jayway.jsonpath.JsonPath.read(body, "$.startedAt");
        assertThat(Instant.parse(startedAt)).isBeforeOrEqualTo(Instant.now());
    }
}
