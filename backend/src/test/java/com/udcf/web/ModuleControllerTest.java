package com.udcf.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards GET /api/modules. Shares context D with ClusterControllerTest, so the registry
 * holds the one fake module from {@link TestModuleConfig}; the empty-registry case is
 * covered by CoreContextTest.
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
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(TestModuleConfig.FakeModule.ID))
                .andExpect(jsonPath("$[0].labNumber").value(TestModuleConfig.FakeModule.LAB_NUMBER))
                .andExpect(jsonPath("$[0].title").value("Test Module"))
                .andExpect(jsonPath("$[0].status").value("IDLE"));
    }
}
