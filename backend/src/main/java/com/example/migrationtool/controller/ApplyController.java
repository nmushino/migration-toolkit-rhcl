package com.example.migrationtool.controller;

import com.example.migrationtool.util.Messages;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Path("/api/apply")
@Tag(name = "Apply", description = "Apply YAML resources to OpenShift")
public class ApplyController {

    private static final Logger LOG = Logger.getLogger(ApplyController.class);

    @Inject
    KubernetesClient client;

    @Inject
    Messages messages;

    public record ApplyRequest(String namespace, Map<String, String> files) {}

    public record ApplyResult(String fileName, boolean success, String message) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Apply YAML files to a Kubernetes/OpenShift namespace")
    public Response applyFiles(ApplyRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", messages.get("apply.error.noFiles")))
                    .build();
        }

        String namespace = (request.namespace() != null && !request.namespace().isBlank())
                ? request.namespace() : "default";

        List<ApplyResult> results = new ArrayList<>();
        boolean anySuccess = false;
        boolean anyError = false;

        // SERVER_SIDE_APPLY パッチコンテキスト（全リソース共通）
        PatchContext ctx = new PatchContext.Builder()
                .withPatchType(PatchType.SERVER_SIDE_APPLY)
                .withFieldManager("migration-toolkit")
                .withForce(true)
                .build();

        for (Map.Entry<String, String> entry : request.files().entrySet()) {
            String fileName = entry.getKey();
            String yaml = entry.getValue();
            try {
                // client.load() は内部で Handlers.getRegisteredKubernetesResource() を呼び、
                // AuthPolicy など未登録 CRD で "Could not find a registered handler" を投げる。
                // 回避策: Serialization.unmarshal() で強制的に GenericKubernetesResource として
                // デシリアライズし、genericKubernetesResources() DSL のみで適用する。
                // これにより Handlers は一切呼ばれない。
                List<GenericKubernetesResource> items = splitYamlDocs(normalizeApiVersion(yaml)).stream()
                        .filter(doc -> !doc.isBlank())
                        .map(doc -> Serialization.unmarshal(doc, GenericKubernetesResource.class))
                        .filter(gkr -> gkr != null && gkr.getKind() != null)
                        .toList();

                List<String> errors = new ArrayList<>();
                for (GenericKubernetesResource gkr : items) {
                    String kind = gkr.getKind();
                    String name = gkr.getMetadata() != null ? gkr.getMetadata().getName() : "unknown";
                    // namespace: YAML内の指定を優先、なければリクエストの namespace を使用
                    String ns = (gkr.getMetadata() != null
                            && gkr.getMetadata().getNamespace() != null
                            && !gkr.getMetadata().getNamespace().isBlank())
                            ? gkr.getMetadata().getNamespace()
                            : namespace;
                    try {
                        // genericKubernetesResources(apiVersion, kind) は内部で API discovery を
                        // 行うため、CRD が発見できない場合に "Could not find the metadata" を投げる。
                        // ResourceDefinitionContext を明示的に構築することで discovery を回避する。
                        ResourceDefinitionContext rdc = buildRdc(gkr.getApiVersion(), kind);
                        client.genericKubernetesResources(rdc)
                                .inNamespace(ns)
                                .withName(name)
                                .patch(ctx, gkr);
                        LOG.infof("Applied %s/%s to namespace %s", kind, name, ns);
                    } catch (Exception ex) {
                        String itemMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                        LOG.warnf("Failed to apply %s/%s: %s", kind, name, itemMsg);
                        errors.add(kind + "/" + name + ": " + itemMsg);
                    }
                }
                if (errors.isEmpty()) {
                    results.add(new ApplyResult(fileName, true, messages.get("apply.success")));
                    anySuccess = true;
                } else {
                    results.add(new ApplyResult(fileName, false, String.join("; ", errors)));
                    anyError = true;
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                LOG.warnf("Failed to apply %s: %s", fileName, msg);
                results.add(new ApplyResult(fileName, false, msg));
                anyError = true;
            }
        }

        int status = anyError && !anySuccess ? 422 : 200;
        return Response.status(status).entity(Map.of(
                "results", results,
                "namespace", namespace,
                "successCount", results.stream().filter(ApplyResult::success).count(),
                "errorCount", results.stream().filter(r -> !r.success()).count()
        )).build();
    }

    /** マルチドキュメント YAML を "---" で分割して個々のドキュメント文字列リストを返す。 */
    private List<String> splitYamlDocs(String yaml) {
        return Arrays.asList(yaml.split("(?m)^---\\s*$"));
    }

    /**
     * 古い apiVersion を現行バージョンへ正規化する。
     * ZIP が旧バージョンで生成されていても適用できるようにする。
     *   kuadrant.io/v1beta2 → kuadrant.io/v1
     *   kuadrant.io/v1beta1 → kuadrant.io/v1
     *   gateway.networking.k8s.io/v1beta1 → gateway.networking.k8s.io/v1
     */
    private String normalizeApiVersion(String yaml) {
        return yaml
                .replace("apiVersion: kuadrant.io/v1beta2", "apiVersion: kuadrant.io/v1")
                .replace("apiVersion: kuadrant.io/v1beta1", "apiVersion: kuadrant.io/v1")
                .replace("apiVersion: gateway.networking.k8s.io/v1beta1", "apiVersion: gateway.networking.k8s.io/v1");
    }

    /**
     * apiVersion (例: "kuadrant.io/v1beta2" または "v1") と kind から
     * ResourceDefinitionContext を構築する。
     * API discovery を使わないため、CRD が未登録でも動作する。
     */
    private ResourceDefinitionContext buildRdc(String apiVersion, String kind) {
        String group;
        String version;
        if (apiVersion != null && apiVersion.contains("/")) {
            String[] parts = apiVersion.split("/", 2);
            group   = parts[0];
            version = parts[1];
        } else {
            group   = "";          // core API group
            version = apiVersion != null ? apiVersion : "v1";
        }
        return new ResourceDefinitionContext.Builder()
                .withGroup(group)
                .withVersion(version)
                .withKind(kind)
                .withPlural(toPlural(kind))
                .withNamespaced(true)
                .build();
    }

    /**
     * kind を複数形 (plural) に変換する。
     * Kubernetes の命名規則に従った簡易実装。
     *   AuthPolicy        → authpolicies
     *   RateLimitPolicy   → ratelimitpolicies
     *   HTTPRoute         → httproutes
     *   Gateway           → gateways
     *   Secret            → secrets
     *   ConfigMap         → configmaps
     */
    private String toPlural(String kind) {
        if (kind == null) return "unknowns";
        String lower = kind.toLowerCase();
        if (lower.endsWith("policy"))  return lower.substring(0, lower.length() - 6) + "policies";
        if (lower.endsWith("status"))  return lower + "es";
        if (lower.endsWith("s"))       return lower + "es";
        return lower + "s";
    }
}
