package com.udcf.web;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Browser-facing settings, bound from {@code udcf.web.*}.
 *
 * <p>{@code allowedOrigins} comes from the {@code UDCF_ALLOWED_ORIGINS} environment variable
 * (comma-separated) and is the only source of origins for both CORS on {@code /api/**} and
 * the WebSocket handshake on {@code /ws}. Entries are origin patterns, so
 * {@code https://*.vercel.app} is allowed. Registered by {@code @ConfigurationPropertiesScan}.</p>
 *
 * <p>Named {@code UdcfWebProperties} so it never clashes with Spring Boot's own
 * {@code WebProperties}.</p>
 *
 * @param allowedOrigins origins (or origin patterns) allowed to call the backend
 */
@Validated
@ConfigurationProperties("udcf.web")
public record UdcfWebProperties(@NotEmpty List<String> allowedOrigins) {

    public UdcfWebProperties {
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin == null || origin.isBlank()) {
                    throw new IllegalArgumentException(
                            "udcf.web.allowed-origins must not contain a blank entry: " + allowedOrigins);
                }
                if (origin.endsWith("/")) {
                    throw new IllegalArgumentException("udcf.web.allowed-origins entry '" + origin
                            + "' must not end with '/'; an origin is scheme://host[:port] with no path");
                }
            }
            allowedOrigins = List.copyOf(allowedOrigins);
        }
    }
}
