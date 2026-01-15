package com.moiz.ledgerr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Type-safe configuration properties for Ledgerr.
 * 
 * <p>Maps to the 'ledgerr' prefix in application.yml:</p>
 * <pre>
 * ledgerr:
 *   transaction:
 *     max-entries-per-transaction: 100
 *   outbox:
 *     enabled: true
 *     poll-interval-ms: 1000
 *   reconciliation:
 *     enabled: true
 *     schedule-cron: "0 0 2 * * ?"
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ledgerr")
@Validated
public class LedgerrProperties {

    private Transaction transaction = new Transaction();
    private Outbox outbox = new Outbox();
    private Reconciliation reconciliation = new Reconciliation();
    private Security security = new Security();

    /**
     * Transaction processing configuration.
     */
    @Data
    public static class Transaction {
        /**
         * Maximum number of ledger entries allowed per transaction.
         * Prevents excessively large atomic operations.
         */
        @Min(2)
        private int maxEntriesPerTransaction = 100;

        /**
         * Maximum retry attempts for optimistic locking conflicts.
         */
        @Min(1)
        private int maxRetryAttempts = 3;

        /**
         * Base backoff time in milliseconds between retries.
         */
        @Min(10)
        private long retryBackoffMs = 100;
    }

    /**
     * Transactional outbox configuration for reliable event publishing.
     */
    @Data
    public static class Outbox {
        /**
         * Whether the outbox publisher is enabled.
         */
        private boolean enabled = true;

        /**
         * Polling interval in milliseconds for unpublished events.
         */
        @Min(100)
        private long pollIntervalMs = 1000;

        /**
         * Number of events to process per batch.
         */
        @Min(1)
        private int batchSize = 100;

        /**
         * Maximum retries for failed event publishing.
         */
        @Min(1)
        private int maxRetries = 5;
    }

    /**
     * Reconciliation workflow configuration.
     */
    @Data
    public static class Reconciliation {
        /**
         * Whether reconciliation processing is enabled.
         */
        private boolean enabled = true;

        /**
         * Cron expression for scheduled reconciliation runs.
         */
        @NotBlank
        private String scheduleCron = "0 0 2 * * ?";

        /**
         * Time window in hours for matching external transactions.
         */
        @Min(1)
        private int matchWindowHours = 72;
    }

    /**
     * Security-related configuration.
     */
    @Data
    public static class Security {
        /**
         * Algorithm used for request payload hashing (idempotency checks).
         */
        @NotBlank
        private String requestHashAlgorithm = "SHA-256";
    }
}
