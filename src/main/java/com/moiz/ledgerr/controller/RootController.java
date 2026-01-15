package com.moiz.ledgerr.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Root API controller providing basic service information.
 */
@RestController
@RequestMapping("/api")
public class RootController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
                "service", "ledgerr",
                "version", "1.0.0",
                "description", "Production-ready double-entry ledger service",
                "timestamp", Instant.now().toString(),
                "endpoints", Map.of(
                        "transactions", "/api/v1/transactions",
                        "accounts", "/api/v1/accounts",
                        "reconciliation", "/api/v1/reconciliation",
                        "health", "/actuator/health",
                        "metrics", "/actuator/prometheus"
                )
        ));
    }

    @GetMapping("/v1")
    public ResponseEntity<Map<String, Object>> v1() {
        return ResponseEntity.ok(Map.of(
                "version", "v1",
                "status", "active",
                "timestamp", Instant.now().toString()
        ));
    }
}
