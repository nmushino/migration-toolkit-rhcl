package com.example.migrationtool.controller;

import com.example.migrationtool.entity.ConversionHistoryEntity;
import com.example.migrationtool.entity.ProjectEntity;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/history")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "History", description = "Conversion history management")
public class HistoryController {

    @GET
    @Operation(summary = "Get conversion history")
    public Response getHistory(@QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("size") @DefaultValue("20") int size) {
        List<ConversionHistoryEntity> history = ConversionHistoryEntity
                .find("ORDER BY createdAt DESC")
                .page(page, size)
                .list();
        return Response.ok(history).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a specific conversion history entry")
    public Response getHistoryById(@PathParam("id") Long id) {
        ConversionHistoryEntity history = ConversionHistoryEntity.findById(id);
        if (history == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(history).build();
    }

    @GET
    @Path("/projects")
    @Operation(summary = "Get all projects")
    public Response getProjects() {
        List<ProjectEntity> projects = ProjectEntity.listAll();
        return Response.ok(projects).build();
    }
}
