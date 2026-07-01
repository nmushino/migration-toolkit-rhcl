package com.redhat.migrationtoolkit.rhcl.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ImportControllerTest {

    // ── helper ───────────────────────────────────────────────────────────────

    private Path createZipFile(String... entries) throws IOException {
        Path tmp = Files.createTempFile("test-import-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (int i = 0; i < entries.length; i += 2) {
                String name = entries[i];
                String content = entries[i + 1];
                zos.putNextEntry(new ZipEntry(name));
                zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    // ── /api/import/zip ───────────────────────────────────────────────────────

    @Test
    void uploadZip_noFile_returns400() {
        given()
                .contentType("multipart/form-data")
                .when().post("/api/import/zip")
                .then()
                .statusCode(400);
    }

    @Test
    void uploadZip_validZip_returnsYamlFiles() throws IOException {
        Path zip = createZipFile(
                "gateway.yaml", "apiVersion: gateway.networking.k8s.io/v1\nkind: Gateway",
                "httproute.yaml", "apiVersion: gateway.networking.k8s.io/v1\nkind: HTTPRoute"
        );

        given()
                .contentType("multipart/form-data")
                .multiPart("file", zip.toFile(), "application/zip")
                .when().post("/api/import/zip")
                .then()
                .statusCode(200)
                .body("count", equalTo(2))
                .body("files.keySet()", hasItems("gateway.yaml", "httproute.yaml"));
    }

    @Test
    void uploadZip_nestedYamlFiles_extractedWithBasename() throws IOException {
        Path zip = createZipFile(
                "pkg/subdir/policy.yaml", "apiVersion: kuadrant.io/v1\nkind: AuthPolicy"
        );

        given()
                .contentType("multipart/form-data")
                .multiPart("file", zip.toFile(), "application/zip")
                .when().post("/api/import/zip")
                .then()
                .statusCode(200)
                .body("files.keySet()", hasItem("policy.yaml"));
    }

    @Test
    void uploadZip_zipWithNoYaml_returns400() throws IOException {
        Path zip = createZipFile(
                "README.md", "# Migration",
                "config.json", "{\"key\":\"value\"}"
        );

        given()
                .contentType("multipart/form-data")
                .multiPart("file", zip.toFile(), "application/zip")
                .when().post("/api/import/zip")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void uploadZip_ymlExtension_accepted() throws IOException {
        Path zip = createZipFile(
                "gateway.yml", "apiVersion: gateway.networking.k8s.io/v1\nkind: Gateway"
        );

        given()
                .contentType("multipart/form-data")
                .multiPart("file", zip.toFile(), "application/zip")
                .when().post("/api/import/zip")
                .then()
                .statusCode(200)
                .body("files.keySet()", hasItem("gateway.yml"));
    }

    @Test
    void uploadZip_invalidZipBytes_returns400() throws IOException {
        Path tmp = Files.createTempFile("bad-", ".zip");
        Files.write(tmp, "not a zip file content".getBytes(StandardCharsets.UTF_8));
        tmp.toFile().deleteOnExit();

        given()
                .contentType("multipart/form-data")
                .multiPart("file", tmp.toFile(), "application/zip")
                .when().post("/api/import/zip")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void uploadZip_directoryEntries_skipped() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // directory entry
            zos.putNextEntry(new ZipEntry("subdir/"));
            zos.closeEntry();
            // yaml file
            zos.putNextEntry(new ZipEntry("subdir/route.yaml"));
            zos.write("kind: HTTPRoute".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path tmp = Files.createTempFile("dir-", ".zip");
        Files.write(tmp, baos.toByteArray());
        tmp.toFile().deleteOnExit();

        given()
                .contentType("multipart/form-data")
                .multiPart("file", tmp.toFile(), "application/zip")
                .when().post("/api/import/zip")
                .then()
                .statusCode(200)
                .body("count", equalTo(1));
    }
}
