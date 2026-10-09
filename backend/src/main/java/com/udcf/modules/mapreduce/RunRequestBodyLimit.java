package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.RunCommand;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Bounds the body of {@code POST /api/modules/mapreduce/runs}, the only request body in
 * Experiment 7, before it is read into memory. A JSON body has no size limit of its own in
 * Spring or Tomcat, so without this a huge body would be parsed before the upload cap is
 * checked.
 *
 * <p>The limit is {@link MapReduceModuleProperties#requestBodyMaxBytes()}: the Base64 size of
 * the upload cap plus a configured allowance for the other fields. A declared
 * {@code Content-Length} above it is refused before anything is read; a body without one
 * (chunked) is cut off as soon as one byte more than the limit has been read. Both throw
 * {@link RequestBodyTooLargeException}, which {@link MapReduceController} answers with 413,
 * and the controller method never runs.</p>
 *
 * <p>Applied only to {@link MapReduceController} and only to a {@link RunCommand} body. Being a
 * body advice (not a servlet filter) it runs inside Spring MVC, after CORS handling, so the
 * 413 carries the same CORS headers and ProblemDetail shape as every other error.</p>
 */
@ControllerAdvice(assignableTypes = MapReduceController.class)
public class RunRequestBodyLimit extends RequestBodyAdviceAdapter {

    private final long limitBytes;

    public RunRequestBodyLimit(MapReduceModuleProperties properties) {
        this.limitBytes = properties.requestBodyMaxBytes();
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == RunCommand.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType, Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        if (inputMessage.getHeaders().getContentLength() > limitBytes) {
            throw new RequestBodyTooLargeException(limitBytes);
        }
        InputStream limited = new LimitedInputStream(inputMessage.getBody(), limitBytes);
        HttpHeaders headers = inputMessage.getHeaders();
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return limited;
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    /** Most bytes a {@code POST /runs} body may have. */
    long limitBytes() {
        return limitBytes;
    }

    /** Passes bytes through and throws once more than {@code limit} bytes have been read. */
    static final class LimitedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        private void count(long n) {
            count += n;
            if (count > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }
    }
}
