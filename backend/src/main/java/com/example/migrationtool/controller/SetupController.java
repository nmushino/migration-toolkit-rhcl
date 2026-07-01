package com.example.migrationtool.controller;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Path("/api/setup")
@Tag(name = "Setup", description = "Namespace label and Gateway annotation for Kuadrant")
public class SetupController {

    private static final Logger LOG = Logger.getLogger(SetupController.class);

    @Inject
    KubernetesClient client;

    public record SetupRequest(String namespace) {}
    public record StepResult(String step, boolean success, String message) {}
    public record SetupResponse(List<StepResult> steps, boolean allSuccess) {}

    /**
     * 対象 namespace への istio-injection ラベル付けと
     * Gateway への kuadrant.io/namespace アノテーション付与を一括実行する。
     * Istio / Kuadrant 本体のインストールはインストールシェルで行う。
     */
    @POST
    @Path("/namespace")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Apply istio-injection label to namespace and kuadrant annotation to Gateway(s)")
    public Response applyNamespaceSetup(SetupRequest request) {
        String namespace = (request != null && request.namespace() != null && !request.namespace().isBlank())
                ? request.namespace() : "default";

        List<StepResult> steps = new ArrayList<>();

        // Step 1: namespace に istio-injection=enabled ラベルを付与
        steps.add(labelNamespace(namespace));

        // Step 2: namespace 内の全 Gateway に kuadrant アノテーションを付与
        steps.add(annotateGateways(namespace));

        boolean allSuccess = steps.stream().allMatch(StepResult::success);
        int status = allSuccess ? 200 : 207;
        return Response.status(status).entity(new SetupResponse(steps, allSuccess)).build();
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Check namespace label and Gateway annotation status")
    public Response checkStatus(@QueryParam("namespace") @DefaultValue("default") String namespace) {
        List<StepResult> steps = new ArrayList<>();
        steps.add(checkNamespaceLabel(namespace));
        steps.add(checkGatewayAnnotation(namespace));
        boolean allSuccess = steps.stream().allMatch(StepResult::success);
        return Response.ok(new SetupResponse(steps, allSuccess)).build();
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private StepResult labelNamespace(String namespace) {
        String stepName = "Namespace istio-injection label (" + namespace + ")";
        try {
            client.namespaces().withName(namespace).edit(ns -> {
                if (ns.getMetadata().getLabels() == null) {
                    ns.getMetadata().setLabels(new HashMap<>());
                }
                ns.getMetadata().getLabels().put("istio-injection", "enabled");
                return ns;
            });
            LOG.infof("[Setup] Labeled namespace %s with istio-injection=enabled", namespace);
            return new StepResult(stepName, true, "Label applied");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            LOG.warnf("[Setup] Failed to label namespace %s: %s", namespace, msg);
            return new StepResult(stepName, false, msg);
        }
    }

    private StepResult annotateGateways(String namespace) {
        String stepName = "Gateway kuadrant.io/namespace annotation (" + namespace + ")";
        try {
            ResourceDefinitionContext rdc = gatewayRdc();
            PatchContext ctx = new PatchContext.Builder()
                    .withPatchType(PatchType.SERVER_SIDE_APPLY)
                    .withFieldManager("migration-toolkit")
                    .withForce(true)
                    .build();

            var list = client.genericKubernetesResources(rdc).inNamespace(namespace).list();
            if (list.getItems().isEmpty()) {
                return new StepResult(stepName, false, "No Gateway found in namespace " + namespace);
            }

            int count = 0;
            for (GenericKubernetesResource gw : list.getItems()) {
                if (gw.getMetadata().getAnnotations() == null) {
                    gw.getMetadata().setAnnotations(new HashMap<>());
                }
                gw.getMetadata().getAnnotations().put("kuadrant.io/namespace", "kuadrant-system");
                gw.getMetadata().getAnnotations().put("networking.istio.io/service-type", "ClusterIP");
                client.genericKubernetesResources(rdc)
                        .inNamespace(namespace)
                        .withName(gw.getMetadata().getName())
                        .patch(ctx, gw);
                count++;
            }
            LOG.infof("[Setup] Annotated %d Gateway(s) in %s", count, namespace);
            return new StepResult(stepName, true, count + " Gateway(s) annotated");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            LOG.warnf("[Setup] Failed to annotate gateways in %s: %s", namespace, msg);
            return new StepResult(stepName, false, msg);
        }
    }

    private StepResult checkNamespaceLabel(String namespace) {
        String stepName = "Namespace istio-injection label (" + namespace + ")";
        try {
            var ns = client.namespaces().withName(namespace).get();
            boolean labeled = ns != null
                    && ns.getMetadata().getLabels() != null
                    && "enabled".equals(ns.getMetadata().getLabels().get("istio-injection"));
            return new StepResult(stepName, labeled, labeled ? "Label present" : "Label missing");
        } catch (Exception e) {
            return new StepResult(stepName, false, e.getMessage());
        }
    }

    private StepResult checkGatewayAnnotation(String namespace) {
        String stepName = "Gateway kuadrant.io/namespace annotation (" + namespace + ")";
        try {
            var list = client.genericKubernetesResources(gatewayRdc()).inNamespace(namespace).list();
            if (list.getItems().isEmpty()) {
                return new StepResult(stepName, false, "No Gateway found in namespace " + namespace);
            }
            boolean annotated = list.getItems().stream().anyMatch(gw ->
                    gw.getMetadata().getAnnotations() != null
                    && gw.getMetadata().getAnnotations().containsKey("kuadrant.io/namespace"));
            return new StepResult(stepName, annotated, annotated ? "Annotation present" : "Annotation missing");
        } catch (Exception e) {
            return new StepResult(stepName, false, e.getMessage());
        }
    }

    private ResourceDefinitionContext gatewayRdc() {
        return new ResourceDefinitionContext.Builder()
                .withGroup("gateway.networking.k8s.io")
                .withVersion("v1")
                .withKind("Gateway")
                .withPlural("gateways")
                .withNamespaced(true)
                .build();
    }
}
