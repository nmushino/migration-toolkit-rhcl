package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.entity.ConversionHistoryEntity;
import com.redhat.migrationtoolkit.rhcl.util.Messages;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Path("/api/apply")
@Tag(name = "Apply", description = "Apply YAML resources to OpenShift")
public class ApplyController {

    private static final Logger LOG = Logger.getLogger(ApplyController.class);

    @Inject
    KubernetesClient client;

    @Inject
    Messages messages;

    @Inject
    ObjectMapper objectMapper;

    public record ApplyRequest(String namespace, Map<String, String> files, String source) {}

    public record ApplyResult(String fileName, boolean success, String message) {}

    record ResourceResult(String fileName, String kind, String name, String namespace,
                          boolean success, String error) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(summary = "Apply YAML files to a Kubernetes/OpenShift namespace")
    public Response applyFiles(ApplyRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", messages.get("apply.error.noFiles")))
                    .build();
        }

        String namespace = (request.namespace() != null && !request.namespace().isBlank())
                ? request.namespace() : "default";
        String source = (request.source() != null && !request.source().isBlank())
                ? request.source() : "CONVERT";

        ensureNamespace(namespace);
        ensureRbac(namespace);

        PatchContext ctx = new PatchContext.Builder()
                .withPatchType(PatchType.SERVER_SIDE_APPLY)
                .withFieldManager("migration-toolkit")
                .withForce(true)
                .build();

        List<ApplyResult> fileResults = new ArrayList<>();
        List<ResourceResult> resourceResults = new ArrayList<>();
        Map<String, String> exportedYamls = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : request.files().entrySet()) {
            String fileName = entry.getKey();
            String yaml = entry.getValue();
            try {
                List<GenericKubernetesResource> items = splitYamlDocs(normalizeApiVersion(yaml)).stream()
                        .filter(doc -> !doc.isBlank())
                        .map(doc -> Serialization.unmarshal(doc, GenericKubernetesResource.class))
                        .filter(gkr -> gkr != null && gkr.getKind() != null)
                        .toList();

                List<String> errors = new ArrayList<>();
                StringBuilder exportedSb = new StringBuilder();

                for (GenericKubernetesResource gkr : items) {
                    String kind = gkr.getKind();
                    String name = gkr.getMetadata() != null ? gkr.getMetadata().getName() : "unknown";
                    // リクエストの namespace で常に上書き（YAML 内の namespace が古い/存在しない場合の対策）
                    String ns = namespace;
                    if (gkr.getMetadata() != null) {
                        gkr.getMetadata().setNamespace(ns);
                    }
                    try {
                        ResourceDefinitionContext rdc = buildRdc(gkr.getApiVersion(), kind);
                        client.genericKubernetesResources(rdc).inNamespace(ns).withName(name).patch(ctx, gkr);
                        LOG.infof("Applied %s/%s to namespace %s", kind, name, ns);
                        resourceResults.add(new ResourceResult(fileName, kind, name, ns, true, null));

                        // cluster から実リソースを export (-o yaml 相当)
                        try {
                            GenericKubernetesResource live = client.genericKubernetesResources(rdc)
                                    .inNamespace(ns).withName(name).get();
                            if (live != null) {
                                if (exportedSb.length() > 0) {
                                    exportedSb.append("\n---\n");
                                }
                                exportedSb.append(Serialization.asYaml(live));
                            }
                        } catch (Exception ex2) {
                            LOG.warnf("Export failed for %s/%s: %s", kind, name, ex2.getMessage());
                        }
                    } catch (Exception ex) {
                        String itemMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                        LOG.warnf("Failed to apply %s/%s: %s", kind, name, itemMsg);
                        errors.add(kind + "/" + name + ": " + itemMsg);
                        resourceResults.add(new ResourceResult(fileName, kind, name, ns, false, itemMsg));
                    }
                }

                if (exportedSb.length() > 0) {
                    exportedYamls.put(fileName, exportedSb.toString());
                }

                if (errors.isEmpty()) {
                    fileResults.add(new ApplyResult(fileName, true, messages.get("apply.success")));
                } else {
                    fileResults.add(new ApplyResult(fileName, false, String.join("; ", errors)));
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                LOG.warnf("Failed to apply %s: %s", fileName, msg);
                fileResults.add(new ApplyResult(fileName, false, msg));
                resourceResults.add(new ResourceResult(fileName, "?", "?", namespace, false, msg));
            }
        }

        long successCount = resourceResults.stream().filter(ResourceResult::success).count();
        long failureCount = resourceResults.stream().filter(r -> !r.success()).count();

        // Secret が適用された場合、Authorino を再起動して新しい API キーを認識させる
        boolean secretApplied = resourceResults.stream()
                .anyMatch(r -> r.success() && "Secret".equals(r.kind()));
        if (secretApplied) {
            restartAuthorino();
        }

        saveHistory(source, namespace, resourceResults, exportedYamls, successCount, failureCount);

        boolean anySuccess = fileResults.stream().anyMatch(ApplyResult::success);
        boolean anyError   = fileResults.stream().anyMatch(r -> !r.success());
        int status = anyError && !anySuccess ? 422 : 200;

        return Response.status(status).entity(Map.of(
                "results", fileResults,
                "namespace", namespace,
                "successCount", successCount,
                "errorCount", failureCount
        )).build();
    }

    private void saveHistory(String source, String namespace,
                             List<ResourceResult> resourceResults,
                             Map<String, String> exportedYamls,
                             long successCount, long failureCount) {
        try {
            List<Map<String, String>> failures = resourceResults.stream()
                    .filter(r -> !r.success())
                    .map(r -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("fileName", r.fileName());
                        m.put("kind", r.kind());
                        m.put("name", r.name());
                        m.put("error", r.error() != null ? r.error() : "");
                        return m;
                    })
                    .collect(Collectors.toList());

            ConversionHistoryEntity entity = new ConversionHistoryEntity();
            entity.source         = source;
            entity.namespace      = namespace;
            entity.status         = failureCount == 0 ? "COMPLETED" : (successCount == 0 ? "FAILED" : "PARTIAL");
            entity.totalCount     = resourceResults.size();
            entity.successCount   = (int) successCount;
            entity.failureCount   = (int) failureCount;
            entity.failureDetails = objectMapper.writeValueAsString(failures);
            entity.exportedYaml   = objectMapper.writeValueAsString(exportedYamls);
            entity.persist();
            LOG.infof("History saved: source=%s ns=%s ok=%d ng=%d", source, namespace, successCount, failureCount);
        } catch (Exception e) {
            LOG.warnf("Failed to save history: %s", e.getMessage());
        }
    }

    private void restartAuthorino() {
        try {
            String authorinoNs = "kuadrant-system";
            String deployName = "authorino";
            var deployment = client.apps().deployments().inNamespace(authorinoNs).withName(deployName).get();
            if (deployment == null) {
                LOG.infof("Authorino deployment not found in %s, skipping restart", authorinoNs);
                return;
            }
            // rollout restart: kubectl.kubernetes.io/restartedAt アノテーションを更新
            String now = java.time.Instant.now().toString();
            client.apps().deployments().inNamespace(authorinoNs).withName(deployName)
                    .edit(d -> {
                        var podMeta = d.getSpec().getTemplate().getMetadata();
                        if (podMeta.getAnnotations() == null) {
                            podMeta.setAnnotations(new java.util.HashMap<>());
                        }
                        podMeta.getAnnotations().put("kubectl.kubernetes.io/restartedAt", now);
                        return d;
                    });
            LOG.infof("Authorino deployment restarted to reload API key Secrets");
        } catch (Exception e) {
            LOG.warnf("Could not restart Authorino: %s", e.getMessage());
        }
    }

    private void ensureNamespace(String namespace) {
        try {
            var ns = client.namespaces().withName(namespace).get();
            if (ns == null) {
                client.namespaces().resource(
                    new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                        .withNewMetadata().withName(namespace).endMetadata()
                        .build()
                ).create();
                LOG.infof("Namespace %s created", namespace);
            }
        } catch (Exception e) {
            LOG.warnf("Could not ensure namespace %s: %s", namespace, e.getMessage());
        }
    }

    private void ensureRbac(String namespace) {
        String saName = "migration-tool-backend";
        String saNamespace = "migration-toolkit";
        String roleName = "migration-tool-istio-manager";
        String clusterRoleName = "migration-tool-applier";

        // ── ClusterRole に不足 apiGroups を補完 ──────────────────────────────
        ensureClusterRole(clusterRoleName, saName, saNamespace);

        // ── 対象 namespace への Role / RoleBinding ────────────────────────────
        PolicyRule istioRule = new PolicyRuleBuilder()
                .withApiGroups("networking.istio.io")
                .withResources("destinationrules", "serviceentries", "serviceentrys", "virtualservices")
                .withVerbs("get", "list", "create", "update", "patch", "delete")
                .build();
        PolicyRule gatewayRule = new PolicyRuleBuilder()
                .withApiGroups("gateway.networking.k8s.io")
                .withResources("gateways", "httproutes")
                .withVerbs("get", "list", "create", "update", "patch", "delete")
                .build();
        PolicyRule kuadrantRule = new PolicyRuleBuilder()
                .withApiGroups("kuadrant.io")
                .withResources("authpolicies", "ratelimitpolicies")
                .withVerbs("get", "list", "create", "update", "patch", "delete")
                .build();
        PolicyRule coreRule = new PolicyRuleBuilder()
                .withApiGroups("")
                .withResources("secrets", "configmaps", "services")
                .withVerbs("get", "list", "create", "update", "patch", "delete")
                .build();

        Role role = new RoleBuilder()
                .withNewMetadata().withName(roleName).withNamespace(namespace).endMetadata()
                .withRules(istioRule, gatewayRule, kuadrantRule, coreRule)
                .build();

        RoleBinding binding = new RoleBindingBuilder()
                .withNewMetadata().withName(saName + "-" + roleName).withNamespace(namespace).endMetadata()
                .withNewRoleRef()
                    .withApiGroup("rbac.authorization.k8s.io")
                    .withKind("Role")
                    .withName(roleName)
                .endRoleRef()
                .addNewSubject()
                    .withKind("ServiceAccount")
                    .withName(saName)
                    .withNamespace(saNamespace)
                .endSubject()
                .build();

        // Authorino SA が対象 namespace の Secrets を読めるよう RoleBinding を追加
        // （ClusterRoleBinding がある場合は冗長だが namespace 再作成後の安全網として追加）
        RoleBinding authorinoBinding = new RoleBindingBuilder()
                .withNewMetadata()
                    .withName("authorino-secret-reader")
                    .withNamespace(namespace)
                .endMetadata()
                .withNewRoleRef()
                    .withApiGroup("rbac.authorization.k8s.io")
                    .withKind("ClusterRole")
                    .withName("authorino-manager-role")
                .endRoleRef()
                .addNewSubject()
                    .withKind("ServiceAccount")
                    .withName("authorino-authorino")
                    .withNamespace("kuadrant-system")
                .endSubject()
                .build();

        try {
            client.rbac().roles().inNamespace(namespace).createOrReplace(role);
            client.rbac().roleBindings().inNamespace(namespace).createOrReplace(binding);
            client.rbac().roleBindings().inNamespace(namespace).createOrReplace(authorinoBinding);
            LOG.infof("RBAC ensured for namespace %s", namespace);
        } catch (Exception e) {
            LOG.warnf("Could not ensure RBAC in namespace %s: %s", namespace, e.getMessage());
        }
    }

    private void ensureClusterRole(String clusterRoleName, String saName, String saNamespace) {
        List<String> requiredGroups = List.of(
                "", "apps", "networking.k8s.io", "route.openshift.io",
                "kuadrant.io", "gateway.networking.k8s.io",
                "networking.istio.io", "rbac.authorization.k8s.io",
                "devportal.kuadrant.io"
        );
        List<String> requiredVerbs = List.of("get", "list", "create", "update", "patch", "delete");

        try {
            ClusterRole existing = client.rbac().clusterRoles().withName(clusterRoleName).get();

            Set<String> currentGroups = new HashSet<>();
            if (existing != null) {
                for (PolicyRule r : existing.getRules()) {
                    currentGroups.addAll(r.getApiGroups());
                }
            }

            if (existing == null || !currentGroups.containsAll(requiredGroups)) {
                PolicyRule fullRule = new PolicyRuleBuilder()
                        .withApiGroups(requiredGroups.toArray(new String[0]))
                        .withResources("*")
                        .withVerbs(requiredVerbs.toArray(new String[0]))
                        .build();

                ClusterRole desired = new ClusterRoleBuilder()
                        .withNewMetadata().withName(clusterRoleName).endMetadata()
                        .withRules(fullRule)
                        .build();

                client.rbac().clusterRoles().createOrReplace(desired);
                LOG.infof("ClusterRole %s patched with required apiGroups", clusterRoleName);
            }
        } catch (Exception e) {
            LOG.warnf("Could not ensure ClusterRole %s: %s", clusterRoleName, e.getMessage());
        }
    }

    private List<String> splitYamlDocs(String yaml) {
        return Arrays.asList(yaml.split("(?m)^---\\s*$"));
    }

    private String normalizeApiVersion(String yaml) {
        String result = yaml
                .replace("apiVersion: kuadrant.io/v1beta2", "apiVersion: kuadrant.io/v1")
                .replace("apiVersion: kuadrant.io/v1beta1", "apiVersion: kuadrant.io/v1")
                .replace("apiVersion: gateway.networking.k8s.io/v1beta1",
                        "apiVersion: gateway.networking.k8s.io/v1");
        result = moveCredentialsOutOfApiKey(result);
        return result;
    }

    private String moveCredentialsOutOfApiKey(String yaml) {
        Pattern pattern = Pattern.compile(
            "(?m)(^([ \\t]+)apiKey:\\n"
            + "(?:\\2[ \\t]+(?!credentials:).*\\n)*)"
            + "(\\2[ \\t]+credentials:\\n"
            + "(?:\\2[ \\t]{2,}.*\\n)*)"
        );

        Matcher m = pattern.matcher(yaml);
        if (!m.find()) {
            return yaml;
        }

        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) {
            String apiKeyBlock      = m.group(1);
            String apiKeyIndent     = m.group(2);
            String credentialsBlock = m.group(3);

            String fixedCredentials = credentialsBlock.lines()
                .map(line -> {
                    String deeper = apiKeyIndent + "  ";
                    return line.startsWith(deeper) ? apiKeyIndent + line.substring(deeper.length()) : line;
                })
                .collect(Collectors.joining("\n", "", "\n"));

            m.appendReplacement(sb, Matcher.quoteReplacement(apiKeyBlock + fixedCredentials));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private ResourceDefinitionContext buildRdc(String apiVersion, String kind) {
        String group;
        String version;
        if (apiVersion != null && apiVersion.contains("/")) {
            String[] parts = apiVersion.split("/", 2);
            group   = parts[0];
            version = parts[1];
        } else {
            group   = "";
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

    private String toPlural(String kind) {
        if (kind == null) {
            return "unknowns";
        }
        String lower = kind.toLowerCase();
        if (lower.endsWith("policy")) {
            return lower.substring(0, lower.length() - 6) + "policies";
        }
        if (lower.endsWith("status")) {
            return lower + "es";
        }
        if (lower.endsWith("s")) {
            return lower + "es";
        }
        if (lower.length() >= 2) {
            char y = lower.charAt(lower.length() - 1);
            char prev = lower.charAt(lower.length() - 2);
            if (y == 'y' && "bcdfghjklmnpqrstvwxz".indexOf(prev) >= 0) {
                return lower.substring(0, lower.length() - 1) + "ies";
            }
        }
        return lower + "s";
    }
}
