package com.udcf.modules.multithreading;

import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.module.ModuleBusyException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the E2c widening of GlobalExceptionHandler from com.udcf.web to all of com.udcf: a
 * controller outside com.udcf.web (here, a test controller in a module package) gets the same
 * ProblemDetail bodies, and an invalid {@code @Valid} body gets the {@code errors} map.
 *
 * <p>The controller is nested in this test class, so component scanning skips it; it exists
 * only in this class's own context, through {@code @Import}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExceptionAdviceScopeTest.ThrowingController.class)
class ExceptionAdviceScopeTest {

    @Autowired
    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/test/advice-scope")
    static class ThrowingController {

        record Body(@Min(value = 1, message = "count must be at least 1") int count,
                    @NotBlank(message = "name is required") String name) {
        }

        @GetMapping("/unknown-node")
        String unknownNode() {
            throw new UnknownNodeException(42);
        }

        @GetMapping("/node-down")
        String nodeDown() {
            throw new NodeDownException(3);
        }

        @GetMapping("/busy")
        String busy() {
            throw new ModuleBusyException("multithreading", "Backpressure demo on node 1");
        }

        @PostMapping("/body")
        String body(@Valid @RequestBody Body body) {
            return "ok";
        }
    }

    @Test
    @DisplayName("an unknown node thrown outside com.udcf.web is a 404 ProblemDetail with nodeId")
    void unknownNode() throws Exception {
        mockMvc.perform(get("/test/advice-scope/unknown-node"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown node"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.nodeId").value(42))
                .andExpect(jsonPath("$.instance").value("/test/advice-scope/unknown-node"));
    }

    @Test
    @DisplayName("a node-down exception thrown outside com.udcf.web is a 409 ProblemDetail")
    void nodeDown() throws Exception {
        mockMvc.perform(get("/test/advice-scope/node-down"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Node down"))
                .andExpect(jsonPath("$.nodeId").value(3));
    }

    @Test
    @DisplayName("a busy module thrown outside com.udcf.web is a 409 ProblemDetail with the action in progress")
    void moduleBusy() throws Exception {
        mockMvc.perform(get("/test/advice-scope/busy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Module busy"))
                .andExpect(jsonPath("$.moduleId").value("multithreading"))
                .andExpect(jsonPath("$.actionInProgress").value("Backpressure demo on node 1"));
    }

    @Test
    @DisplayName("an invalid @Valid body is a 400 with errors {field: message}, like a bad parameter")
    void invalidBody() throws Exception {
        mockMvc.perform(post("/test/advice-scope/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":0,\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request parameters"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors", aMapWithSize(2)))
                .andExpect(jsonPath("$.errors.count").value("count must be at least 1"))
                .andExpect(jsonPath("$.errors.name").value("name is required"));
    }
}
