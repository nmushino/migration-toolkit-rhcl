package com.example.migrationtool.service;

import com.example.migrationtool.model.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;

@ApplicationScoped
public class ConversionService {

    /**
     * バックエンドの種別を表す列挙型。
     * INTERNAL : OpenShift/Kubernetes 内の Service（ServiceEntry・DestinationRule・URLRewrite 不要）
     * EXTERNAL : クラスター外の HTTPS エンドポイント（ServiceEntry・DestinationRule・URLRewrite が必要）
     */
    enum BackendType { INTERNAL, EXTERNAL }

    public Map<String, String> convert(ApiService service, String namespace) {
        return convert(service, namespace, null);
    }

    public Map<String, String> convert(ApiService service, String namespace, String backendUrl) {
        Map<String, String> files = new LinkedHashMap<>();
        String name = toKebabCase(service.systemName != null ? service.systemName : service.name);

        BackendType backendType = detectBackendType(backendUrl);
        String externalHost = backendType == BackendType.EXTERNAL ? extractHostname(backendUrl) : null;
        String internalService = backendType == BackendType.INTERNAL ? extractInternalService(backendUrl, name) : null;
        int internalPort = backendType == BackendType.INTERNAL ? extractPort(backendUrl, 8080) : 8080;

        files.put("gateway.yaml",    generateGateway(name, namespace));
        files.put("httproute.yaml",  generateHttpRoute(name, namespace, service, backendType, externalHost, internalService, internalPort));
        files.put("policy.yaml",     generateAuthPolicy(name, namespace, service));
        files.put("secret.yaml",     generateSecret(name, namespace, service));
        files.put("configmap.yaml",  generateConfigMap(name, namespace, service, backendUrl));

        if (backendType == BackendType.EXTERNAL) {
            files.put("serviceentry.yaml",    generateServiceEntry(name, namespace, externalHost));
            files.put("destinationrule.yaml", generateDestinationRule(name, namespace, externalHost));
        }

        files.put("README.md", generateReadme(service, name, namespace, backendType, externalHost));
        return files;
    }

    // ─────────────────────────────────────────────
    // バックエンドタイプ判定
    // ─────────────────────────────────────────────

    /**
     * バックエンド URL からタイプを判定する。
     *   null / 空文字          → INTERNAL（デフォルト）
     *   *.svc / *.svc.cluster.local 形式 → INTERNAL
     *   クラスター内 DNS（ドット区切りのないホスト名）→ INTERNAL
     *   https?://external...   → EXTERNAL
     */
    BackendType detectBackendType(String url) {
        if (url == null || url.isBlank()) return BackendType.INTERNAL;
        String host = extractHostname(url);
        if (host == null) return BackendType.INTERNAL;
        // *.svc または *.svc.cluster.local → 内部
        if (host.endsWith(".svc") || host.endsWith(".svc.cluster.local")) return BackendType.INTERNAL;
        // ドットを含まないシンプルなホスト名（例: my-service）→ 内部
        if (!host.contains(".")) return BackendType.INTERNAL;
        return BackendType.EXTERNAL;
    }

    /** URL からホスト名を抽出する。失敗時は null。 */
    private String extractHostname(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String s = url.trim();
            if (!s.contains("://")) s = "https://" + s;
            return new java.net.URI(s).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 内部バックエンド URL からサービス名を抽出する。
     * "http://my-service:8080" → "my-service"
     * 抽出できない場合は "{name}-backend" を返す。
     */
    private String extractInternalService(String url, String name) {
        String host = extractHostname(url);
        if (host == null || host.isBlank()) return name + "-backend";
        // "svc.cluster.local" サフィックスを除去して先頭のサービス名だけ返す
        return host.split("\\.")[0];
    }

    /** URL からポート番号を抽出する。失敗時はデフォルト値を返す。 */
    private int extractPort(String url, int defaultPort) {
        if (url == null || url.isBlank()) return defaultPort;
        try {
            String s = url.trim();
            if (!s.contains("://")) s = "http://" + s;
            int port = new java.net.URI(s).getPort();
            return port > 0 ? port : defaultPort;
        } catch (Exception e) {
            return defaultPort;
        }
    }

    // ─────────────────────────────────────────────
    // Gateway
    // ─────────────────────────────────────────────

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

    // ─────────────────────────────────────────────
    // HTTPRoute
    // ─────────────────────────────────────────────

    private String generateHttpRoute(String name, String namespace, ApiService service,
                                     BackendType backendType, String externalHost,
                                     String internalService, int internalPort) {
        // 外部バックエンド: port 443 + URLRewrite で Host ヘッダーを書き換え
        // 内部バックエンド: URL 指定のポート（デフォルト 8080）、フィルターなし
        int backendPort   = backendType == BackendType.EXTERNAL ? 443 : internalPort;
        String backendSvc = backendType == BackendType.EXTERNAL
                ? (name + "-backend")
                : (internalService != null ? internalService : name + "-backend");

        String urlRewriteFilter = backendType == BackendType.EXTERNAL ? """
      filters:
        - type: URLRewrite
          urlRewrite:
            hostname: "%s"
""".formatted(externalHost) : "";

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
                String path   = rule.pattern != null ? rule.pattern.replaceAll("\\{\\?\\}", "*") : "/";
                String method = rule.httpMethod != null ? rule.httpMethod : "GET";
                sb.append("""
    - matches:
        - path:
            type: PathPrefix
            value: "%s"
          method: %s
%s      backendRefs:
        - name: %s
          port: %d
""".formatted(path, method, urlRewriteFilter, backendSvc, backendPort));
            }
        } else {
            sb.append("""
    - matches:
        - path:
            type: PathPrefix
            value: "/"
%s      backendRefs:
        - name: %s
          port: %d
""".formatted(urlRewriteFilter, backendSvc, backendPort));
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // ServiceEntry（外部バックエンドのみ生成）
    // ─────────────────────────────────────────────

    private String generateServiceEntry(String name, String namespace, String externalHost) {
        String backendSvc = name + "-backend";
        return """
apiVersion: networking.istio.io/v1alpha3
kind: ServiceEntry
metadata:
  name: %s-external
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
spec:
  hosts:
  - %s
  ports:
  - number: 443
    name: https
    protocol: HTTPS
  resolution: DNS
  location: MESH_EXTERNAL
---
apiVersion: v1
kind: Service
metadata:
  name: %s
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
spec:
  type: ExternalName
  externalName: %s
  ports:
  - name: https
    port: 443
""".formatted(name, namespace, name, externalHost,
              backendSvc, namespace, name, externalHost);
    }

    // ─────────────────────────────────────────────
    // DestinationRule（外部バックエンドのみ生成）
    // ─────────────────────────────────────────────

    private String generateDestinationRule(String name, String namespace, String externalHost) {
        return """
apiVersion: networking.istio.io/v1alpha3
kind: DestinationRule
metadata:
  name: %s-backend-tls
  namespace: %s
  labels:
    app: %s
    migrated-from: 3scale
spec:
  host: %s
  trafficPolicy:
    tls:
      mode: SIMPLE
      sni: %s
""".formatted(name, namespace, name, externalHost, externalHost);
    }

    // ─────────────────────────────────────────────
    // AuthPolicy
    // ─────────────────────────────────────────────

    private String generateAuthPolicy(String name, String namespace, ApiService service) {
        String authType = service.authentication != null ? service.authentication.type : "none";

        if ("jwt".equals(authType)) {
            String issuer = service.authentication.oidcIssuerEndpoint != null
                    ? service.authentication.oidcIssuerEndpoint
                    : "https://your-oidc-provider/realms/your-realm";
            return """
apiVersion: kuadrant.io/v1
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
apiVersion: kuadrant.io/v1
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
apiVersion: kuadrant.io/v1
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

    // ─────────────────────────────────────────────
    // Secret / ConfigMap
    // ─────────────────────────────────────────────

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

    private String generateConfigMap(String name, String namespace, ApiService service, String backendUrl) {
        String url = "";
        if (backendUrl != null && !backendUrl.isBlank()) {
            url = backendUrl.trim();
        } else if (service.backends != null && !service.backends.isEmpty()) {
            url = service.backends.get(0).privateEndpoint != null
                    ? service.backends.get(0).privateEndpoint : "";
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
""".formatted(name, namespace, name, url, service.name, service.id);
    }

    // ─────────────────────────────────────────────
    // README
    // ─────────────────────────────────────────────

    private String generateReadme(ApiService service, String name, String namespace,
                                  BackendType backendType, String externalHost) {
        String backendSection = switch (backendType) {
            case EXTERNAL -> """

## External Backend（外部 HTTPS サービス）

バックエンドはクラスター外の HTTPS エンドポイントです。

**外部エンドポイント:** `%s`

| File | 説明 |
|------|------|
| serviceentry.yaml | Istio に外部ホストを登録（ServiceEntry + ExternalName Service） |
| destinationrule.yaml | 外部への接続に TLS（SIMPLE）を適用 |
| httproute.yaml | `URLRewrite` で Host ヘッダーを外部ホスト名に書き換え |
""".formatted(externalHost);
            case INTERNAL -> """

## Internal Backend（OpenShift 内 Service）

バックエンドはクラスター内の Kubernetes Service です。
ServiceEntry・DestinationRule・URLRewrite フィルターは不要なため生成されていません。

> `httproute.yaml` の `backendRefs.name` が実際の Service 名と一致していることを確認してください。
""";
        };

        String fileList = backendType == BackendType.EXTERNAL
                ? "| serviceentry.yaml | Istio ServiceEntry + ExternalName Service for external backend |\n| destinationrule.yaml | TLS origination to external host |"
                : "";

        return """
# %s - Connectivity Link Migration

## Overview
3scale Migration Toolkit で生成した Kubernetes/OpenShift リソースです。

**元の 3scale サービス:** %s (ID: %s)
**対象 Namespace:** %s
**バックエンドタイプ:** %s
%s
## Files

| File | 説明 |
|------|------|
| gateway.yaml | 外部トラフィックの入口となる Gateway |
| httproute.yaml | 3scale マッピングルールから変換した HTTPRoute |
| policy.yaml | 認証・認可ポリシー（AuthPolicy） |
| secret.yaml | 認証情報（apply 前に値を置き換えてください） |
| configmap.yaml | 設定情報 |
%s

## Prerequisites
- OpenShift with Connectivity Link (Kuadrant) operator
- Gateway API CRDs
- Istio

## Installation

```bash
# secret.yaml の値を確認・更新してから適用
vi secret.yaml
kubectl apply -f . -n %s

# Gateway 確認
kubectl get gateway %s-gateway -n %s
kubectl get httproute %s-route -n %s
```

## Notes
- `secret.yaml` の認証情報を apply 前に必ず更新してください
- `httproute.yaml` のバックエンドサービス名が実際の Service 名と一致しているか確認してください
- まずステージング環境でテストしてください
""".formatted(
            service.name, service.name, service.id, namespace,
            backendType == BackendType.EXTERNAL ? "External HTTPS" : "Internal OpenShift Service",
            backendSection,
            fileList,
            namespace, name, namespace, name, namespace
        );
    }

    // ─────────────────────────────────────────────
    // ユーティリティ
    // ─────────────────────────────────────────────

    private String toKebabCase(String input) {
        if (input == null) return "service";
        return input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
