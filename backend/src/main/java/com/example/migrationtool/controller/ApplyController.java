package com.example.migrationtool.controller;

import com.example.migrationtool.util.Messages;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

        for (Map.Entry<String, String> entry : request.files().entrySet()) {
            String fileName = entry.getKey();
            String yaml = entry.getValue();
            try {
                byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
                List<HasMetadata> items;
                try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
                    items = client.load(bis).items();
                }
                List<String> errors = new ArrayList<>();
                for (HasMetadata item : items) {
                    String kind = item.getKind();
                    String name = item.getMetadata().getName();
                    try {
                        if (item instanceof GenericKubernetesResource gkr) {
                            // 未登録CRD（AuthPolicy など）:
                            // serverSideApply() は内部で Handlers を呼ぶため使えない。
                            // PatchType.SERVER_SIDE_APPLY を直接指定して Handlers をバイパスする。
                            PatchContext ctx = new PatchContext.Builder()
                                    .withPatchType(PatchType.SERVER_SIDE_APPLY)
                                    .withFieldManager("migration-toolkit")
                                    .withForce(true)
                                    .build();
                            client.genericKubernetesResources(gkr.getApiVersion(), gkr.getKind())
                                    .inNamespace(namespace)
                                    .withName(gkr.getMetadata().getName())
                                    .patch(ctx, gkr);
                        } else {
                            client.resource(item).inNamespace(namespace).serverSideApply();
                        }
                        LOG.infof("Applied %s/%s to namespace %s", kind, name, namespace);
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
}
