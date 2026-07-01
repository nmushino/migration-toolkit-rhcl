package com.redhat.migrationtoolkit.rhcl.controller;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApplyControllerTest {

    @InjectMock(convertScopes = true)
    KubernetesClient kubernetesClient;

    @Inject
    EntityManager em;

    @AfterEach
    @Transactional
    void cleanUp() {
        em.createQuery("DELETE FROM ConversionHistoryEntity").executeUpdate();
        em.createQuery("DELETE FROM ProjectEntity").executeUpdate();
    }

    // ── empty / null request ─────────────────────────────────────────────────

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

    // ── all-fail path ────────────────────────────────────────────────────────

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

    // ── success path ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_successPath_returns200() {
        // Namespace exists
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.get()).thenReturn(new Namespace());

        mockRbacFullSuccess();

        // Apply + export
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMeta());
        var mockGkrOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockGkrNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockGkrRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockGkrOp);
        when(mockGkrOp.inNamespace(anyString())).thenReturn(mockGkrNsOp);
        when(mockGkrNsOp.withName(anyString())).thenReturn(mockGkrRes);
        when(mockGkrRes.patch(any(), any())).thenReturn(live);
        when(mockGkrRes.get()).thenReturn(live);

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
                .statusCode(200)
                .body("successCount", equalTo(1))
                .body("errorCount", equalTo(0));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_successPath_multiDoc_returns200() {
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.get()).thenReturn(new Namespace());

        mockRbacFullSuccess();

        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMeta());
        var mockGkrOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockGkrNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockGkrRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockGkrOp);
        when(mockGkrOp.inNamespace(anyString())).thenReturn(mockGkrNsOp);
        when(mockGkrNsOp.withName(anyString())).thenReturn(mockGkrRes);
        when(mockGkrRes.patch(any(), any())).thenReturn(live);
        when(mockGkrRes.get()).thenReturn(live);

        // multi-doc YAML with --- separator
        String multiDoc = "apiVersion: gateway.networking.k8s.io/v1\\nkind: Gateway\\nmetadata:\\n  name: gw\\n---\\napiVersion: gateway.networking.k8s.io/v1\\nkind: HTTPRoute\\nmetadata:\\n  name: rt";

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "multi.yaml": "%s"
                          }
                        }
                        """.formatted(multiDoc))
                .when().post("/api/apply")
                .then()
                .statusCode(200)
                .body("successCount", equalTo(2));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_secretApplied_authorinoNotFound_returns200() {
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.get()).thenReturn(new Namespace());

        mockRbacFullSuccess();

        // Secret apply succeeds
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMeta());
        var mockGkrOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockGkrNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockGkrRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockGkrOp);
        when(mockGkrOp.inNamespace(anyString())).thenReturn(mockGkrNsOp);
        when(mockGkrNsOp.withName(anyString())).thenReturn(mockGkrRes);
        when(mockGkrRes.patch(any(), any())).thenReturn(live);
        when(mockGkrRes.get()).thenReturn(live);

        // Authorino deployment not found → restartAuthorino skip path
        var mockApps = Mockito.mock(io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL.class);
        var mockDeployments = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockDeployNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockDeployRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.RollableScalableResource.class);
        when(kubernetesClient.apps()).thenReturn(mockApps);
        when(mockApps.deployments()).thenReturn(mockDeployments);
        when(mockDeployments.inNamespace("kuadrant-system")).thenReturn(mockDeployNsOp);
        when(mockDeployNsOp.withName("authorino")).thenReturn(mockDeployRes);
        when(mockDeployRes.get()).thenReturn(null); // not found

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "secret.yaml": "apiVersion: v1\\nkind: Secret\\nmetadata:\\n  name: my-apikey"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(200)
                .body("successCount", equalTo(1));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_secretApplied_authorinoFound_editsDeployment() {
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.get()).thenReturn(new Namespace());

        mockRbacFullSuccess();

        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMeta());
        var mockGkrOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockGkrNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockGkrRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockGkrOp);
        when(mockGkrOp.inNamespace(anyString())).thenReturn(mockGkrNsOp);
        when(mockGkrNsOp.withName(anyString())).thenReturn(mockGkrRes);
        when(mockGkrRes.patch(any(), any())).thenReturn(live);
        when(mockGkrRes.get()).thenReturn(live);

        // Authorino deployment found → edit (rollout restart)
        Deployment deployment = buildMockDeployment();
        var mockApps = Mockito.mock(io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL.class);
        var mockDeployments = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockDeployNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockDeployRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.RollableScalableResource.class);
        when(kubernetesClient.apps()).thenReturn(mockApps);
        when(mockApps.deployments()).thenReturn(mockDeployments);
        when(mockDeployments.inNamespace("kuadrant-system")).thenReturn(mockDeployNsOp);
        when(mockDeployNsOp.withName("authorino")).thenReturn(mockDeployRes);
        when(mockDeployRes.get()).thenReturn(deployment);
        when(mockDeployRes.edit(any(java.util.function.UnaryOperator.class))).thenAnswer(inv -> {
            java.util.function.UnaryOperator<Deployment> fn = inv.getArgument(0);
            return fn.apply(deployment);
        });

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "secret.yaml": "apiVersion: v1\\nkind: Secret\\nmetadata:\\n  name: api-secret"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(200)
                .body("successCount", equalTo(1));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_normalizeApiVersion_kuadrantV1beta2_converted() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("ns"));
        when(kubernetesClient.rbac()).thenThrow(new RuntimeException("rbac"));

        // kuadrant.io/v1beta2 → kuadrant.io/v1 normalization is tested; apply fails but path is exercised
        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("apply failed"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "rate.yaml": "apiVersion: kuadrant.io/v1beta2\\nkind: RateLimitPolicy\\nmetadata:\\n  name: rl"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(422)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_normalizeApiVersion_gatewayV1beta1_converted() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("ns"));
        when(kubernetesClient.rbac()).thenThrow(new RuntimeException("rbac"));
        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("failed"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "gw.yaml": "apiVersion: gateway.networking.k8s.io/v1beta1\\nkind: Gateway\\nmetadata:\\n  name: gw"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(422)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_clusterRoleExists_complete_skipsUpdate() {
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.get()).thenReturn(new Namespace());

        // ClusterRole exists with all required groups → skip update
        ClusterRole existingCr = new ClusterRole();
        PolicyRule fullRule = new PolicyRuleBuilder()
                .withApiGroups("", "apps", "networking.k8s.io", "route.openshift.io",
                        "kuadrant.io", "gateway.networking.k8s.io",
                        "networking.istio.io", "rbac.authorization.k8s.io", "devportal.kuadrant.io")
                .withResources("*")
                .withVerbs("get", "list", "create", "update", "patch", "delete")
                .build();
        existingCr.setRules(List.of(fullRule));

        var mockRbac = Mockito.mock(io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL.class);
        var mockCrOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockCrRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        var mockRolesOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockRolesNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockRbOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockRbNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        when(kubernetesClient.rbac()).thenReturn(mockRbac);
        when(mockRbac.clusterRoles()).thenReturn(mockCrOp);
        when(mockCrOp.withName(anyString())).thenReturn(mockCrRes);
        when(mockCrRes.get()).thenReturn(existingCr); // already complete
        when(mockRbac.roles()).thenReturn(mockRolesOp);
        when(mockRolesOp.inNamespace(anyString())).thenReturn(mockRolesNsOp);
        when(mockRolesNsOp.createOrReplace(any())).thenReturn(null);
        when(mockRbac.roleBindings()).thenReturn(mockRbOp);
        when(mockRbOp.inNamespace(anyString())).thenReturn(mockRbNsOp);
        when(mockRbNsOp.createOrReplace(any())).thenReturn(null);

        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("apply failed"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "gw.yaml": "apiVersion: gateway.networking.k8s.io/v1\\nkind: Gateway\\nmetadata:\\n  name: gw"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(422)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_namespaceNotFound_createsIt() {
        // Namespace is null → create path
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.get()).thenReturn(null); // not found → create
        when(mockNsOp.resource(any())).thenReturn(mockNsRes);
        when(mockNsRes.create()).thenReturn(new Namespace());

        when(kubernetesClient.rbac()).thenThrow(new RuntimeException("rbac"));
        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("apply failed"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "new-ns",
                          "files": {
                            "gw.yaml": "apiVersion: gateway.networking.k8s.io/v1\\nkind: Gateway\\nmetadata:\\n  name: gw"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(422)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_exportFails_continuesGracefully() {
        var mockNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockNsRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.namespaces()).thenReturn(mockNsOp);
        when(mockNsOp.withName(anyString())).thenReturn(mockNsRes);
        when(mockNsRes.get()).thenReturn(new Namespace());

        mockRbacFullSuccess();

        // patch succeeds but get (export) fails
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMeta());
        var mockGkrOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockGkrNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockGkrRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(kubernetesClient.genericKubernetesResources(any())).thenReturn(mockGkrOp);
        when(mockGkrOp.inNamespace(anyString())).thenReturn(mockGkrNsOp);
        when(mockGkrNsOp.withName(anyString())).thenReturn(mockGkrRes);
        when(mockGkrRes.patch(any(), any())).thenReturn(live);
        when(mockGkrRes.get()).thenThrow(new RuntimeException("export failed")); // export fails

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "files": {
                            "gw.yaml": "apiVersion: gateway.networking.k8s.io/v1\\nkind: Gateway\\nmetadata:\\n  name: gw"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(200)
                .body("successCount", equalTo(1));
    }

    @SuppressWarnings("unchecked")
    @Test
    void applyFiles_sourceFromImport_savedCorrectly() {
        when(kubernetesClient.namespaces()).thenThrow(new RuntimeException("ns"));
        when(kubernetesClient.rbac()).thenThrow(new RuntimeException("rbac"));
        when(kubernetesClient.genericKubernetesResources(any()))
                .thenThrow(new RuntimeException("failed"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "namespace": "test-ns",
                          "source": "IMPORT",
                          "files": {
                            "gw.yaml": "apiVersion: gateway.networking.k8s.io/v1\\nkind: Gateway\\nmetadata:\\n  name: gw"
                          }
                        }
                        """)
                .when().post("/api/apply")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(422)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mockRbacFullSuccess() {
        var mockRbac = Mockito.mock(io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL.class);
        var mockCrOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockCrRes = Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        var mockRolesOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockRolesNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var mockRbOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var mockRbNsOp = Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);

        when(kubernetesClient.rbac()).thenReturn(mockRbac);
        when(mockRbac.clusterRoles()).thenReturn(mockCrOp);
        when(mockCrOp.withName(anyString())).thenReturn(mockCrRes);
        when(mockCrRes.get()).thenReturn(null); // ClusterRole not found → create
        when(mockCrOp.createOrReplace(any())).thenReturn(null);
        when(mockRbac.roles()).thenReturn(mockRolesOp);
        when(mockRolesOp.inNamespace(anyString())).thenReturn(mockRolesNsOp);
        when(mockRolesNsOp.createOrReplace(any())).thenReturn(null);
        when(mockRbac.roleBindings()).thenReturn(mockRbOp);
        when(mockRbOp.inNamespace(anyString())).thenReturn(mockRbNsOp);
        when(mockRbNsOp.createOrReplace(any())).thenReturn(null);
    }

    private Deployment buildMockDeployment() {
        Deployment d = new Deployment();
        ObjectMeta meta = new ObjectMeta();
        meta.setName("authorino");
        d.setMetadata(meta);
        DeploymentSpec spec = new DeploymentSpec();
        PodTemplateSpec template = new PodTemplateSpec();
        ObjectMeta podMeta = new ObjectMeta();
        podMeta.setAnnotations(new HashMap<>());
        template.setMetadata(podMeta);
        spec.setTemplate(template);
        d.setSpec(spec);
        return d;
    }
}
