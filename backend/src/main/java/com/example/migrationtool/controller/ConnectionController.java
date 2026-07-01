package com.example.migrationtool.controller;

import com.example.migrationtool.dto.ConnectionRequest;
import com.example.migrationtool.service.ThreeScaleExportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

@Path("/api/connection")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Connection", description = "3scale connection management")
public class ConnectionController {

    @Inject
    ThreeScaleExportService exportService;

    @POST
    @Path("/test")
    @Operation(summary = "Test connection to 3scale")
    public Response testConnection(ConnectionRequest request) {
        if (request.url == null || request.url.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "URL is required"))
                    .build();
        }
        if (request.accessToken == null || request.accessToken.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "Access token is required"))
                    .build();
        }

        boolean connected = exportService.testConnection(request);
        if (connected) {
            return Response.ok(
                    Map.of("success", true, "message", "Successfully connected to 3scale")).build();
        } else {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("success", false,
                            "message", "Failed to connect to 3scale. Check URL and access token."))
                    .build();
        }
    }
}
