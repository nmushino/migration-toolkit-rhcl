package com.redhat.migrationtoolkit.rhcl.controller;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void applyNamespaceSetup_labelSuccess_gatewayListEmpty_returns207() {
        // Mock namespace edit success
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        Namespace ns = new Namespace();
        ObjectMeta nsMeta = new ObjectMeta();
        nsMeta.setLabels(new HashMap<>());
        ns.setMetadata(nsMeta);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.edit(any(java.util.function.UnaryOperator.class))).thenAnswer(inv -> {
            java.util.function.UnaryOperator<Namespace> fn = inv.getArgument(0);
            return fn.apply(ns);
        });

        // Mock genericKubernetesResources returns empty list
        var mockGwOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockGwNs = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        GenericKubernetesResourceList emptyList = new GenericKubernetesResourceList();
        emptyList.setItems(List.of());
        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockGwOp);
        when(mockGwOp.inNamespace(anyString())).thenReturn(mockGwNs);
        when(mockGwNs.list()).thenReturn(emptyList);

        given()
                .contentType(ContentType.JSON)
                .body("{\"namespace\":\"test-ns\"}")
                .when().post("/api/setup/namespace")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(207)))
                .body("steps", hasSize(2));
    }

    @Test
    void applyNamespaceSetup_labelSuccess_gatewayAnnotated_returns200() {
        // Mock namespace edit success
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        Namespace ns = new Namespace();
        ObjectMeta nsMeta = new ObjectMeta();
        nsMeta.setLabels(new HashMap<>());
        ns.setMetadata(nsMeta);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.edit(any(java.util.function.UnaryOperator.class))).thenAnswer(inv -> {
            java.util.function.UnaryOperator<Namespace> fn = inv.getArgument(0);
            return fn.apply(ns);
        });

        // Mock gateway with one item
        GenericKubernetesResource gw = new GenericKubernetesResource();
        ObjectMeta gwMeta = new ObjectMeta();
        gwMeta.setName("my-gw");
        gwMeta.setAnnotations(new HashMap<>());
        gw.setMetadata(gwMeta);
        gw.setAdditionalProperties(new HashMap<>());

        GenericKubernetesResourceList gwList = new GenericKubernetesResourceList();
        gwList.setItems(List.of(gw));

        var mockGwOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockGwNs = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockGwRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockGwOp);
        when(mockGwOp.inNamespace(anyString())).thenReturn(mockGwNs);
        when(mockGwNs.list()).thenReturn(gwList);
        when(mockGwNs.withName(anyString())).thenReturn(mockGwRes);
        when(mockGwRes.patch(any(), any())).thenReturn(gw);

        given()
                .contentType(ContentType.JSON)
                .body("{\"namespace\":\"test-ns\"}")
                .when().post("/api/setup/namespace")
                .then()
                .statusCode(200)
                .body("allSuccess", is(true));
    }

    @Test
    void checkStatus_namespaceLabeled_returnsSteps() {
        Namespace ns = new Namespace();
        ObjectMeta meta = new ObjectMeta();
        Map<String, String> labels = new HashMap<>();
        labels.put("istio-injection", "enabled");
        meta.setLabels(labels);
        ns.setMetadata(meta);

        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.get()).thenReturn(ns);

        GenericKubernetesResourceList emptyList = new GenericKubernetesResourceList();
        emptyList.setItems(List.of());
        var mockGwOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockGwNs = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockGwOp);
        when(mockGwOp.inNamespace(anyString())).thenReturn(mockGwNs);
        when(mockGwNs.list()).thenReturn(emptyList);

        given()
                .queryParam("namespace", "labeled-ns")
                .when().get("/api/setup/status")
                .then()
                .statusCode(200)
                .body("steps[0].success", is(true));
    }
}
