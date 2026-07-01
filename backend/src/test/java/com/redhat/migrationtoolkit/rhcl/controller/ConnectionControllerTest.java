package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.service.ThreeScaleExportService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class ConnectionControllerTest {

    @InjectMock
    ThreeScaleExportService exportService;

    @Test
    void testConnection_success_returns200() {
        when(exportService.testConnection(any())).thenReturn(true);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"url":"https://3scale.example.com","accessToken":"token123"}
                        """)
                .when().post("/api/connection/test")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    void testConnection_failure_returns502() {
        when(exportService.testConnection(any())).thenReturn(false);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"url":"https://3scale.example.com","accessToken":"bad-token"}
                        """)
                .when().post("/api/connection/test")
                .then()
                .statusCode(502)
                .body("success", is(false));
    }

    @Test
    void testConnection_missingUrl_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"url":"","accessToken":"token123"}
                        """)
                .when().post("/api/connection/test")
                .then()
                .statusCode(400)
                .body("success", is(false))
                .body("message", containsString("URL"));
    }

    @Test
    void testConnection_missingToken_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"url":"https://3scale.example.com","accessToken":""}
                        """)
                .when().post("/api/connection/test")
                .then()
                .statusCode(400)
                .body("success", is(false))
                .body("message", containsString("token"));
    }

    @Test
    void testConnection_nullUrl_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"accessToken":"token123"}
                        """)
                .when().post("/api/connection/test")
                .then()
                .statusCode(400);
    }
}
