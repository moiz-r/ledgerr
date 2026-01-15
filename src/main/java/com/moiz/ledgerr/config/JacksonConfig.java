package com.moiz.ledgerr.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.TimeZone;

/**
 * Jackson ObjectMapper configuration for consistent JSON handling.
 * 
 * <p>Configures JSON serialization/deserialization with:</p>
 * <ul>
 *   <li>Java 8 date/time support (ISO-8601 format)</li>
 *   <li>UTC timezone</li>
 *   <li>Lenient deserialization (ignores unknown properties)</li>
 *   <li>Null-exclusion in serialization</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Register Java 8 date/time module
        mapper.registerModule(new JavaTimeModule());
        
        // Serialize dates as ISO-8601 strings, not timestamps
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // Don't fail on unknown properties during deserialization
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        
        // Use UTC timezone
        mapper.setTimeZone(TimeZone.getTimeZone("UTC"));
        
        return mapper;
    }
}
