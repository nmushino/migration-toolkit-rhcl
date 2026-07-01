package com.redhat.migrationtoolkit.rhcl.service;

import com.redhat.migrationtoolkit.rhcl.dto.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidationServiceTest {

    private ValidationService service;

    @BeforeEach
    void setUp() {
        service = new ValidationService();
    }

    // ── Syntax validation ─────────────────────────────────────────────────────

    @Test
    void validate_validYaml_ok() {
        Map<String, String> files = Map.of("gateway.yaml", """
                apiVersion: gateway.networking.k8s.io/v1
                kind: Gateway
                metadata:
                  name: test-gateway
                  namespace: test-ns
                spec:
                  gatewayClassName: istio
                """);
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "OK".equals(i.status)
                && i.check.contains("YAML Syntax")));
    }

    @Test
    void validate_invalidYaml_error() {
        Map<String, String> files = Map.of("bad.yaml", """
                apiVersion: gateway.networking.k8s.io/v1
                kind: Gateway
                  bad-indent: this is wrong
                  - invalid list here
                """);
        ValidationResult result = service.validate(files);
        assertFalse(result.valid);
        assertTrue(result.items.stream().anyMatch(i -> "ERROR".equals(i.status)));
    }

    @Test
    void validate_emptyContent_treatedAsValid() {
        Map<String, String> files = Map.of("empty.yaml", "");
        assertDoesNotThrow(() -> service.validate(files));
    }

    @Test
    void validate_nonYamlFileIgnored() {
        Map<String, String> files = Map.of("README.md", "# This is a readme");
        ValidationResult result = service.validate(files);
        assertTrue(result.items.isEmpty());
    }

    // ── CRD validation ────────────────────────────────────────────────────────

    @Test
    void validate_knownCrd_gatewayNetworking_ok() {
        Map<String, String> files = Map.of("httproute.yaml", yaml("gateway.networking.k8s.io/v1",
                "HTTPRoute", "my-route", "test-ns"));
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "OK".equals(i.status)
                && i.check.startsWith("CRD:")));
    }

    @Test
    void validate_knownCrd_kuadrant_ok() {
        Map<String, String> files = Map.of("authpolicy.yaml", yaml("kuadrant.io/v1",
                "AuthPolicy", "my-auth", "test-ns"));
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "OK".equals(i.status)
                && i.check.contains("kuadrant.io")));
    }

    @Test
    void validate_knownCrd_istio_ok() {
        Map<String, String> files = Map.of("serviceentry.yaml", yaml("networking.istio.io/v1alpha3",
                "ServiceEntry", "my-se", "test-ns"));
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "OK".equals(i.status)
                && i.check.contains("istio")));
    }

    @Test
    void validate_unknownCrd_warning() {
        Map<String, String> files = Map.of("custom.yaml", yaml("custom.example.com/v1",
                "MyCustomResource", "my-res", "test-ns"));
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.check.startsWith("CRD:")));
    }

    @Test
    void validate_coreV1_ok() {
        Map<String, String> files = Map.of("secret.yaml", yaml("v1", "Secret", "my-secret", "test-ns"));
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "OK".equals(i.status)
                && i.check.contains("v1")));
    }

    // ── Namespace validation ──────────────────────────────────────────────────

    @Test
    void validate_namespaceSet_ok() {
        Map<String, String> files = Map.of("gw.yaml", yaml("gateway.networking.k8s.io/v1",
                "Gateway", "my-gw", "my-namespace"));
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "OK".equals(i.status)
                && i.check.startsWith("Namespace:")));
    }

    @Test
    void validate_namespaceMissing_warning() {
        Map<String, String> files = Map.of("gw.yaml", """
                apiVersion: gateway.networking.k8s.io/v1
                kind: Gateway
                metadata:
                  name: my-gw
                spec: {}
                """);
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.check.startsWith("Namespace:")));
    }

    // ── Reference validation ──────────────────────────────────────────────────

    @Test
    void validate_httprouteWithGateway_ok() {
        String httprouteYaml = """
                apiVersion: gateway.networking.k8s.io/v1
                kind: HTTPRoute
                metadata:
                  name: my-route
                  namespace: test-ns
                spec:
                  parentRefs:
                    - name: my-gateway
                """;
        Map<String, String> files = Map.of(
                "httproute.yaml", httprouteYaml,
                "gateway.yaml", yaml("gateway.networking.k8s.io/v1", "Gateway", "my-gateway", "test-ns")
        );
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "OK".equals(i.status)
                && i.check.contains("Gateway")));
    }

    @Test
    void validate_httprouteWithoutGateway_warning() {
        String httprouteYaml = """
                apiVersion: gateway.networking.k8s.io/v1
                kind: HTTPRoute
                metadata:
                  name: my-route
                  namespace: test-ns
                spec:
                  parentRefs:
                    - name: missing-gateway
                """;
        ValidationResult result = service.validate(Map.of("httproute.yaml", httprouteYaml));
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.check.contains("Gateway")));
    }

    @Test
    void validate_authPolicyWithHttpRoute_ok() {
        String authPolicyYaml = """
                apiVersion: kuadrant.io/v1
                kind: AuthPolicy
                metadata:
                  name: my-auth
                  namespace: test-ns
                spec: {}
                """;
        Map<String, String> files = Map.of(
                "policy.yaml", authPolicyYaml,
                "httproute.yaml", yaml("gateway.networking.k8s.io/v1", "HTTPRoute", "my-route", "test-ns")
        );
        ValidationResult result = service.validate(files);
        assertTrue(result.items.stream().anyMatch(i -> "OK".equals(i.status)
                && i.check.contains("AuthPolicy")));
    }

    @Test
    void validate_authPolicyWithoutHttpRoute_warning() {
        String authPolicyYaml = """
                apiVersion: kuadrant.io/v1
                kind: AuthPolicy
                metadata:
                  name: my-auth
                  namespace: test-ns
                spec: {}
                """;
        ValidationResult result = service.validate(Map.of("policy.yaml", authPolicyYaml));
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.check.contains("AuthPolicy")));
    }

    @Test
    void validate_replaceMePlaceholder_warning() {
        String secretYaml = """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: my-secret
                  namespace: test-ns
                stringData:
                  client-id: "REPLACE_ME"
                """;
        ValidationResult result = service.validate(Map.of("secret.yaml", secretYaml));
        assertTrue(result.items.stream().anyMatch(i -> "WARNING".equals(i.status)
                && i.message.contains("placeholder")));
    }

    // ── Overall validity ──────────────────────────────────────────────────────

    @Test
    void validate_allOk_validTrue() {
        Map<String, String> files = Map.of(
                "gateway.yaml", yaml("gateway.networking.k8s.io/v1", "Gateway", "gw", "ns"),
                "httproute.yaml", yaml("gateway.networking.k8s.io/v1", "HTTPRoute", "rt", "ns")
        );
        ValidationResult result = service.validate(files);
        assertNotNull(result);
        assertTrue(result.valid, "Should be valid when no ERROR items");
    }

    @Test
    void validate_withError_validFalse() {
        Map<String, String> files = Map.of("bad.yaml", "{{invalid: yaml: content: :");
        ValidationResult result = service.validate(files);
        assertFalse(result.valid);
    }

    @Test
    void validate_multiDocumentYaml_validatesAll() {
        String multiDoc = """
                apiVersion: gateway.networking.k8s.io/v1
                kind: Gateway
                metadata:
                  name: gw
                  namespace: ns
                spec: {}
                ---
                apiVersion: gateway.networking.k8s.io/v1
                kind: HTTPRoute
                metadata:
                  name: rt
                  namespace: ns
                spec: {}
                """;
        ValidationResult result = service.validate(Map.of("multi.yaml", multiDoc));
        assertTrue(result.items.stream().anyMatch(i -> i.message.contains("2 documents")));
    }

    @Test
    void validate_emptyMap_noItems() {
        ValidationResult result = service.validate(Map.of());
        assertTrue(result.items.isEmpty());
        assertTrue(result.valid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String yaml(String apiVersion, String kind, String name, String namespace) {
        return String.format("""
                apiVersion: %s
                kind: %s
                metadata:
                  name: %s
                  namespace: %s
                spec: {}
                """, apiVersion, kind, name, namespace);
    }
}
