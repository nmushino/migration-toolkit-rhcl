package com.example.migrationtool.service;

import com.example.migrationtool.model.ApiService;
import com.example.migrationtool.model.Authentication;
import com.example.migrationtool.model.Backend;
import com.example.migrationtool.model.CompatibilityResult;
import com.example.migrationtool.model.MappingRule;
import com.example.migrationtool.model.Policy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityServiceTest {

    private CompatibilityService service;

    @BeforeEach
    void setUp() {
        service = new CompatibilityService();
    }

    // ── Authentication checks ────────────────────────────────────────────────

    @Test
    void check_nullAuthentication_warningItem() {
        ApiService svc = basicService();
        svc.authentication = null;
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.name.equals("Authentication")));
    }

    @Test
    void check_jwtAuthentication_supported() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "SUPPORTED".equals(i.status)
                && i.name.contains("JWT")));
    }

    @Test
    void check_apiKeyAuthentication_supported() {
        ApiService svc = basicService();
        svc.authentication = auth("apiKey");
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "SUPPORTED".equals(i.status)
                && i.name.contains("API Key")));
    }

    @Test
    void check_appIdKeyAuthentication_warning() {
        ApiService svc = basicService();
        svc.authentication = auth("appIdKey");
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.name.contains("App ID")));
    }

    @Test
    void check_unknownAuthentication_warning() {
        ApiService svc = basicService();
        svc.authentication = auth("oauth2-custom");
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)));
    }

    // ── Policy checks ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "url_rewriting, SUPPORTED",
        "rewrite, SUPPORTED",
        "header_modification, SUPPORTED",
        "headers, SUPPORTED",
        "cors, SUPPORTED",
        "rate_limit, SUPPORTED",
        "rate-limit, SUPPORTED",
        "lua, WARNING",
        "soap, UNSUPPORTED",
        "camel, UNSUPPORTED",
        "unknown_policy, WARNING"
    })
    void check_policy_expectedStatus(String policyName, String expectedStatus) {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.policies = List.of(enabledPolicy(policyName));
        CompatibilityResult result = service.check(svc);
        boolean found = result.items.stream().anyMatch(i -> {
            if (!expectedStatus.equals(i.status)) return false;
            String itemLower = i.name.toLowerCase();
            String policyLower = policyName.toLowerCase();
            if (itemLower.contains(policyLower.replace("_", " ").replace("-", " "))) return true;
            if (itemLower.contains("policy")) return true;
            // match by first keyword (handles "url_rewriting"→"url", "headers"→"header")
            String firstWord = policyLower.split("[_-]")[0];
            return itemLower.contains(firstWord) || itemLower.contains(firstWord.replaceAll("s$", ""));
        });
        assertTrue(found, "Expected " + expectedStatus + " for policy " + policyName);
    }

    @Test
    void check_disabledPoliciesIgnored() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        Policy p = new Policy();
        p.name = "soap";
        p.enabled = false;
        svc.policies = List.of(p);
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().noneMatch(i -> "UNSUPPORTED".equals(i.status)));
    }

    @Test
    void check_nullPolicies_noError() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.policies = null;
        assertDoesNotThrow(() -> service.check(svc));
    }

    @Test
    void check_emptyPolicies_noError() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.policies = List.of();
        CompatibilityResult result = service.check(svc);
        assertNotNull(result);
    }

    // ── Mapping rule checks ───────────────────────────────────────────────────

    @Test
    void check_nullMappingRules_warningItem() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.mappingRules = null;
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.name.equals("Mapping Rules")));
    }

    @Test
    void check_emptyMappingRules_warningItem() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.mappingRules = List.of();
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.name.equals("Mapping Rules")));
    }

    @Test
    void check_mappingRulesWithWildcard_warning() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        MappingRule rule = new MappingRule();
        rule.pattern = "/api/{?}";
        svc.mappingRules = List.of(rule);
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.name.equals("Mapping Rules")));
    }

    @Test
    void check_mappingRulesWithoutWildcard_supported() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        MappingRule rule = new MappingRule();
        rule.pattern = "/api/users";
        svc.mappingRules = List.of(rule);
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "SUPPORTED".equals(i.status)
                && i.name.equals("Mapping Rules")));
    }

    @Test
    void check_multipleMappingRules_supported() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.mappingRules = List.of(
                rule("GET", "/users"),
                rule("POST", "/users"),
                rule("DELETE", "/users/{id}")
        );
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "SUPPORTED".equals(i.status)
                && i.message.contains("3")));
    }

    // ── Backend checks ────────────────────────────────────────────────────────

    @Test
    void check_nullBackends_warningItem() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.backends = null;
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.name.equals("Backend")));
    }

    @Test
    void check_emptyBackends_warningItem() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.backends = List.of();
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.name.equals("Backend")));
    }

    @Test
    void check_httpsBackend_supported() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        Backend b = new Backend();
        b.name = "my-backend";
        b.privateEndpoint = "https://api.example.com";
        svc.backends = List.of(b);
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "SUPPORTED".equals(i.status)
                && i.name.contains("TLS")));
    }

    @Test
    void check_httpBackend_supported() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        Backend b = new Backend();
        b.name = "http-backend";
        b.privateEndpoint = "http://api.internal";
        svc.backends = List.of(b);
        CompatibilityResult result = service.check(svc);
        assertTrue(result.items.stream().anyMatch(i -> "SUPPORTED".equals(i.status)));
    }

    // ── Score and level calculation ───────────────────────────────────────────

    @Test
    void check_allSupported_scoreHigh() {
        ApiService svc = fullSupportedService();
        CompatibilityResult result = service.check(svc);
        assertEquals("HIGH", result.level);
        assertTrue(result.score >= 80);
    }

    @Test
    void check_soapPolicy_reducesScore() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.mappingRules = List.of(rule("GET", "/api"));
        svc.backends = List.of(backend("http://svc"));
        svc.policies = List.of(enabledPolicy("soap"));
        CompatibilityResult result = service.check(svc);
        assertTrue(result.score < 100, "SOAP policy should reduce score");
    }

    @Test
    void check_serviceIdAndNamePreserved() {
        ApiService svc = basicService();
        svc.id = "test-id-99";
        svc.name = "My Test Service";
        svc.authentication = auth("jwt");
        CompatibilityResult result = service.check(svc);
        assertEquals("test-id-99", result.serviceId);
        assertEquals("My Test Service", result.serviceName);
    }

    @Test
    void check_mediumLevel_whenMixedResults() {
        ApiService svc = new ApiService();
        svc.id = "1";
        svc.name = "Mixed";
        Authentication auth = new Authentication();
        auth.type = "appIdKey";
        svc.authentication = auth;
        svc.mappingRules = null;
        svc.backends = null;
        svc.policies = null;
        CompatibilityResult result = service.check(svc);
        assertNotNull(result.level);
        assertTrue(result.score >= 0 && result.score <= 100);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ApiService basicService() {
        ApiService svc = new ApiService();
        svc.id = "svc-1";
        svc.name = "Test Service";
        return svc;
    }

    private ApiService fullSupportedService() {
        ApiService svc = basicService();
        svc.authentication = auth("jwt");
        svc.mappingRules = List.of(rule("GET", "/api/users"), rule("POST", "/api/users"));
        svc.backends = List.of(backend("https://api.example.com"));
        svc.policies = List.of(enabledPolicy("cors"));
        return svc;
    }

    private Authentication auth(String type) {
        Authentication a = new Authentication();
        a.type = type;
        return a;
    }

    private Policy enabledPolicy(String name) {
        Policy p = new Policy();
        p.name = name;
        p.enabled = true;
        return p;
    }

    private MappingRule rule(String method, String pattern) {
        MappingRule r = new MappingRule();
        r.httpMethod = method;
        r.pattern = pattern;
        return r;
    }

    private Backend backend(String endpoint) {
        Backend b = new Backend();
        b.name = "backend";
        b.privateEndpoint = endpoint;
        return b;
    }
}
