package com.example.migrationtool.controller;

import com.example.migrationtool.util.Messages;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Path("/api/import")
@Tag(name = "Import", description = "Import ZIP packages")
public class ImportController {

    @Inject
    Messages messages;

    @POST
    @Path("/zip")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Upload and extract a ZIP file, returning YAML contents")
    public Response uploadZip(@RestForm("file") FileUpload fileUpload) {
        if (fileUpload == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", messages.get("import.error.noFile")))
                    .build();
        }

        Map<String, String> yamlFiles = new HashMap<>();

        try (InputStream is = java.nio.file.Files.newInputStream(fileUpload.uploadedFile());
             ZipInputStream zis = new ZipInputStream(is)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                    String basename = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                    byte[] content = zis.readAllBytes();
                    yamlFiles.put(basename, new String(content, StandardCharsets.UTF_8));
                }
                zis.closeEntry();
            }

        } catch (IOException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", messages.get("import.error.parseZip", e.getMessage())))
                    .build();
        }

        if (yamlFiles.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", messages.get("import.error.noYaml")))
                    .build();
        }

        return Response.ok(Map.of(
                "files", yamlFiles,
                "count", yamlFiles.size()
        )).build();
    }
}
