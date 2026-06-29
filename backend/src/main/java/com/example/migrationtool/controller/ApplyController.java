package com.example.migrationtool.controller;

import com.example.migrationtool.util.Messages;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import jakarta.inject.Inject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * 古い apiVersion / スキーマ構造を kuadrant.io/v1 互換に正規化する。
     *
     * apiVersion の置換:
     *   kuadrant.io/v1beta2 → kuadrant.io/v1
     *   kuadrant.io/v1beta1 → kuadrant.io/v1
     *   gateway.networking.k8s.io/v1beta1 → gateway.networking.k8s.io/v1
     *
     * AuthPolicy スキーマ変更 (v1beta2 → v1):
     *   v1beta2: credentials は apiKey の内側 (子フィールド)
     *   v1:      credentials は apiKey と同階層 (兄弟フィールド)
     *
     * 正規表現で任意のインデント幅に対応し、apiKey の直下にある credentials を
     * apiKey と同じインデントレベルへ移動する。
     */
    private String normalizeApiVersion(String yaml) {
        String result = yaml
                .replace("apiVersion: kuadrant.io/v1beta2", "apiVersion: kuadrant.io/v1")
                .replace("apiVersion: kuadrant.io/v1beta1", "apiVersion: kuadrant.io/v1")
                .replace("apiVersion: gateway.networking.k8s.io/v1beta1", "apiVersion: gateway.networking.k8s.io/v1");

        // AuthPolicy v1beta2 → v1: apiKey 内の credentials を apiKey と同階層に移動
        //
        // マッチパターン例 (インデント幅は可変):
        //   <N spaces>apiKey:\n
        //     ... (selector 等の apiKey 子フィールド) ...
        //   <N+2 spaces>credentials:\n
        //     <N+4 spaces>(authorizationHeader|cookie|customHeader|queryString):\n
        //
        // → credentials ブロック全体を apiKey の外（N スペース）に移す。
        //
        // アルゴリズム:
        //   1. "^(\s+)apiKey:" 行を見つける → その空白数 = apiKeyIndent
        //   2. credentials が apiKeyIndent+2 スペースで始まっていれば内側にある
        //   3. credentials ブロック（credentials 行 + 子行）を抽出し、
        //      apiKeyIndent スペースのインデントに付け直して apiKey ブロックの後ろに移動
        result = moveCredentialsOutOfApiKey(result);

        return result;
    }

    /**
     * YAML 文字列内で apiKey の子になっている credentials フィールドを
     * apiKey と同階層（兄弟）に移動する。
     * インデント幅に依存しない行単位の処理。
     */
    private String moveCredentialsOutOfApiKey(String yaml) {
        // Pattern: apiKey: が出現し、その直後のフィールドに credentials がある場合を検出
        // credentials が authorizationHeader/cookie/customHeader/queryString/header を持つ行を対象
        Pattern pattern = Pattern.compile(
            "(?m)(^([ \\t]+)apiKey:\\n" +           // group1=apiKeyLine+newline, group2=apiKeyIndent
            "(?:\\2[ \\t]+(?!credentials:).*\\n)*)" + // apiKey の子フィールド(credentials以外)
            "(\\2[ \\t]+credentials:\\n" +           // group3=credentials行(apiKeyより深いインデント)
            "(?:\\2[ \\t]{2,}.*\\n)*)"               // credentials の子フィールド
        );

        Matcher m = pattern.matcher(yaml);
        if (!m.find()) {
            return yaml; // パターン未マッチ → 変換不要
        }

        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) {
            String apiKeyBlock      = m.group(1); // apiKey: + その子行
            String apiKeyIndent     = m.group(2); // apiKey のインデント文字列
            String credentialsBlock = m.group(3); // credentials: + その子行

            // credentials ブロックの各行から余分なインデント(2文字分)を除去して
            // apiKey と同じインデントレベルに揃える
            String fixedCredentials = credentialsBlock.lines()
                .map(line -> {
                    // apiKeyIndent より 2文字深いインデントがあれば 2文字削る
                    String deeper = apiKeyIndent + "  ";
                    return line.startsWith(deeper) ? apiKeyIndent + line.substring(deeper.length()) : line;
                })
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));

            // apiKey ブロックの後に credentials を付ける（apiKey と同階層）
            m.appendReplacement(sb, Matcher.quoteReplacement(apiKeyBlock + fixedCredentials));
        }
        m.appendTail(sb);
        return sb.toString();
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
