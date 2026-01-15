package com.moiz.ledgerr.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Metrics configuration for Prometheus and Micrometer.
 * 
 * <p>This configuration sets up custom metrics, tags, and the TimedAspect
 * for @Timed annotation support on methods.</p>
 * 
 * <h2>Custom Metrics Exposed:</h2>
 * <ul>
 *   <li>ledgerr.transactions.created - Counter for created transactions</li>
 *   <li>ledgerr.transactions.posted - Counter for posted transactions</li>
 *   <li>ledgerr.transactions.rejected - Counter for rejected transactions</li>
 *   <li>ledgerr.outbox.events.published - Counter for published events</li>
 *   <li>ledgerr.reconciliation.mismatches - Counter for detected mismatches</li>
 * </ul>
 */
@Configuration
public class MetricsConfig {

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /**
     * Customizes the MeterRegistry with common tags for all metrics.
     * These tags will be added to every metric emitted by the application.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags(List.of(
                        Tag.of("application", applicationName),
                        Tag.of("environment", activeProfile)
                ));
    }

    /**
     * Enables the @Timed annotation on methods for automatic timing metrics.
     * 
     * <p>Usage example:</p>
     * <pre>
     * {@code
     * @Timed(value = "ledgerr.transaction.create", description = "Time to create a transaction")
     * public Transaction createTransaction(CreateTransactionRequest request) {
     *     // ...
     * }
     * }
     * </pre>
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
