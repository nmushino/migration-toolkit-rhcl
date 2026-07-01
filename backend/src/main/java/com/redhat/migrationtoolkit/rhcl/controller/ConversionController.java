package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.dto.ConversionRequest;
import com.redhat.migrationtoolkit.rhcl.entity.ConversionHistoryEntity;
import com.redhat.migrationtoolkit.rhcl.entity.ProjectEntity;
import com.redhat.migrationtoolkit.rhcl.model.ApiService;
import com.redhat.migrationtoolkit.rhcl.model.CompatibilityResult;
import com.redhat.migrationtoolkit.rhcl.service.CompatibilityService;
import com.redhat.migrationtoolkit.rhcl.service.ConversionService;
import com.redhat.migrationtoolkit.rhcl.service.ThreeScaleExportService;
import com.redhat.migrationtoolkit.rhcl.service.ValidationService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/convert")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Conversion", description = "Convert 3scale services to Connectivity Link YAML")
public class ConversionController {

    @Inject
    ThreeScaleExportService exportService;

    @Inject
    CompatibilityService compatibilityService;

    @Inject
    ConversionService conversionService;

    @Inject
    ValidationService validationService;

    @POST
    @Transactional
    @Operation(summary = "Convert selected 3scale services")
    public Response convert(ConversionRequest request) {
        if (request.serviceIds == null || request.serviceIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No service IDs provided"))
                    .build();
        }

        String namespace = request.namespace != null ? request.namespace : "default";

        ProjectEntity project = new ProjectEntity();
        project.name = "Migration-" + System.currentTimeMillis();
        project.threescaleUrl = request.threescaleUrl;
        project.tenant = request.tenant;
        project.persist();

        List<Map<String, Object>> results = new ArrayList<>();

        for (String serviceId : request.serviceIds) {
            try {
                ApiService service = exportService.exportService(
                        request.threescaleUrl, request.accessToken, serviceId);
                CompatibilityResult compatibility = compatibilityService.check(service);
                Map<String, String> yamlFiles = conversionService.convert(
                        service, namespace, request.externalBackendUrl);

                String name = service.systemName != null ? service.systemName : service.name;
                name = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");

                String yamlContent = String.join("\n---\n", yamlFiles.values());
                ConversionHistoryEntity history = new ConversionHistoryEntity();
                history.project = project;
                history.serviceId = serviceId;
                history.serviceName = service.name;
                history.status = "COMPLETED";
                history.compatibilityScore = compatibility.score;
                history.yamlContent = yamlContent;
                history.persist();

                Map<String, Object> result = new HashMap<>();
                result.put("serviceId", serviceId);
                result.put("serviceName", service.name);
                result.put("packageName", name);
                result.put("historyId", history.id);
                result.put("compatibilityScore", compatibility.score);
                result.put("files", new ArrayList<>(yamlFiles.keySet()));
                result.put("yamlFiles", yamlFiles);
                results.add(result);

            } catch (Exception e) {
                ConversionHistoryEntity history = new ConversionHistoryEntity();
                history.project = project;
                history.serviceId = serviceId;
                history.status = "FAILED";
                history.persist();

                results.add(Map.of(
                        "serviceId", serviceId,
                        "status", "FAILED",
                        "error", e.getMessage()
                ));
            }
        }

        return Response.ok(Map.of(
                "projectId", project.id,
                "results", results
        )).build();
    }
}
