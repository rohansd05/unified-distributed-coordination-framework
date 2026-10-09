package com.udcf.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards GET /api/modules. Shares context D with ClusterControllerTest, so the registry
 * holds the real modules plus the fake one from {@link TestModuleConfig} (lab 10). The
 * assertions find modules by id rather than by position or count, so other tracks' modules
 * can join the registry without breaking this test (E2c).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestModuleConfig.class)
class ModuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/modules lists each module with id, lab number, title and status")
    void listsModules() throws Exception {
        mockMvc.perform(get("/api/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'multithreading')].labNumber").value(2))
                .andExpect(jsonPath("$[?(@.id == 'multithreading')].title").value("Multithreading"))
                .andExpect(jsonPath("$[?(@.id == 'multithreading')].status").value("IDLE"))
                .andExpect(jsonPath("$[?(@.id == 'clocksync')].labNumber").value(3))
                .andExpect(jsonPath("$[?(@.id == 'clocksync')].title").value("Clock Synchronization"))
                .andExpect(jsonPath("$[?(@.id == 'clocksync')].status").value("IDLE"))
                .andExpect(jsonPath("$[?(@.id == 'loadbalancing')].labNumber").value(6))
                .andExpect(jsonPath("$[?(@.id == 'loadbalancing')].title").value("Load Balancing"))
                .andExpect(jsonPath("$[?(@.id == 'loadbalancing')].status").value("IDLE"))
                .andExpect(jsonPath("$[?(@.id == 'mapreduce')].labNumber").value(7))
                .andExpect(jsonPath("$[?(@.id == 'mapreduce')].title").value("MapReduce"))
                .andExpect(jsonPath("$[?(@.id == 'mapreduce')].status").value("IDLE"))
                .andExpect(jsonPath("$[?(@.id == '" + TestModuleConfig.FakeModule.ID + "')].labNumber")
                        .value(TestModuleConfig.FakeModule.LAB_NUMBER))
                .andExpect(jsonPath("$[?(@.id == '" + TestModuleConfig.FakeModule.ID + "')].title").value("Test Module"))
                .andExpect(jsonPath("$[?(@.id == '" + TestModuleConfig.FakeModule.ID + "')].status").value("IDLE"))
                .andExpect(jsonPath("$[-1:].id").value(TestModuleConfig.FakeModule.ID));
    }
}
