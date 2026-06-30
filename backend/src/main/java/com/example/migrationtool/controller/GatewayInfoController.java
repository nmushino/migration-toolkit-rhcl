package com.example.migrationtool.controller;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@Path("/api/gateway")
@Tag(name = "Gateway", description = "Gateway status and connection info")
public class GatewayInfoController {

    private static final Logger LOG = Logger.getLogger(GatewayInfoController.class);

    @Inject
    KubernetesClient client;

    /**
     * Gateway の外部アクセス URL（LoadBalancer hostname / IP）を返す。
     * フロントエンドが適用後にテスト用 curl コマンドを生成するために使用する。
     *
     * GET /api/gateway/info?namespace={ns}&name={gatewayName}
     */
    @GET
    @Path("/info")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get Gateway external URL from cluster")
    public Response getGatewayInfo(
            @QueryParam("namespace") String namespace,
            @QueryParam("name") String gatewayName) {

        if (namespace == null || namespace.isBlank() || gatewayName == null || gatewayName.isBlank()) {
            return Response.status(400).entity(Map.of("error", "namespace and name are required")).build();
        }

        try {
            ResourceDefinitionContext rdc = new ResourceDefinitionContext.Builder()
                    .withGroup("gateway.networking.k8s.io")
                    .withVersion("v1")
                    .withKind("Gateway")
                    .withPlural("gateways")
                    .withNamespaced(true)
                    .build();

            GenericKubernetesResource gw = client.genericKubernetesResources(rdc)
                    .inNamespace(namespace)
                    .withName(gatewayName)
                    .get();

            if (gw == null) {
                return Response.status(404).entity(Map.of(
                        "error", "Gateway not found: " + gatewayName,
                        "ready", false
                )).build();
            }

            String hostname = extractHostname(gw);
            boolean ready = hostname != null && !hostname.isBlank();

            return Response.ok(Map.of(
                    "hostname", hostname != null ? hostname : "",
                    "httpUrl",  ready ? "http://"  + hostname : "",
                    "httpsUrl", ready ? "https://" + hostname : "",
                    "ready", ready
            )).build();

        } catch (Exception e) {
            LOG.warnf("Failed to get Gateway info for %s/%s: %s", namespace, gatewayName, e.getMessage());
            return Response.status(500).entity(Map.of(
                    "error", e.getMessage(),
                    "ready", false
            )).build();
        }
    }

    /** Gateway status.addresses[0].value を取得する。 */
    @SuppressWarnings("unchecked")
    private String extractHostname(GenericKubernetesResource gw) {
        try {
            Map<String, Object> status = (Map<String, Object>) gw.getAdditionalProperties().get("status");
            if (status == null) return null;
            List<Map<String, Object>> addresses = (List<Map<String, Object>>) status.get("addresses");
            if (addresses == null || addresses.isEmpty()) return null;
            Object value = addresses.get(0).get("value");
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
