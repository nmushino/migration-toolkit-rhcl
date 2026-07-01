package com.example.migrationtool.service;

import com.example.migrationtool.dto.ConnectionRequest;
import com.example.migrationtool.model.ApiService;
import com.example.migrationtool.model.Authentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThreeScaleExportServiceTest {

    private ThreeScaleExportService service;

    @BeforeEach
    void setUp() {
        service = new ThreeScaleExportService();
    }

    // ── testConnection() ──────────────────────────────────────────────────────

    @Test
    void testConnection_invalidUrl_returnsFalse() {
        ConnectionRequest req = new ConnectionRequest();
        req.url = "not-a-url";
        req.accessToken = "token";
        boolean result = service.testConnection(req);
        assertFalse(result, "Invalid URL should return false");
    }

    @Test
    void testConnection_unreachableHost_returnsFalse() {
        ConnectionRequest req = new ConnectionRequest();
        req.url = "https://nonexistent-3scale-host.example.invalid";
        req.accessToken = "token";
        boolean result = service.testConnection(req);
        assertFalse(result, "Unreachable host should return false");
    }

    @Test
    void testConnection_nullUrl_returnsFalse() {
        ConnectionRequest req = new ConnectionRequest();
        req.url = null;
        req.accessToken = "token";
        assertFalse(service.testConnection(req));
    }

    // ── buildClient() ─────────────────────────────────────────────────────────

    @Test
    void buildClient_invalidUri_throwsRuntimeException() throws Exception {
        Method buildClient = ThreeScaleExportService.class
                .getDeclaredMethod("buildClient", String.class);
        buildClient.setAccessible(true);
        Exception ex = assertThrows(Exception.class,
                () -> buildClient.invoke(service, "::invalid::uri::"));
        assertNotNull(ex.getCause() != null ? ex.getCause() : ex);
    }

    // ── extractAuthentication() ───────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "1, apiKey",
        "2, appIdKey",
        "oidc, jwt",
        "3, none",
        ", none"
    })
    void extractAuthentication_backendVersion_mapsCorrectly(String backendVersion, String expectedType)
            throws Exception {
        Method extractAuth = ThreeScaleExportService.class
                .getDeclaredMethod("extractAuthentication", Map.class);
        extractAuth.setAccessible(true);

        Map<String, Object> svc = new HashMap<>();
        if (backendVersion != null) {
            svc.put("backend_version", backendVersion);
        }
        Authentication auth = (Authentication) extractAuth.invoke(service, svc);
        assertEquals(expectedType, auth.type);
    }

    // ── extractList() ─────────────────────────────────────────────────────────

    @Test
    void extractList_withValidKey_returnsList() throws Exception {
        Method extractList = ThreeScaleExportService.class
                .getDeclaredMethod("extractList", Map.class, String.class);
        extractList.setAccessible(true);

        List<Map<String, Object>> data = List.of(Map.of("service", Map.of("id", "1")));
        Map<String, Object> response = Map.of("services", data);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) extractList.invoke(service, response, "services");
        assertEquals(1, result.size());
    }

    @Test
    void extractList_missingKey_returnsEmpty() throws Exception {
        Method extractList = ThreeScaleExportService.class
                .getDeclaredMethod("extractList", Map.class, String.class);
        extractList.setAccessible(true);

        Map<String, Object> response = Map.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) extractList.invoke(service, response, "services");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractList_nonListValue_returnsEmpty() throws Exception {
        Method extractList = ThreeScaleExportService.class
                .getDeclaredMethod("extractList", Map.class, String.class);
        extractList.setAccessible(true);

        Map<String, Object> response = Map.of("services", "not-a-list");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) extractList.invoke(service, response, "services");
        assertTrue(result.isEmpty());
    }

    // ── exportService() error handling ────────────────────────────────────────

    @Test
    void exportService_invalidUrl_throwsRuntimeException() {
        assertThrows(RuntimeException.class,
                () -> service.exportService("invalid-url", "token", "svc-1"));
    }

    // ── exportServices() error handling ──────────────────────────────────────

    @Test
    void exportServices_invalidUrl_throwsRuntimeException() {
        assertThrows(RuntimeException.class,
                () -> service.exportServices("invalid-url", "token"));
    }
}
