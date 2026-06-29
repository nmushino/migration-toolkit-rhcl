package com.example.migrationtool.service;

import com.example.migrationtool.model.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;

@ApplicationScoped
public class ConversionService {

    public Map<String, String> convert(ApiService service, String namespace) {
        Map<String, String> files = new LinkedHashMap<>();
        String name = toKebabCase(service.systemName != null ? service.systemName : service.name);

        files.put("gateway.yaml", generateGateway(name, namespace));
        files.put("httproute.yaml", generateHttpRoute(name, namespace, service));
        files.put("policy.yaml", generateAuthPolicy(name, namespace, service));
        files.put("secret.yaml", generateSecret(name, namespace, service));
        files.put("configmap.yaml", generateConfigMap(name, namespace, service));
        files.put("README.md", generateReadme(service, name, namespace));

        return files;
    }

    private String generateGateway(String name, String namespace) {
        return """
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: %s-gateway
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
spec:
  gatewayClassName: istio
  listeners:
    - name: http
      protocol: HTTP
      port: 80
      allowedRoutes:
        namespaces:
          from: Same
    - name: https
      protocol: HTTPS
      port: 443
      tls:
        mode: Terminate
        certificateRefs:
          - name: %s-tls
      allowedRoutes:
        namespaces:
          from: Same
""".formatted(name, namespace, name, name);
    }

    private String generateHttpRoute(String name, String namespace, ApiService service) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: %s-route
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
spec:
  parentRefs:
    - name: %s-gateway
      namespace: %s
  rules:
""".formatted(name, namespace, name, name, namespace));

        if (service.mappingRules != null && !service.mappingRules.isEmpty()) {
            for (MappingRule rule : service.mappingRules) {
                String path = rule.pattern != null ? rule.pattern.replaceAll("\\{\\?\\}", "*") : "/";
                String method = rule.httpMethod != null ? rule.httpMethod : "GET";
                String backendName = service.backends != null && !service.backends.isEmpty()
                        ? toKebabCase(service.backends.get(0).systemName != null ? service.backends.get(0).systemName : service.backends.get(0).name)
                        : name + "-backend";
                sb.append("""
    - matches:
        - path:
            type: PathPrefix
            value: "%s"
          method: %s
      backendRefs:
        - name: %s
          port: 8080
""".formatted(path, method, backendName));
            }
        } else {
            String backendName = service.backends != null && !service.backends.isEmpty()
                    ? toKebabCase(service.backends.get(0).name)
                    : name + "-backend";
            sb.append("""
    - matches:
        - path:
            type: PathPrefix
            value: "/"
      backendRefs:
        - name: %s
          port: 8080
""".formatted(backendName));
        }
        return sb.toString();
    }

    private String generateAuthPolicy(String name, String namespace, ApiService service) {
        String authType = service.authentication != null ? service.authentication.type : "none";

        if ("jwt".equals(authType)) {
            String issuer = service.authentication.oidcIssuerEndpoint != null
                    ? service.authentication.oidcIssuerEndpoint
                    : "https://your-oidc-provider/realms/your-realm";
            return """
apiVersion: kuadrant.io/v1beta2
kind: AuthPolicy
metadata:
  name: %s-auth
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
spec:
  targetRef:
    group: gateway.networking.k8s.io
    kind: HTTPRoute
    name: %s-route
  rules:
    authentication:
      jwt-auth:
        jwt:
          issuerUrl: %s
""".formatted(name, namespace, name, name, issuer);
        } else if ("apiKey".equals(authType)) {
            return """
apiVersion: kuadrant.io/v1beta2
kind: AuthPolicy
metadata:
  name: %s-auth
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
spec:
  targetRef:
    group: gateway.networking.k8s.io
    kind: HTTPRoute
    name: %s-route
  rules:
    authentication:
      api-key-auth:
        apiKey:
          selector:
            matchLabels:
              app: %s
          credentials:
            authorizationHeader:
              prefix: APIKEY
""".formatted(name, namespace, name, name, name);
        }

        return """
apiVersion: kuadrant.io/v1beta2
kind: AuthPolicy
metadata:
  name: %s-auth
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
spec:
  targetRef:
    group: gateway.networking.k8s.io
    kind: HTTPRoute
    name: %s-route
  rules:
    authentication: {}
""".formatted(name, namespace, name, name);
    }

    private String generateSecret(String name, String namespace, ApiService service) {
        return """
apiVersion: v1
kind: Secret
metadata:
  name: %s-credentials
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
type: Opaque
stringData:
  # TODO: Replace with actual credentials
  client-id: "REPLACE_ME"
  client-secret: "REPLACE_ME"
""".formatted(name, namespace, name);
    }

    private String generateConfigMap(String name, String namespace, ApiService service) {
        String backendUrl = "";
        if (service.backends != null && !service.backends.isEmpty()) {
            backendUrl = service.backends.get(0).privateEndpoint != null
                    ? service.backends.get(0).privateEndpoint
                    : "http://backend-service:8080";
        }
        return """
apiVersion: v1
kind: ConfigMap
metadata:
  name: %s-config
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
data:
  backend-url: "%s"
  service-name: "%s"
  original-3scale-service-id: "%s"
""".formatted(name, namespace, name, backendUrl, service.name, service.id);
    }

    private String generateReadme(ApiService service, String name, String namespace) {
        return """
# %s - Connectivity Link Migration

## Overview
This directory contains Kubernetes/OpenShift resources generated by the 3scale Migration Toolkit.

**Original 3scale Service:** %s (ID: %s)
**Target Namespace:** %s

## Files

| File | Description |
|------|-------------|
| gateway.yaml | Gateway resource defining entry points |
| httproute.yaml | HTTPRoute rules converted from 3scale mapping rules |
| policy.yaml | AuthPolicy for authentication/authorization |
| secret.yaml | Credentials secret (REPLACE values before applying) |
| configmap.yaml | Configuration data |

## Prerequisites
- OpenShift with Connectivity Link (Kuadrant) operator installed
- Gateway API CRDs installed
- Istio or other supported gateway implementation

## Installation

```bash
# Review and update secret values first
vi secret.yaml

# Apply all resources
kubectl apply -f . -n %s

# Verify Gateway is ready
kubectl get gateway %s-gateway -n %s

# Verify HTTPRoute
kubectl get httproute %s-route -n %s
```

## Notes
- Review and update `secret.yaml` with actual credentials before applying
- Verify backend service names in `httproute.yaml` match your actual services
- Test the migration in a staging environment first
""".formatted(service.name, service.name, service.id, namespace, namespace, name, namespace, name, namespace);
    }

    private String toKebabCase(String input) {
        if (input == null) return "service";
        return input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
