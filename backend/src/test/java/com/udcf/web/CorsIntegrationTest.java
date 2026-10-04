package com.udcf.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards CORS on {@code /api/**} against the real application context: the configured
 * origins are allowed and anything else is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsIntegrationTest {

    private static final String ENDPOINT = "/api/multithreading/stats";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a preflight from each configured origin is allowed")
    void preflightFromAllowedOrigin() throws Exception {
        for (String origin : new String[] {"http://localhost:5173", "http://127.0.0.1:5173"}) {
            mockMvc.perform(options(ENDPOINT)
                            .header(HttpHeaders.ORIGIN, origin)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
    }

    @Test
    @DisplayName("a preflight from a foreign origin is rejected with 403")
    void preflightFromForeignOrigin() throws Exception {
        mockMvc.perform(options(ENDPOINT)
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("an actual GET from an allowed origin carries Access-Control-Allow-Origin")
    void actualRequestFromAllowedOrigin() throws Exception {
        mockMvc.perform(get(ENDPOINT).header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }
}
