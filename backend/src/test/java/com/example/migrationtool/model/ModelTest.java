package com.example.migrationtool.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    // ── ApiService ──────────────────────────────────────────────────────────

    @Test
    void apiService_defaultFieldsAreNull() {
        ApiService svc = new ApiService();
        assertNull(svc.id);
        assertNull(svc.name);
        assertNull(svc.authentication);
        assertNull(svc.backends);
    }

    @Test
    void apiService_fieldAssignment() {
        ApiService svc = new ApiService();
        svc.id = "42";
        svc.name = "Test API";
        svc.systemName = "test-api";
        svc.backendVersion = "1";
        svc.deploymentOption = "hosted";
        svc.state = "incomplete";

        assertEquals("42", svc.id);
        assertEquals("Test API", svc.name);
        assertEquals("test-api", svc.systemName);
        assertEquals("1", svc.backendVersion);
        assertEquals("hosted", svc.deploymentOption);
        assertEquals("incomplete", svc.state);
    }

    @Test
    void apiService_withBackendsAndMappingRules() {
        ApiService svc = new ApiService();
        Backend b = new Backend();
        b.id = "1";
        b.privateEndpoint = "http://backend:8080";
        svc.backends = List.of(b);

        MappingRule rule = new MappingRule();
        rule.httpMethod = "GET";
        rule.pattern = "/api/{?}";
        svc.mappingRules = List.of(rule);

        assertEquals(1, svc.backends.size());
        assertEquals("http://backend:8080", svc.backends.get(0).privateEndpoint);
        assertEquals(1, svc.mappingRules.size());
        assertEquals("/api/{?}", svc.mappingRules.get(0).pattern);
    }

    // ── Authentication ──────────────────────────────────────────────────────

    @Test
    void authentication_fieldAssignment() {
        Authentication auth = new Authentication();
        auth.type = "jwt";
        auth.location = "header";
        auth.paramName = "Authorization";
        auth.oidcIssuerEndpoint = "https://sso.example.com/realms/test";
        auth.credentials = "Bearer";

        assertEquals("jwt", auth.type);
        assertEquals("header", auth.location);
        assertEquals("Authorization", auth.paramName);
        assertEquals("https://sso.example.com/realms/test", auth.oidcIssuerEndpoint);
        assertEquals("Bearer", auth.credentials);
    }

    @Test
    void authentication_apiKeyType() {
        Authentication auth = new Authentication();
        auth.type = "apiKey";
        auth.location = "query";
        auth.paramName = "user_key";
        assertEquals("apiKey", auth.type);
        assertEquals("query", auth.location);
    }

    // ── Backend ─────────────────────────────────────────────────────────────

    @Test
    void backend_fieldAssignment() {
        Backend b = new Backend();
        b.id = "10";
        b.name = "My Backend";
        b.systemName = "my-backend";
        b.privateEndpoint = "https://api.internal.example.com";
        b.description = "Main backend";

        assertEquals("10", b.id);
        assertEquals("My Backend", b.name);
        assertEquals("my-backend", b.systemName);
        assertEquals("https://api.internal.example.com", b.privateEndpoint);
        assertEquals("Main backend", b.description);
    }

    @Test
    void backend_withMappingRules() {
        Backend b = new Backend();
        MappingRule rule = new MappingRule();
        rule.httpMethod = "POST";
        rule.pattern = "/users";
        b.mappingRules = List.of(rule);
        assertEquals(1, b.mappingRules.size());
    }

    // ── CompatibilityItem ───────────────────────────────────────────────────

    @Test
    void compatibilityItem_defaultConstructor() {
        CompatibilityItem item = new CompatibilityItem();
        assertNull(item.name);
        assertNull(item.status);
        assertNull(item.message);
    }

    @Test
    void compatibilityItem_paramConstructor() {
        CompatibilityItem item = new CompatibilityItem("Auth", "SUPPORTED", "OK");
        assertEquals("Auth", item.name);
        assertEquals("SUPPORTED", item.status);
        assertEquals("OK", item.message);
    }

    @Test
    void compatibilityItem_statuses() {
        CompatibilityItem sup = new CompatibilityItem("A", "SUPPORTED", "");
        CompatibilityItem warn = new CompatibilityItem("B", "WARNING", "");
        CompatibilityItem unsup = new CompatibilityItem("C", "UNSUPPORTED", "");
        assertEquals("SUPPORTED", sup.status);
        assertEquals("WARNING", warn.status);
        assertEquals("UNSUPPORTED", unsup.status);
    }

    // ── CompatibilityResult ─────────────────────────────────────────────────

    @Test
    void compatibilityResult_fieldAssignment() {
        CompatibilityResult result = new CompatibilityResult();
        result.serviceId = "svc-1";
        result.serviceName = "Service One";
        result.score = 80;
        result.level = "HIGH";
        result.items = List.of(new CompatibilityItem("A", "SUPPORTED", "OK"));

        assertEquals("svc-1", result.serviceId);
        assertEquals("Service One", result.serviceName);
        assertEquals(80, result.score);
        assertEquals("HIGH", result.level);
        assertEquals(1, result.items.size());
    }

    // ── MappingRule ─────────────────────────────────────────────────────────

    @Test
    void mappingRule_fieldAssignment() {
        MappingRule rule = new MappingRule();
        rule.id = "1";
        rule.httpMethod = "GET";
        rule.pattern = "/api/v1/users/{?}";
        rule.delta = 1;
        rule.metricSystemName = "hits";
        rule.last = true;

        assertEquals("1", rule.id);
        assertEquals("GET", rule.httpMethod);
        assertEquals("/api/v1/users/{?}", rule.pattern);
        assertEquals(1, rule.delta);
        assertEquals("hits", rule.metricSystemName);
        assertTrue(rule.last);
    }

    @Test
    void mappingRule_wildcardPattern() {
        MappingRule rule = new MappingRule();
        rule.pattern = "/api/{?}";
        assertTrue(rule.pattern.contains("{?}"));
    }

    // ── Metric ──────────────────────────────────────────────────────────────

    @Test
    void metric_fieldAssignment() {
        Metric m = new Metric();
        m.id = "1";
        m.name = "Hits";
        m.systemName = "hits";
        m.unit = "hit";
        m.description = "Total API hits";

        assertEquals("1", m.id);
        assertEquals("Hits", m.name);
        assertEquals("hits", m.systemName);
        assertEquals("hit", m.unit);
        assertEquals("Total API hits", m.description);
    }

    // ── Policy ──────────────────────────────────────────────────────────────

    @Test
    void policy_fieldAssignment() {
        Policy p = new Policy();
        p.name = "cors";
        p.version = "1.0.0";
        p.enabled = true;
        p.configuration = Map.of("allow_origin", "*");

        assertEquals("cors", p.name);
        assertEquals("1.0.0", p.version);
        assertTrue(p.enabled);
        assertEquals("*", p.configuration.get("allow_origin"));
    }

    @Test
    void policy_disabledPolicy() {
        Policy p = new Policy();
        p.name = "lua";
        p.enabled = false;
        assertEquals("lua", p.name);
        assertFalse(p.enabled);
    }

    // ── Project ─────────────────────────────────────────────────────────────

    @Test
    void project_fieldAssignment() {
        Project proj = new Project();
        proj.id = 1L;
        proj.name = "My Project";
        proj.threescaleUrl = "https://3scale.example.com";
        proj.tenant = "acme";
        proj.services = List.of(new ApiService());

        assertEquals(1L, proj.id);
        assertEquals("My Project", proj.name);
        assertEquals("https://3scale.example.com", proj.threescaleUrl);
        assertEquals("acme", proj.tenant);
        assertEquals(1, proj.services.size());
    }

    // ── Route ───────────────────────────────────────────────────────────────

    @Test
    void route_fieldAssignment() {
        Route r = new Route();
        r.id = "route-1";
        r.name = "Users Route";
        r.path = "/api/users";
        r.method = "GET";
        r.backendId = "backend-1";
        r.backendPath = "/users";

        assertEquals("route-1", r.id);
        assertEquals("Users Route", r.name);
        assertEquals("/api/users", r.path);
        assertEquals("GET", r.method);
        assertEquals("backend-1", r.backendId);
        assertEquals("/users", r.backendPath);
    }

    @Test
    void route_withPolicies() {
        Route r = new Route();
        Policy p = new Policy();
        p.name = "rate_limit";
        p.enabled = true;
        r.policies = List.of(p);
        assertEquals(1, r.policies.size());
        assertEquals("rate_limit", r.policies.get(0).name);
    }
}
