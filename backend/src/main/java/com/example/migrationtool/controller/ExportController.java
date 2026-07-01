package com.example.migrationtool.controller;

import com.example.migrationtool.model.ApiService;
import com.example.migrationtool.model.CompatibilityResult;
import com.example.migrationtool.service.CompatibilityService;
import com.example.migrationtool.service.ThreeScaleExportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/services")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Services", description = "3scale service export")
public class ExportController {

    @Inject
    ThreeScaleExportService exportService;

    @Inject
    CompatibilityService compatibilityService;

    @GET
    @Operation(summary = "Get all services from 3scale")
    public Response getServices(@QueryParam("url") String url,
                                 @QueryParam("accessToken") String accessToken) {
        if (url == null || accessToken == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("url and accessToken query parameters are required")
                    .build();
        }
        List<ApiService> services = exportService.exportServices(url, accessToken);
        return Response.ok(services).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a specific service from 3scale")
    public Response getService(@PathParam("id") String id,
                                @QueryParam("url") String url,
                                @QueryParam("accessToken") String accessToken) {
        ApiService service = exportService.exportService(url, accessToken, id);
        return Response.ok(service).build();
    }

    @GET
    @Path("/{id}/compatibility")
    @Operation(summary = "Check compatibility of a service")
    public Response checkCompatibility(@PathParam("id") String id,
                                        @QueryParam("url") String url,
                                        @QueryParam("accessToken") String accessToken) {
        ApiService service = exportService.exportService(url, accessToken, id);
        CompatibilityResult result = compatibilityService.check(service);
        return Response.ok(result).build();
    }
}
