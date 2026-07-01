package com.example.migrationtool.controller;

import com.example.migrationtool.service.PackageService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class PackageControllerTest {

    @InjectMock
    PackageService packageService;

    @Test
    void downloadZip_validRequest_returns200() {
        byte[] fakeZip = new byte[]{80, 75, 3, 4}; // PK header
        when(packageService.createZip(anyString(), any())).thenReturn(fakeZip);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "yamlFiles": {"gateway.yaml": "apiVersion: gateway.networking.k8s.io/v1"},
                          "packageName": "test-package"
                        }
                        """)
                .when().post("/api/download/zip")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString("test-package.zip"));
    }

    @Test
    void downloadZip_missingYamlFiles_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"packageName": "test-package"}
                        """)
                .when().post("/api/download/zip")
                .then()
                .statusCode(400);
    }

    @Test
    void downloadZip_emptyYamlFiles_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"yamlFiles": {}, "packageName": "test-package"}
                        """)
                .when().post("/api/download/zip")
                .then()
                .statusCode(400);
    }

    @Test
    void downloadZip_defaultPackageName_usedWhenMissing() {
        byte[] fakeZip = new byte[]{80, 75, 3, 4};
        when(packageService.createZip(anyString(), any())).thenReturn(fakeZip);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"yamlFiles": {"file.yaml": "content"}}
                        """)
                .when().post("/api/download/zip")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString("migration-package.zip"));
    }

    @Test
    void downloadFromHistory_notFound_returns404() {
        given()
                .when().get("/api/download/history/99999")
                .then()
                .statusCode(404);
    }
}
