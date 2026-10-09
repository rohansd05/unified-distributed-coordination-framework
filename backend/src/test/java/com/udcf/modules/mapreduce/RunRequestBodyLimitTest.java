package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.RunCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The POST /runs body limit at unit level: a declared Content-Length over the limit is refused
 * before any byte is read, a body without one is cut off one byte past the limit, and a body
 * at exactly the limit passes. (The HTTP behaviour is covered by MapReduceControllerTest.)
 */
class RunRequestBodyLimitTest {

    private static final MapReduceModuleProperties PROPS = new MapReduceModuleProperties(3, 1, 1, 6);
    private static final long LIMIT = 4 + 6;   // Base64 of 3 bytes + allowance

    private static HttpInputMessage message(byte[] body, long declaredLength, boolean[] read) {
        HttpHeaders headers = new HttpHeaders();
        if (declaredLength >= 0) {
            headers.setContentLength(declaredLength);
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                read[0] = true;
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private static HttpInputMessage advise(HttpInputMessage message) throws Exception {
        return new RunRequestBodyLimit(PROPS).beforeBodyRead(message, null, RunCommand.class,
                MappingJackson2HttpMessageConverter.class);
    }

    @Test
    @DisplayName("applies only to a RunCommand body")
    void supports() {
        RunRequestBodyLimit limit = new RunRequestBodyLimit(PROPS);
        assertThat(limit.limitBytes()).isEqualTo(LIMIT);
        assertThat(limit.supports(null, RunCommand.class, MappingJackson2HttpMessageConverter.class)).isTrue();
        assertThat(limit.supports(null, String.class, MappingJackson2HttpMessageConverter.class)).isFalse();
    }

    @Test
    @DisplayName("a declared Content-Length over the limit is refused before the body is opened")
    void declaredTooLarge() {
        boolean[] read = {false};
        assertThatThrownBy(() -> advise(message(new byte[0], LIMIT + 1, read)))
                .isInstanceOfSatisfying(RequestBodyTooLargeException.class,
                        e -> assertThat(e.limitBytes()).isEqualTo(LIMIT));
        assertThat(read[0]).isFalse();
    }

    @Test
    @DisplayName("a body without Content-Length is cut off one byte past the limit; exactly the limit passes")
    void chunked() throws Exception {
        boolean[] read = {false};
        InputStream atLimit = advise(message(new byte[(int) LIMIT], -1, read)).getBody();
        assertThat(atLimit.readAllBytes()).hasSize((int) LIMIT);

        InputStream over = advise(message(new byte[(int) LIMIT + 1], -1, read)).getBody();
        assertThatThrownBy(over::readAllBytes).isInstanceOf(RequestBodyTooLargeException.class);

        InputStream overByteByByte = advise(message(new byte[(int) LIMIT + 1], -1, read)).getBody();
        for (int i = 0; i < LIMIT; i++) {
            assertThat(overByteByByte.read()).isZero();
        }
        assertThatThrownBy(overByteByByte::read).isInstanceOf(RequestBodyTooLargeException.class);
    }
}
