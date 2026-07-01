package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.dto.ValidationResult;
import com.redhat.migrationtoolkit.rhcl.service.ValidationService;
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
