package com.urlcheck.web;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enables cross-origin access to the API when {@code app.cors.allowed-origins}
 * names one or more origins (see {@code CORS_ALLOWED_ORIGINS}).
 *
 * <p>When the property is empty - the default - no CORS headers are written at
 * all. That is the safe setting for the common deployment where a reverse proxy
 * serves the built frontend and this API from the same origin, and it avoids
 * advertising origins that were never configured.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** Origins are matched exactly; credentials require that, so no wildcards. */
    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
