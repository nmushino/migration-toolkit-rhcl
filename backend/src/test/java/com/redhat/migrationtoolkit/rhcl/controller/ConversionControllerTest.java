package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.model.ApiService;
import com.redhat.migrationtoolkit.rhcl.model.Authentication;
import com.redhat.migrationtoolkit.rhcl.model.CompatibilityResult;
import com.redhat.migrationtoolkit.rhcl.service.CompatibilityService;
import com.redhat.migrationtoolkit.rhcl.service.ConversionService;
import com.redhat.migrationtoolkit.rhcl.service.ThreeScaleExportService;
import com.redhat.migrationtoolkit.rhcl.service.ValidationService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@QuarkusTest
class ConversionControllerTest {

    @Inject
    EntityManager em;

    @AfterEach
    @Transactional
    void cleanUp() {
        em.createQuery("DELETE FROM ConversionHistoryEntity").executeUpdate();
        em.createQuery("DELETE FROM ProjectEntity").executeUpdate();
    }

    @InjectMock
    ThreeScaleExportService exportService;

    @InjectMock
    CompatibilityService compatibilityService;

    @InjectMock
    ConversionService conversionService;

    @InjectMock
    ValidationService validationService;

    // ── /api/convert ──────────────────────────────────────────────────────────

    @Test
    void convert_noServiceIds_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"serviceIds\":[], \"namespace\":\"test\", \"threescaleUrl\":\"https://x.com\", \"accessToken\":\"tok\"}")
                .when().post("/api/convert")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void convert_nullServiceIds_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"namespace\":\"test\", \"threescaleUrl\":\"https://x.com\", \"accessToken\":\"tok\"}")
                .when().post("/api/convert")
                .then()
                .statusCode(400);
    }

    @Test
    void convert_singleService_success() {
        ApiService svc = buildService("svc-1", "My API", "jwt");
        CompatibilityResult compat = buildCompat("svc-1", 90, "HIGH");

        when(exportService.exportService(anyString(), anyString(), anyString())).thenReturn(svc);
        when(compatibilityService.check(any())).thenReturn(compat);
        when(conversionService.convert(any(), anyString(), isNull())).thenReturn(
                Map.of("gateway.yaml", "kind: Gateway", "httproute.yaml", "kind: HTTPRoute"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "serviceIds": ["svc-1"],
                          "namespace": "test-ns",
                          "threescaleUrl": "https://3scale.example.com",
                          "accessToken": "my-token"
                        }
                        """)
                .when().post("/api/convert")
                .then()
                .statusCode(200)
                .body("results", hasSize(1))
                .body("results[0].serviceId", equalTo("svc-1"))
                .body("results[0].compatibilityScore", equalTo(90));
    }

    @Test
    void convert_multipleServices_allIncluded() {
        ApiService svc1 = buildService("svc-1", "API One", "jwt");
        ApiService svc2 = buildService("svc-2", "API Two", "apiKey");
        CompatibilityResult compat = buildCompat("svc-1", 80, "HIGH");

        when(exportService.exportService(anyString(), anyString(), anyString()))
                .thenReturn(svc1).thenReturn(svc2);
        when(compatibilityService.check(any())).thenReturn(compat);
        when(conversionService.convert(any(), anyString(), isNull()))
                .thenReturn(Map.of("gateway.yaml", "kind: Gateway"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "serviceIds": ["svc-1", "svc-2"],
                          "namespace": "ns",
                          "threescaleUrl": "https://3scale.example.com",
                          "accessToken": "tok"
                        }
                        """)
                .when().post("/api/convert")
                .then()
                .statusCode(200)
                .body("results", hasSize(2));
    }

    @Test
    void convert_defaultNamespaceWhenNull() {
        ApiService svc = buildService("svc-1", "My API", "jwt");
        when(exportService.exportService(anyString(), anyString(), anyString())).thenReturn(svc);
        when(compatibilityService.check(any())).thenReturn(buildCompat("svc-1", 70, "MEDIUM"));
        when(conversionService.convert(any(), anyString(), isNull()))
                .thenReturn(Map.of("gateway.yaml", "kind: Gateway"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "serviceIds": ["svc-1"],
                          "threescaleUrl": "https://3scale.example.com",
                          "accessToken": "tok"
                        }
                        """)
                .when().post("/api/convert")
                .then()
                .statusCode(200);
    }

    @Test
    void convert_exportServiceThrows_serviceMarkedFailed() {
        when(exportService.exportService(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("3scale unavailable"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "serviceIds": ["bad-svc"],
                          "namespace": "ns",
                          "threescaleUrl": "https://3scale.example.com",
                          "accessToken": "tok"
                        }
                        """)
                .when().post("/api/convert")
                .then()
                .statusCode(200)
                .body("results[0].status", equalTo("FAILED"))
                .body("results[0].error", notNullValue());
    }

    @Test
    void convert_withExternalBackendUrl_passed() {
        ApiService svc = buildService("svc-1", "API One", "jwt");
        when(exportService.exportService(anyString(), anyString(), anyString())).thenReturn(svc);
        when(compatibilityService.check(any())).thenReturn(buildCompat("svc-1", 80, "HIGH"));
        when(conversionService.convert(any(), anyString(), anyString()))
                .thenReturn(Map.of("gateway.yaml", "kind: Gateway"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "serviceIds": ["svc-1"],
                          "namespace": "ns",
                          "threescaleUrl": "https://3scale.example.com",
                          "accessToken": "tok",
                          "externalBackendUrl": "https://api.external.example.com"
                        }
                        """)
                .when().post("/api/convert")
                .then()
                .statusCode(200)
                .body("results", hasSize(1));
    }

    @Test
    void convert_systemNameNormalized_toKebabCase() {
        ApiService svc = buildService("svc-1", "My Great API", "jwt");
        svc.systemName = "My Great API";
        when(exportService.exportService(anyString(), anyString(), anyString())).thenReturn(svc);
        when(compatibilityService.check(any())).thenReturn(buildCompat("svc-1", 85, "HIGH"));
        when(conversionService.convert(any(), anyString(), isNull()))
                .thenReturn(Map.of("gateway.yaml", "kind: Gateway"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "serviceIds": ["svc-1"],
                          "namespace": "ns",
                          "threescaleUrl": "https://3scale.example.com",
                          "accessToken": "tok"
                        }
                        """)
                .when().post("/api/convert")
                .then()
                .statusCode(200)
                .body("results[0].packageName", equalTo("my-great-api"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ApiService buildService(String id, String name, String authType) {
        ApiService svc = new ApiService();
        svc.id = id;
        svc.name = name;
        svc.systemName = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        Authentication auth = new Authentication();
        auth.type = authType;
        svc.authentication = auth;
        return svc;
    }

    private CompatibilityResult buildCompat(String serviceId, int score, String level) {
        CompatibilityResult r = new CompatibilityResult();
        r.serviceId = serviceId;
        r.score = score;
        r.level = level;
        r.items = List.of();
        return r;
    }
}
