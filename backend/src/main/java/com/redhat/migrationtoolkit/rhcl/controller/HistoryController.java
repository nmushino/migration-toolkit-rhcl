package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.entity.ConversionHistoryEntity;
import com.redhat.migrationtoolkit.rhcl.entity.ProjectEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Path("/api/history")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "History", description = "Conversion history management")
public class HistoryController {

    private static final Logger LOG = Logger.getLogger(HistoryController.class);

    @Inject
    ObjectMapper objectMapper;

    @GET
    @Operation(summary = "Get conversion history (exportedYaml を除いた軽量レスポンス)")
    public Response getHistory(@QueryParam("page") @DefaultValue("0") int page,
                               @QueryParam("size") @DefaultValue("50") int size) {
        List<ConversionHistoryEntity> history = ConversionHistoryEntity
                .find("ORDER BY createdAt DESC")
                .page(page, size)
                .list();
        // exportedYaml はサイズが大きいためリスト API では除外する
        List<Map<String, Object>> result = history.stream().map(h -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id",               h.id);
            m.put("source",           h.source);
            m.put("namespace",        h.namespace);
            m.put("status",           h.status);
            m.put("totalCount",       h.totalCount);
            m.put("successCount",     h.successCount);
            m.put("failureCount",     h.failureCount);
            m.put("failureDetails",   h.failureDetails);
            m.put("serviceName",      h.serviceName);
            m.put("serviceId",        h.serviceId);
            m.put("compatibilityScore", h.compatibilityScore);
            m.put("createdAt",        h.createdAt);
            return m;
        }).collect(java.util.stream.Collectors.toList());
        return Response.ok(result).build();
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
        return Response.ok(ProjectEntity.listAll()).build();
    }

    /** 履歴の export YAML を ZIP でダウンロード */
    @GET
    @Path("/{id}/download")
    @Produces("application/zip")
    @Operation(summary = "Download exported YAML as ZIP")
    public Response downloadYaml(@PathParam("id") Long id) {
        ConversionHistoryEntity entity = ConversionHistoryEntity.findById(id);
        if (entity == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            // exportedYaml は JSON: {filename → yamlContent}
            Map<String, String> files = entity.exportedYaml != null
                    ? objectMapper.readValue(entity.exportedYaml,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class))
                    : Map.of();

            if (files.isEmpty() && entity.yamlContent != null) {
                files = Map.of("export.yaml", entity.yamlContent);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (Map.Entry<String, String> e : files.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }

            byte[] zipBytes = baos.toByteArray();
            String filename = "history-" + id + ".zip";
            return Response.ok((StreamingOutput) out -> out.write(zipBytes))
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Length", zipBytes.length)
                    .build();
        } catch (Exception e) {
            LOG.warnf("Download failed for id=%d: %s", id, e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** 複数 ID をまとめて削除 */
    @DELETE
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(summary = "Delete history entries by IDs")
    public Response deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No IDs provided")).build();
        }
        long deleted = ConversionHistoryEntity.delete("id IN ?1", ids);
        LOG.infof("Deleted %d history entries: %s", deleted, ids);
        return Response.ok(Map.of("deleted", deleted)).build();
    }
}
