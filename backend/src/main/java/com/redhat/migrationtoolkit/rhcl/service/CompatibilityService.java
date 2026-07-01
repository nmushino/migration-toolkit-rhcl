package com.redhat.migrationtoolkit.rhcl.service;

import com.redhat.migrationtoolkit.rhcl.model.ApiService;
import com.redhat.migrationtoolkit.rhcl.model.Backend;
import com.redhat.migrationtoolkit.rhcl.model.CompatibilityItem;
import com.redhat.migrationtoolkit.rhcl.model.CompatibilityResult;
import com.redhat.migrationtoolkit.rhcl.model.Policy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class CompatibilityService {

    private static final int SCORE_SUPPORTED = 20;
    private static final int SCORE_WARNING = 10;
    private static final int SCORE_UNSUPPORTED = 0;

    public CompatibilityResult check(ApiService service) {
        CompatibilityResult result = new CompatibilityResult();
        result.serviceId = service.id;
        result.serviceName = service.name;
        result.items = new ArrayList<>();

        checkAuthentication(service, result.items);
        checkPolicies(service, result.items);
        checkMappingRules(service, result.items);
        checkBackend(service, result.items);

        result.score = calculateScore(result.items);
        result.level = scoreToLevel(result.score);
        return result;
    }

    private void checkAuthentication(ApiService service, List<CompatibilityItem> items) {
        if (service.authentication == null) {
            items.add(new CompatibilityItem("Authentication", "WARNING", "No authentication configured"));
            return;
        }
        switch (service.authentication.type) {
            case "jwt" ->
                items.add(new CompatibilityItem(
                        "JWT Authentication", "SUPPORTED", "JWT/OIDC is fully supported"));
            case "apiKey" ->
                items.add(new CompatibilityItem(
                        "API Key Authentication", "SUPPORTED", "API key authentication is supported"));
            case "appIdKey" ->
                items.add(new CompatibilityItem(
                        "App ID/Key Authentication", "WARNING",
                        "App ID/Key requires custom policy configuration"));
            default ->
                items.add(new CompatibilityItem(
                        "Authentication", "WARNING",
                        "Authentication type may require manual review"));
        }
    }

    private void checkPolicies(ApiService service, List<CompatibilityItem> items) {
        if (service.policies == null || service.policies.isEmpty()) {
            return;
        }
        for (Policy policy : service.policies) {
            if (!policy.enabled) {
                continue;
            }
            switch (policy.name.toLowerCase()) {
                case "url_rewriting", "rewrite" ->
                    items.add(new CompatibilityItem(
                            "URL Rewrite", "SUPPORTED",
                            "URL rewriting is supported via HTTPRoute filters"));
                case "header_modification", "headers" ->
                    items.add(new CompatibilityItem(
                            "Header Modification", "SUPPORTED",
                            "Header manipulation is supported"));
                case "cors" ->
                    items.add(new CompatibilityItem(
                            "CORS", "SUPPORTED", "CORS policy is supported"));
                case "rate_limit", "rate-limit" ->
                    items.add(new CompatibilityItem(
                            "Rate Limiting", "SUPPORTED",
                            "Rate limiting supported via Kuadrant RateLimitPolicy"));
                case "lua" ->
                    items.add(new CompatibilityItem(
                            "Lua Policy", "WARNING",
                            "Lua scripts need manual conversion to WASM or custom policies"));
                case "soap" ->
                    items.add(new CompatibilityItem(
                            "SOAP", "UNSUPPORTED",
                            "SOAP policies are not supported in Connectivity Link"));
                case "camel" ->
                    items.add(new CompatibilityItem(
                            "Camel Integration", "UNSUPPORTED",
                            "Camel integrations require separate migration"));
                default ->
                    items.add(new CompatibilityItem(
                            "Policy: " + policy.name, "WARNING", "Policy requires manual review"));
            }
        }
    }

    private void checkMappingRules(ApiService service, List<CompatibilityItem> items) {
        if (service.mappingRules == null || service.mappingRules.isEmpty()) {
            items.add(new CompatibilityItem("Mapping Rules", "WARNING", "No mapping rules found"));
            return;
        }
        boolean hasWildcard = service.mappingRules.stream()
                .anyMatch(r -> r.pattern != null && r.pattern.contains("{?}"));
        if (hasWildcard) {
            items.add(new CompatibilityItem(
                    "Mapping Rules", "WARNING",
                    "Wildcard patterns may need adjustment for HTTPRoute"));
        } else {
            items.add(new CompatibilityItem(
                    "Mapping Rules", "SUPPORTED",
                    service.mappingRules.size() + " mapping rules will be converted to HTTPRoute rules"));
        }
    }

    private void checkBackend(ApiService service, List<CompatibilityItem> items) {
        if (service.backends == null || service.backends.isEmpty()) {
            items.add(new CompatibilityItem("Backend", "WARNING", "No backend configured"));
            return;
        }
        for (Backend backend : service.backends) {
            if (backend.privateEndpoint != null && backend.privateEndpoint.startsWith("https://")) {
                items.add(new CompatibilityItem(
                        "Backend TLS", "SUPPORTED", "HTTPS backend endpoint supported"));
            } else {
                items.add(new CompatibilityItem(
                        "Backend: " + backend.name, "SUPPORTED",
                        "Backend URL will be configured in HTTPRoute"));
            }
        }
    }

    private int calculateScore(List<CompatibilityItem> items) {
        if (items.isEmpty()) {
            return 100;
        }
        int total = 0;
        int max = items.size() * SCORE_SUPPORTED;
        for (CompatibilityItem item : items) {
            total += switch (item.status) {
                case "SUPPORTED" -> SCORE_SUPPORTED;
                case "WARNING" -> SCORE_WARNING;
                default -> SCORE_UNSUPPORTED;
            };
        }
        return (int) ((double) total / max * 100);
    }

    private String scoreToLevel(int score) {
        if (score >= 80) {
            return "HIGH";
        }
        if (score >= 50) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
