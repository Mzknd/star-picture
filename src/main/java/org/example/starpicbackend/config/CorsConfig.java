package org.example.starpicbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.util.Arrays;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:${CORS_ALLOWED_ORIGINS:http://localhost:5173}}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty())
                .peek(CorsConfig::validateOrigin).distinct().toArray(String[]::new);
        registry.addMapping("/**")
                .allowCredentials(true)
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    private static void validateOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || origin.contains("*") || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid origin");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("CORS_ALLOWED_ORIGINS must contain exact HTTP(S) origins without wildcards or paths", e);
        }
    }
}