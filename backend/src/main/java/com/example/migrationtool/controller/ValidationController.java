package com.example.migrationtool.controller;

import com.example.migrationtool.dto.ValidationResult;
import com.example.migrationtool.service.ValidationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

@Path("/api/validate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Validation", description = "Validate generated YAML files")
public class ValidationController {

    @Inject
    ValidationService validationService;

    @POST
    @Operation(summary = "Validate YAML files")
    public Response validate(Map<String, String> yamlFiles) {
        ValidationResult result = validationService.validate(yamlFiles);
        return Response.ok(result).build();
    }
}
