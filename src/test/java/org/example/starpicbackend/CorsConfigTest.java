package org.example.starpicbackend;

import org.example.starpicbackend.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    @Test void credentialsOnlyApplyToExplicitOrigins() {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173, https://gallery.example.com");
        TestRegistry registry = new TestRegistry();
        config.addCorsMappings(registry);
        CorsConfiguration cors = registry.configurations().get("/**");
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
        assertEquals("http://localhost:5173", cors.checkOrigin("http://localhost:5173"));
        assertEquals("https://gallery.example.com", cors.checkOrigin("https://gallery.example.com"));
        assertNull(cors.checkOrigin("https://attacker.example.com"));
        assertNull(cors.checkOrigin("http://localhost:5174"));
    }

    @Test void wildcardAndNonOriginUrlsFailConfiguration() {
        for (String value : new String[]{"*", "https://*.example.com", "https://example.com/path", "null"}) {
            CorsConfig config = new CorsConfig();
            ReflectionTestUtils.setField(config, "allowedOrigins", value);
            assertThrows(IllegalArgumentException.class, () -> config.addCorsMappings(new TestRegistry()));
        }
    }

    private static class TestRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}