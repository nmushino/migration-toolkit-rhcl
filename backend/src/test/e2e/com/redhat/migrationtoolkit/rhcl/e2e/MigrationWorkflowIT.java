package com.redhat.migrationtoolkit.rhcl.e2e;

import com.redhat.migrationtoolkit.rhcl.dto.ValidationResult;
import com.redhat.migrationtoolkit.rhcl.service.CompatibilityService;
import com.redhat.migrationtoolkit.rhcl.service.ConversionService;
import com.redhat.migrationtoolkit.rhcl.service.PackageService;
import com.redhat.migrationtoolkit.rhcl.service.ValidationService;
import com.redhat.migrationtoolkit.rhcl.model.ApiService;
import com.redhat.migrationtoolkit.rhcl.model.Authentication;
import com.redhat.migrationtoolkit.rhcl.model.Backend;
import com.redhat.migrationtoolkit.rhcl.model.CompatibilityResult;
import com.redhat.migrationtoolkit.rhcl.model.MappingRule;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end workflow tests: simulates a full 3scale → Connectivity Link migration
 * without a real 3scale instance by directly invoking the service layer.
 */
@QuarkusTest
class MigrationWorkflowIT {

    @Inject
    CompatibilityService compatibilityService;

    @Inject
    ConversionService conversionService;

    @Inject
    ValidationService validationService;

    @Inject
    PackageService packageService;

    // ── Full workflow: service object → YAML → validate → package ─────────────

    @Test
    void fullWorkflow_jwtService_producesValidPackage() throws Exception {
        // Step 1: Build an API service (as if exported from 3scale)
        ApiService svc = buildCompleteService("jwt");

        // Step 2: Check compatibility
        CompatibilityResult compat = compatibilityService.check(svc);
        assertNotNull(compat);
        assertEquals("HIGH", compat.level, "JWT service should be HIGH compatibility");
        assertTrue(compat.score >= 80);

        // Step 3: Convert to YAML
        Map<String, String> yamlFiles = conversionService.convert(svc, "e2e-test-ns");
        assertFalse(yamlFiles.isEmpty());
        assertTrue(yamlFiles.containsKey("gateway.yaml"));
        assertTrue(yamlFiles.containsKey("httproute.yaml"));
        assertTrue(yamlFiles.containsKey("policy.yaml"));

        // Step 4: Validate YAML
        ValidationResult validation = validationService.validate(yamlFiles);
        assertNotNull(validation);
        assertFalse(validation.items.isEmpty());
        assertTrue(validation.valid, "Generated YAML should be syntactically valid");

        // Step 5: Create ZIP package
        byte[] zipBytes = packageService.createZip("e2e-test-pkg", yamlFiles);
        assertNotNull(zipBytes);
        assertTrue(zipBytes.length > 0);

        // Verify ZIP contents
        int fileCount = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            while (zis.getNextEntry() != null) {
                fileCount++;
                zis.closeEntry();
            }
        }
        assertEquals(yamlFiles.size(), fileCount);
    }

    @Test
    void fullWorkflow_apiKeyService_includesApiKeyResources() {
        ApiService svc = buildCompleteService("apiKey");

        CompatibilityResult compat = compatibilityService.check(svc);
        assertTrue(compat.score > 0);

        Map<String, String> yamlFiles = conversionService.convert(svc, "apikey-test-ns");
        assertTrue(yamlFiles.containsKey("apikey.yaml"),
                "API Key service should include apikey.yaml");
        assertTrue(yamlFiles.containsKey("secret.yaml"));

        // Secret should have random API key, not REPLACE_ME
        String secret = yamlFiles.get("secret.yaml");
        assertFalse(secret.contains("REPLACE_ME"), "API key secret should have generated value");

        ValidationResult validation = validationService.validate(yamlFiles);
        assertTrue(validation.valid);
    }

    @Test
    void fullWorkflow_externalBackend_includesServiceEntry() {
        ApiService svc = buildCompleteService("jwt");
        String externalUrl = "https://api.external-backend.example.com";

        Map<String, String> yamlFiles = conversionService.convert(svc, "external-ns", externalUrl);

        assertTrue(yamlFiles.containsKey("serviceentry.yaml"),
                "External backend should produce serviceentry.yaml");
        assertTrue(yamlFiles.containsKey("destinationrule.yaml"),
                "External backend should produce destinationrule.yaml");

        String serviceEntry = yamlFiles.get("serviceentry.yaml");
        assertTrue(serviceEntry.contains("api.external-backend.example.com"));

        ValidationResult validation = validationService.validate(yamlFiles);
        assertNotNull(validation);
    }

    @Test
    void fullWorkflow_internalBackend_noServiceEntry() {
        ApiService svc = buildCompleteService("jwt");
        String internalUrl = "http://my-service.my-namespace.svc.cluster.local:8080";

        Map<String, String> yamlFiles = conversionService.convert(svc, "internal-ns", internalUrl);

        assertFalse(yamlFiles.containsKey("serviceentry.yaml"),
                "Internal backend should NOT produce serviceentry.yaml");
        assertFalse(yamlFiles.containsKey("destinationrule.yaml"),
                "Internal backend should NOT produce destinationrule.yaml");
    }

    @Test
    void fullWorkflow_multipleServices_eachConvertsIndependently() {
        ApiService jwtSvc = buildCompleteService("jwt");
        jwtSvc.id = "svc-jwt";
        jwtSvc.name = "JWT Service";
        jwtSvc.systemName = "jwt-service";

        ApiService apiKeySvc = buildCompleteService("apiKey");
        apiKeySvc.id = "svc-apikey";
        apiKeySvc.name = "APIKey Service";
        apiKeySvc.systemName = "apikey-service";

        Map<String, String> jwtFiles = conversionService.convert(jwtSvc, "test-ns");
        Map<String, String> apiKeyFiles = conversionService.convert(apiKeySvc, "test-ns");

        assertFalse(jwtFiles.containsKey("apikey.yaml"));
        assertTrue(apiKeyFiles.containsKey("apikey.yaml"));

        CompatibilityResult jwtCompat = compatibilityService.check(jwtSvc);
        CompatibilityResult apiKeyCompat = compatibilityService.check(apiKeySvc);
        assertEquals("HIGH", jwtCompat.level);
        assertNotNull(apiKeyCompat.level);
    }

    // ── REST API E2E tests ────────────────────────────────────────────────────

    @Test
    void healthCheck_returns200() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void healthLive_returns200() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200);
    }

    @Test
    void healthReady_returns200() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200);
    }

    @Test
    void openApiSpec_available() {
        given()
                .when().get("/q/openapi")
                .then()
                .statusCode(200)
                .body(containsString("openapi"));
    }

    @Test
    void validateEndpoint_fullRoundTrip() {
        // Generate YAML via service layer
        ApiService svc = buildCompleteService("jwt");
        Map<String, String> yamlFiles = conversionService.convert(svc, "roundtrip-ns");

        // Send to REST validation endpoint
        given()
                .contentType("application/json")
                .body(yamlFiles)
                .when().post("/api/validate")
                .then()
                .statusCode(200)
                .body("valid", is(true));
    }

    @Test
    void historyEndpoint_emptyInitially() {
        given()
                .when().get("/api/history")
                .then()
                .statusCode(200)
                .body("$", instanceOf(List.class));
    }

    @Test
    void connectionTest_invalidUrl_returns400() {
        given()
                .contentType("application/json")
                .body("{\"url\":\"\",\"accessToken\":\"token\"}")
                .when().post("/api/connection/test")
                .then()
                .statusCode(400);
    }

    @Test
    void downloadZip_validFiles_returnsZip() {
        ApiService svc = buildCompleteService("jwt");
        Map<String, String> yamlFiles = conversionService.convert(svc, "download-ns");

        given()
                .contentType("application/json")
                .body(Map.of("yamlFiles", yamlFiles, "packageName", "e2e-download-test"))
                .when().post("/api/download/zip")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString("e2e-download-test.zip"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ApiService buildCompleteService(String authType) {
        ApiService svc = new ApiService();
        svc.id = "svc-e2e-1";
        svc.name = "E2E Test Service";
        svc.systemName = "e2e-test-service";
        svc.description = "End-to-end test service";
        svc.backendVersion = "oidc".equals(authType) ? "oidc" : "1";

        Authentication auth = new Authentication();
        auth.type = authType;
        if ("jwt".equals(authType)) {
            auth.oidcIssuerEndpoint = "https://sso.example.com/realms/test";
        }
        svc.authentication = auth;

        MappingRule rule1 = new MappingRule();
        rule1.httpMethod = "GET";
        rule1.pattern = "/api/users";
        MappingRule rule2 = new MappingRule();
        rule2.httpMethod = "POST";
        rule2.pattern = "/api/users";
        svc.mappingRules = List.of(rule1, rule2);

        Backend backend = new Backend();
        backend.id = "backend-1";
        backend.name = "Main Backend";
        backend.privateEndpoint = "https://api.internal.example.com";
        svc.backends = List.of(backend);

        return svc;
    }
}
