package com.redhat.migrationtoolkit.rhcl.service;

import com.redhat.migrationtoolkit.rhcl.model.ApiService;
import com.redhat.migrationtoolkit.rhcl.model.Authentication;
import com.redhat.migrationtoolkit.rhcl.model.Backend;
import com.redhat.migrationtoolkit.rhcl.model.MappingRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversionServiceTest {

    private ConversionService service;

    @BeforeEach
    void setUp() {
        service = new ConversionService();
    }

    // ── convert() basic output ────────────────────────────────────────────────

    @Test
    void convert_basicService_producesRequiredFiles() {
        ApiService svc = basicService("my-api", "my-api");
        Map<String, String> files = service.convert(svc, "test-ns");

        assertTrue(files.containsKey("gateway.yaml"));
        assertTrue(files.containsKey("httproute.yaml"));
        assertTrue(files.containsKey("policy.yaml"));
        assertTrue(files.containsKey("secret.yaml"));
        assertTrue(files.containsKey("configmap.yaml"));
        assertTrue(files.containsKey("apiproduct.yaml"));
        assertTrue(files.containsKey("README.md"));
    }

    @Test
    void convert_apiKeyAuth_includesApiKeyYaml() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("apiKey");
        Map<String, String> files = service.convert(svc, "test-ns");
        assertTrue(files.containsKey("apikey.yaml"));
        assertTrue(files.containsKey("secret.yaml"));
    }

    @Test
    void convert_jwtAuth_noApiKeyYaml() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "test-ns");
        assertFalse(files.containsKey("apikey.yaml"));
    }

    @Test
    void convert_externalBackend_includesServiceEntry() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "test-ns", "https://api.external.example.com");
        assertTrue(files.containsKey("serviceentry.yaml"));
        assertTrue(files.containsKey("destinationrule.yaml"));
    }

    @Test
    void convert_internalBackend_noServiceEntry() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "test-ns", "http://my-service:8080");
        assertFalse(files.containsKey("serviceentry.yaml"));
        assertFalse(files.containsKey("destinationrule.yaml"));
    }

    @Test
    void convert_nullBackendUrl_treatedAsInternal() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "test-ns", null);
        assertFalse(files.containsKey("serviceentry.yaml"));
    }

    // ── Gateway YAML content ──────────────────────────────────────────────────

    @Test
    void convert_gatewayYaml_containsNamespace() {
        ApiService svc = basicService("test-svc", "test-svc");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "my-namespace");
        String gw = files.get("gateway.yaml");
        assertTrue(gw.contains("namespace: my-namespace"));
    }

    @Test
    void convert_gatewayYaml_containsGatewayClass() {
        ApiService svc = basicService("test-svc", "test-svc");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns");
        String gw = files.get("gateway.yaml");
        assertTrue(gw.contains("gatewayClassName: istio"));
        assertTrue(gw.contains("apiVersion: gateway.networking.k8s.io/v1"));
        assertTrue(gw.contains("kind: Gateway"));
    }

    // ── HTTPRoute YAML content ────────────────────────────────────────────────

    @Test
    void convert_httpRouteYaml_containsMappingRules() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        MappingRule rule = new MappingRule();
        rule.httpMethod = "GET";
        rule.pattern = "/api/users";
        svc.mappingRules = List.of(rule);

        Map<String, String> files = service.convert(svc, "ns");
        String httproute = files.get("httproute.yaml");
        assertTrue(httproute.contains("/api/users"));
        assertTrue(httproute.contains("GET"));
    }

    @Test
    void convert_httpRouteYaml_wildcardReplaced() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        MappingRule rule = new MappingRule();
        rule.httpMethod = "GET";
        rule.pattern = "/api/{?}";
        svc.mappingRules = List.of(rule);

        Map<String, String> files = service.convert(svc, "ns");
        String httproute = files.get("httproute.yaml");
        assertTrue(httproute.contains("*"), "Wildcard {?} should be replaced with *");
        assertFalse(httproute.contains("{?}"));
    }

    @Test
    void convert_externalBackend_httpRouteContainsUrlRewrite() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns", "https://api.external.com");
        String httproute = files.get("httproute.yaml");
        assertTrue(httproute.contains("URLRewrite") || httproute.contains("urlRewrite"));
    }

    // ── AuthPolicy YAML content ───────────────────────────────────────────────

    @Test
    void convert_jwtAuth_authPolicyContainsJwt() {
        ApiService svc = basicService("my-api", "my-api");
        Authentication auth = auth("jwt");
        auth.oidcIssuerEndpoint = "https://sso.example.com/realms/test";
        svc.authentication = auth;

        Map<String, String> files = service.convert(svc, "ns");
        String policy = files.get("policy.yaml");
        assertTrue(policy.contains("jwt"));
        assertTrue(policy.contains("sso.example.com"));
    }

    @Test
    void convert_apiKeyAuth_authPolicyContainsApiKey() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("apiKey");
        Map<String, String> files = service.convert(svc, "ns");
        String policy = files.get("policy.yaml");
        assertTrue(policy.contains("apiKey") || policy.contains("api-key-auth"));
    }

    @Test
    void convert_noneAuth_authPolicyIsEmpty() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = null;
        Map<String, String> files = service.convert(svc, "ns");
        String policy = files.get("policy.yaml");
        assertTrue(policy.contains("authentication: {}") || policy.contains("AuthPolicy"));
    }

    // ── ConfigMap YAML content ────────────────────────────────────────────────

    @Test
    void convert_configMapContainsServiceInfo() {
        ApiService svc = basicService("My Service", "my-service");
        svc.id = "svc-42";
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns");
        String cm = files.get("configmap.yaml");
        assertTrue(cm.contains("svc-42"));
        assertTrue(cm.contains("My Service"));
    }

    @Test
    void convert_configMapWithBackendUrl() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns", "http://my-svc:8080");
        String cm = files.get("configmap.yaml");
        assertTrue(cm.contains("my-svc:8080"));
    }

    @Test
    void convert_configMapWithServiceBackend() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Backend b = new Backend();
        b.privateEndpoint = "http://backend-service:9090";
        svc.backends = List.of(b);
        Map<String, String> files = service.convert(svc, "ns");
        String cm = files.get("configmap.yaml");
        assertTrue(cm.contains("backend-service:9090"));
    }

    // ── APIProduct YAML content ───────────────────────────────────────────────

    @Test
    void convert_apiProductContainsDisplayName() {
        ApiService svc = basicService("My Great API", "my-great-api");
        svc.description = "A great API for testing";
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns");
        String ap = files.get("apiproduct.yaml");
        assertTrue(ap.contains("My Great API"));
        assertTrue(ap.contains("devportal.kuadrant.io"));
    }

    // ── Secret YAML content ───────────────────────────────────────────────────

    @Test
    void convert_apiKeyAuth_secretContainsApiKey() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("apiKey");
        Map<String, String> files = service.convert(svc, "ns");
        String secret = files.get("secret.yaml");
        assertTrue(secret.contains("api_key"));
        assertFalse(secret.contains("REPLACE_ME"));
    }

    @Test
    void convert_jwtAuth_secretContainsPlaceholders() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns");
        String secret = files.get("secret.yaml");
        assertTrue(secret.contains("REPLACE_ME"));
    }

    // ── ServiceEntry + DestinationRule ────────────────────────────────────────

    @Test
    void convert_externalBackend_serviceEntryContainsHost() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns", "https://api.external.example.com");
        String se = files.get("serviceentry.yaml");
        assertTrue(se.contains("api.external.example.com"));
        assertTrue(se.contains("ServiceEntry"));
    }

    @Test
    void convert_externalBackend_destinationRuleContainsTls() {
        ApiService svc = basicService("my-api", "my-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns", "https://api.external.example.com");
        String dr = files.get("destinationrule.yaml");
        assertTrue(dr.contains("SIMPLE") || dr.contains("tls"));
    }

    // ── detectBackendType() ───────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "http://my-service:8080",
            "http://svc.namespace.svc.cluster.local", "my-service"})
    void detectBackendType_internal(String url) {
        ConversionService.BackendType type = service.detectBackendType(url.isBlank() ? null : url);
        assertEquals(ConversionService.BackendType.INTERNAL, type);
    }

    @ParameterizedTest
    @CsvSource({
        "https://api.example.com, EXTERNAL",
        "https://foo.ecs.us-east-2.on.aws/api, EXTERNAL",
        "http://api.external-provider.com, EXTERNAL",
        "http://svc.cluster.local, EXTERNAL"
    })
    void detectBackendType_external(String url, String expected) {
        ConversionService.BackendType type = service.detectBackendType(url);
        assertEquals(ConversionService.BackendType.valueOf(expected), type);
    }

    @Test
    void detectBackendType_null_isInternal() {
        assertEquals(ConversionService.BackendType.INTERNAL, service.detectBackendType(null));
    }

    // ── README content ────────────────────────────────────────────────────────

    @Test
    void convert_readmeContainsServiceName() {
        ApiService svc = basicService("Customer API", "customer-api");
        svc.id = "cust-1";
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns");
        String readme = files.get("README.md");
        assertTrue(readme.contains("Customer API"));
        assertTrue(readme.contains("cust-1"));
    }

    @Test
    void convert_externalReadme_mentionsExternal() {
        ApiService svc = basicService("External API", "external-api");
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns", "https://ext.example.com");
        String readme = files.get("README.md");
        assertTrue(readme.contains("ext.example.com") || readme.contains("External"));
    }

    // ── Kebab case conversion ─────────────────────────────────────────────────

    @Test
    void convert_systemName_usedForResourceName() {
        ApiService svc = new ApiService();
        svc.id = "1";
        svc.name = "My API";
        svc.systemName = "my_api_service";
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns");
        String gw = files.get("gateway.yaml");
        assertTrue(gw.contains("my-api-service"));
    }

    @Test
    void convert_nameUsedWhenSystemNameNull() {
        ApiService svc = new ApiService();
        svc.id = "1";
        svc.name = "My Service";
        svc.systemName = null;
        svc.authentication = auth("jwt");
        Map<String, String> files = service.convert(svc, "ns");
        assertNotNull(files.get("gateway.yaml"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ApiService basicService(String name, String systemName) {
        ApiService svc = new ApiService();
        svc.id = "svc-1";
        svc.name = name;
        svc.systemName = systemName;
        return svc;
    }

    private Authentication auth(String type) {
        Authentication a = new Authentication();
        a.type = type;
        return a;
    }
}
