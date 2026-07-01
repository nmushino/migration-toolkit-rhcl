package com.example.migrationtool.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class GatewayInfoControllerTest {

    @InjectMock(convertScopes = true)
    KubernetesClient kubernetesClient;

    @Test
    void getGatewayInfo_missingNamespace_returns400() {
        given()
                .queryParam("name", "my-gateway")
                .when().get("/api/gateway/info")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void getGatewayInfo_missingName_returns400() {
        given()
                .queryParam("namespace", "test-ns")
                .when().get("/api/gateway/info")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void getGatewayInfo_bothMissing_returns400() {
        given()
                .when().get("/api/gateway/info")
                .then()
                .statusCode(400);
    }

    @Test
    void getGatewayInfo_clientError_returns500() {
        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("Connection refused"));

        given()
                .queryParam("namespace", "test-ns")
                .queryParam("name", "my-gateway")
                .when().get("/api/gateway/info")
                .then()
                .statusCode(500)
                .body("ready", is(false));
    }

    @Test
    void getGatewayInfo_gatewayNotFound_returns404() {
        var mockResources = Mockito.mock(
                io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockNamespaced = Mockito.mock(
                io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockResource = Mockito.mock(
                io.fabric8.kubernetes.client.dsl.Resource.class);

        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockResources);
        when(mockResources.inNamespace(any())).thenReturn(mockNamespaced);
        when(mockNamespaced.withName(any())).thenReturn(mockResource);
        when(mockResource.get()).thenReturn(null);

        given()
                .queryParam("namespace", "test-ns")
                .queryParam("name", "my-gateway")
                .when().get("/api/gateway/info")
                .then()
                .statusCode(404)
                .body("ready", is(false));
    }
}
