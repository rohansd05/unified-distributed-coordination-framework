package com.udcf.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.dto.BatchSubmissionResponse;
import com.udcf.dto.GenerateRequestsCommand;
import com.udcf.dto.RequestResult;
import com.udcf.dto.ThreadPoolStats;
import com.udcf.model.DistributedRequest;
import com.udcf.model.RequestStatus;
import com.udcf.model.WorkloadType;
import com.udcf.threadpool.RequestProcessingService;
import com.udcf.threadpool.RequestRegistry;
import com.udcf.threadpool.ThreadPoolStatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MultithreadingController.class)
class MultithreadingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RequestProcessingService processingService;

    @MockBean
    private ThreadPoolStatsService statsService;

    @MockBean
    private RequestRegistry registry;

    private RequestResult sampleResult() {
        return new RequestResult("abc123", 1, WorkloadType.MIXED, RequestStatus.COMPLETED,
                "udcf-worker-2", Instant.parse("2026-01-01T00:00:00Z"),
                1.5d, 12.25d, 13.75d, "hash=dead waited=4ms", null);
    }

    @Test
    @DisplayName("POST /requests returns 202 with accepted and rejected counts")
    void generateReturnsAccepted() throws Exception {
        given(processingService.submitBatch(any()))
                .willReturn(new BatchSubmissionResponse(10, 8, 2, List.of("a", "b")));

        mockMvc.perform(post("/api/multithreading/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                GenerateRequestsCommand.of(10, WorkloadType.CPU_HASH, 50))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested").value(10))
                .andExpect(jsonPath("$.accepted").value(8))
                .andExpect(jsonPath("$.rejected").value(2));
    }

    @Test
    @DisplayName("POST /requests rejects a count below one")
    void rejectsCountBelowOne() throws Exception {
        mockMvc.perform(post("/api/multithreading/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                GenerateRequestsCommand.of(0, WorkloadType.CPU_HASH, 50))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /requests rejects a count above the safety cap")
    void rejectsCountAboveCap() throws Exception {
        mockMvc.perform(post("/api/multithreading/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                GenerateRequestsCommand.of(5000, WorkloadType.CPU_HASH, 50))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /requests/sync returns the completed result")
    void syncReturnsResult() throws Exception {
        given(processingService.submitAndWait(any(WorkloadType.class), anyInt()))
                .willReturn(sampleResult());

        mockMvc.perform(post("/api/multithreading/requests/sync")
                        .param("type", "MIXED")
                        .param("payloadSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("abc123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.threadName").value("udcf-worker-2"));
    }

    @Test
    @DisplayName("GET /requests returns the recent request list")
    void listsRecentRequests() throws Exception {
        DistributedRequest request = new DistributedRequest("abc123", 1, WorkloadType.MIXED, 50);
        request.markStarted("udcf-worker-2");
        request.markCompleted("hash=dead");
        given(registry.recent(anyInt())).willReturn(List.of(request));

        mockMvc.perform(get("/api/multithreading/requests").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("abc123"))
                .andExpect(jsonPath("$[0].threadName").value("udcf-worker-2"));
    }

    @Test
    @DisplayName("GET /requests/{id} returns 404 for an unknown id")
    void unknownRequestReturns404() throws Exception {
        given(registry.find("nope")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/multithreading/requests/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /stats exposes live executor state")
    void statsExposesExecutorState() throws Exception {
        Map<RequestStatus, Long> counts = new EnumMap<>(RequestStatus.class);
        counts.put(RequestStatus.COMPLETED, 42L);
        given(statsService.snapshot()).willReturn(new ThreadPoolStats(
                1, 4, 8, 6, 3, 6, 12, 200, 188, 42L, 54L, 7.5d, 18.2d, 40.1d, 42, counts));

        mockMvc.perform(get("/api/multithreading/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeThreads").value(3))
                .andExpect(jsonPath("$.queuedRequests").value(12))
                .andExpect(jsonPath("$.completedTasks").value(42))
                .andExpect(jsonPath("$.requestsPerSecond").value(7.5))
                .andExpect(jsonPath("$.statusCounts.COMPLETED").value(42));
    }

    @Test
    @DisplayName("DELETE /requests clears history and returns 204")
    void resetReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/multithreading/requests"))
                .andExpect(status().isNoContent());

        verify(statsService).reset();
    }
}
