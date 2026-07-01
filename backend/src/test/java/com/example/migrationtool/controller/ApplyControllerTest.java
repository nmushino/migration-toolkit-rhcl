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
class ApplyControllerTest {

    @InjectMock(convertScopes = true)
    KubernetesClient kubernetesClient;

    @Test
    void applyFiles_emptyRequest_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"namespace":"test","files":{}}
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void applyFiles_nullRequest_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/api/apply")
                .then()
                .statusCode(400);
    }

    @Test
    void applyFiles_kubernetesError_returns422OrOk() {
        var mockNamespaces = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockResource = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);

        when(kubernetesClient.namespaces()).thenReturn(mockNamespaces);
        when(mockNamespaces.withName(anyString())).thenReturn(mockResource);
        when(mockResource.get()).thenReturn(null);
        when(mockNamespaces.resource(any())).thenReturn(mockResource);
        when(mockResource.create()).thenReturn(null);
        when(kubernetesClient.rbac()).thenThrow(new RuntimeException("RBAC error"));
        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("Apply error"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "gateway.yaml": "apiVersion: gateway.networking.k8s.io/v1\\nkind: Gateway\\nmetadata:\\n  name: my-gw"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(422)));
    }

    @Test
    void applyFiles_multipleFiles_responseContainsResults() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("test"));
        when(kubernetesClient.rbac()).thenThrow(new RuntimeException("test"));
        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("Apply failed"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "gateway.yaml": "apiVersion: gateway.networking.k8s.io/v1\\nkind: Gateway\\nmetadata:\\n  name: gw",
                            "httproute.yaml": "apiVersion: gateway.networking.k8s.io/v1\\nkind: HTTPRoute\\nmetadata:\\n  name: rt"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .body("results", notNullValue())
                .body("namespace", equalTo("test-ns"));
    }

    @Test
    void applyFiles_defaultNamespaceWhenBlank() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("test"));
        when(kubernetesClient.rbac()).thenThrow(new RuntimeException("test"));
        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("failed"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "",
                          "files": {
                            "secret.yaml": "apiVersion: v1\\nkind: Secret\\nmetadata:\\n  name: sec"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .body("namespace", equalTo("default"));
    }
}
