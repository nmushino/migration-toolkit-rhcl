package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.model.ApiService;
import com.redhat.migrationtoolkit.rhcl.model.Authentication;
import com.redhat.migrationtoolkit.rhcl.model.CompatibilityResult;
import com.redhat.migrationtoolkit.rhcl.service.CompatibilityService;
import com.redhat.migrationtoolkit.rhcl.service.ThreeScaleExportService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class ExportControllerTest {

    @InjectMock
    ThreeScaleExportService exportService;

    @InjectMock
    CompatibilityService compatibilityService;

    @Test
    void getServices_missingParams_returns400() {
        given()
                .when().get("/api/services")
                .then()
                .statusCode(400);
    }

    @Test
    void getServices_withParams_returns200() {
        ApiService svc = new ApiService();
        svc.id = "1";
        svc.name = "Test API";
        when(exportService.exportServices(anyString(), anyString())).thenReturn(List.of(svc));

        given()
                .queryParam("url", "https://3scale.example.com")
                .queryParam("accessToken", "token123")
                .when().get("/api/services")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo("1"));
    }

    @Test
    void getService_byId_returns200() {
        ApiService svc = new ApiService();
        svc.id = "42";
        svc.name = "My API";
        when(exportService.exportService(anyString(), anyString(), anyString())).thenReturn(svc);

        given()
                .queryParam("url", "https://3scale.example.com")
                .queryParam("accessToken", "token123")
                .when().get("/api/services/42")
                .then()
                .statusCode(200)
                .body("id", equalTo("42"));
    }

    @Test
    void checkCompatibility_returns200() {
        ApiService svc = new ApiService();
        svc.id = "42";
        svc.name = "My API";
        Authentication auth = new Authentication();
        auth.type = "jwt";
        svc.authentication = auth;

        CompatibilityResult result = new CompatibilityResult();
        result.serviceId = "42";
        result.score = 80;
        result.level = "HIGH";
        result.items = List.of();

        when(exportService.exportService(anyString(), anyString(), anyString())).thenReturn(svc);
        when(compatibilityService.check(any())).thenReturn(result);

        given()
                .queryParam("url", "https://3scale.example.com")
                .queryParam("accessToken", "token123")
                .when().get("/api/services/42/compatibility")
                .then()
                .statusCode(200)
                .body("score", equalTo(80))
                .body("level", equalTo("HIGH"));
    }
}
