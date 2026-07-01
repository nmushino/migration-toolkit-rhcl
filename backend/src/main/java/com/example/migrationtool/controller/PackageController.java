package com.example.migrationtool.controller;

import com.example.migrationtool.entity.ConversionHistoryEntity;
import com.example.migrationtool.service.PackageService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

@Path("/api/download")
@Tag(name = "Download", description = "Download generated packages")
public class PackageController {

    @Inject
    PackageService packageService;

    @POST
    @Path("/zip")
    @Produces("application/zip")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create and download a ZIP of YAML files")
    public Response downloadZip(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, String> yamlFiles = (Map<String, String>) request.get("yamlFiles");
        String packageName = (String) request.getOrDefault("packageName", "migration-package");

        if (yamlFiles == null || yamlFiles.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("yamlFiles are required")
                    .build();
        }

        byte[] zipBytes = packageService.createZip(packageName, yamlFiles);

        return Response.ok(zipBytes)
                .header("Content-Disposition", "attachment; filename=\"" + packageName + ".zip\"")
                .header("Content-Type", "application/zip")
                .build();
    }

    @GET
    @Path("/history/{historyId}")
    @Produces("application/zip")
    @Operation(summary = "Download ZIP from history")
    public Response downloadFromHistory(@PathParam("historyId") Long historyId) {
        ConversionHistoryEntity history = ConversionHistoryEntity.findById(historyId);
        if (history == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String packageName = history.serviceName != null
                ? history.serviceName.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                : "service-" + historyId;

        Map<String, String> files = Map.of(
                "all-resources.yaml", history.yamlContent != null ? history.yamlContent : "");
        byte[] zipBytes = packageService.createZip(packageName, files);

        return Response.ok(zipBytes)
                .header("Content-Disposition", "attachment; filename=\"" + packageName + ".zip\"")
                .build();
    }
}
