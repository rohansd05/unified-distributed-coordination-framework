package com.udcf.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The one CORS configuration: lets the frontend call {@code /api/**} from the origins in
 * {@link UdcfWebProperties}, and from nowhere else.
 *
 * <p>Replaces the Exp 2 {@code com.udcf.config.CorsConfig}, which hard-coded the Vite dev
 * server's origins.</p>
 *
 * <p>{@code @EnableConfigurationProperties} registers {@link UdcfWebProperties} explicitly
 * because MVC test slices ({@code @WebMvcTest}) load this configurer but filter out
 * {@code @ConfigurationPropertiesScan}. In the full application the scan and this
 * annotation register the same bean name, so there is still exactly one bean.</p>
 *
 * <p>No dedicated test file: declarative framework configuration. CorsIntegrationTest
 * checks allowed and rejected origins against the real application context.</p>
 */
@Configuration
@EnableConfigurationProperties(UdcfWebProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final UdcfWebProperties properties;

    public CorsConfig(UdcfWebProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
