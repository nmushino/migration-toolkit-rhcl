package com.redhat.migrationtoolkit.rhcl.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackageServiceTest {

    private PackageService service;

    @BeforeEach
    void setUp() {
        service = new PackageService();
    }

    @Test
    void createZip_singleFile_validZip() throws Exception {
        Map<String, String> files = Map.of("gateway.yaml", "apiVersion: gateway.networking.k8s.io/v1\nkind: Gateway\n");
        byte[] zip = service.createZip("my-package", files);

        assertNotNull(zip);
        assertTrue(zip.length > 0);

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = zis.getNextEntry();
            assertNotNull(entry);
            assertEquals("my-package/gateway.yaml", entry.getName());
            String content = new String(zis.readAllBytes());
            assertTrue(content.contains("kind: Gateway"));
        }
    }

    @Test
    void createZip_multipleFiles_allIncluded() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("gateway.yaml", "apiVersion: gateway.networking.k8s.io/v1\nkind: Gateway");
        files.put("httproute.yaml", "apiVersion: gateway.networking.k8s.io/v1\nkind: HTTPRoute");
        files.put("policy.yaml", "apiVersion: kuadrant.io/v1\nkind: AuthPolicy");

        byte[] zip = service.createZip("test-pkg", files);
        assertNotNull(zip);

        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                assertTrue(entry.getName().startsWith("test-pkg/"));
                count++;
                zis.closeEntry();
            }
        }
        assertEquals(3, count);
    }

    @Test
    void createZip_fileNamingIncludesPackageName() throws Exception {
        byte[] zip = service.createZip("migration-package", Map.of("secret.yaml", "content"));
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = zis.getNextEntry();
            assertEquals("migration-package/secret.yaml", entry.getName());
        }
    }

    @Test
    void createZip_contentPreservedExactly() throws Exception {
        String content = "apiVersion: v1\nkind: Secret\nstringData:\n  key: value\n";
        byte[] zip = service.createZip("pkg", Map.of("secret.yaml", content));

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            zis.getNextEntry();
            String extracted = new String(zis.readAllBytes());
            assertEquals(content, extracted);
        }
    }

    @Test
    void createZip_largeContent_succeeds() throws Exception {
        String largeContent = "apiVersion: v1\nkind: ConfigMap\ndata:\n  key: " + "x".repeat(10000);
        byte[] zip = service.createZip("large-pkg", Map.of("configmap.yaml", largeContent));
        assertTrue(zip.length > 0);
    }

    @Test
    void createZip_packageNameWithSpecialChars() throws Exception {
        byte[] zip = service.createZip("my-api-v1.0", Map.of("file.yaml", "content"));
        assertNotNull(zip);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = zis.getNextEntry();
            assertEquals("my-api-v1.0/file.yaml", entry.getName());
        }
    }

    @Test
    void createZip_unicodeContent_preserved() throws Exception {
        String content = "# 日本語テスト\napiVersion: v1\nkind: ConfigMap\n";
        byte[] zip = service.createZip("pkg", Map.of("config.yaml", content));
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            zis.getNextEntry();
            String extracted = new String(zis.readAllBytes());
            assertTrue(extracted.contains("日本語テスト"));
        }
    }
}
