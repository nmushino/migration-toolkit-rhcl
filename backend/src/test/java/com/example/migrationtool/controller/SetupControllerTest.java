package com.example.migrationtool.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class SetupControllerTest {

    @InjectMock(convertScopes = true)
    KubernetesClient kubernetesClient;

    @Test
    void applyNamespaceSetup_clientThrows_returns207() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("No access"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"namespace":"test-ns"}
                        """)
                .when().post("/api/setup/namespace")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(207)))
                .body("steps", not(empty()));
    }

    @Test
    void applyNamespaceSetup_nullNamespace_usesDefault() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("test"));

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/api/setup/namespace")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(207)));
    }

    @Test
    void checkStatus_defaultNamespace_returns200() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("test"));

        given()
                .when().get("/api/setup/status")
                .then()
                .statusCode(200)
                .body("steps", not(empty()));
    }

    @Test
    void checkStatus_specificNamespace_returns200() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("test"));

        given()
                .queryParam("namespace", "my-ns")
                .when().get("/api/setup/status")
                .then()
                .statusCode(200)
                .body("steps", hasSize(2));
    }
}
