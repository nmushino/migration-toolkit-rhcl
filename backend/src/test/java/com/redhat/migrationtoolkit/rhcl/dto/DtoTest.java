package com.redhat.migrationtoolkit.rhcl.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DtoTest {

    // ── ConnectionRequest ────────────────────────────────────────────────────

    @Test
    void connectionRequest_fieldAssignment() {
        ConnectionRequest req = new ConnectionRequest();
        req.url = "https://3scale.example.com";
        req.accessToken = "my-token-123";
        req.tenant = "acme";

        assertEquals("https://3scale.example.com", req.url);
        assertEquals("my-token-123", req.accessToken);
        assertEquals("acme", req.tenant);
    }

    @Test
    void connectionRequest_defaultsNull() {
        ConnectionRequest req = new ConnectionRequest();
        assertNull(req.url);
        assertNull(req.accessToken);
        assertNull(req.tenant);
    }

    // ── ConversionRequest ────────────────────────────────────────────────────

    @Test
    void conversionRequest_fieldAssignment() {
        ConversionRequest req = new ConversionRequest();
        req.threescaleUrl = "https://3scale.example.com";
        req.accessToken = "token-abc";
        req.tenant = "acme";
        req.namespace = "my-namespace";
        req.serviceIds = List.of("101", "202");
        req.externalBackendUrl = "https://api.external.example.com";

        assertEquals("https://3scale.example.com", req.threescaleUrl);
        assertEquals("token-abc", req.accessToken);
        assertEquals("acme", req.tenant);
        assertEquals("my-namespace", req.namespace);
        assertEquals(List.of("101", "202"), req.serviceIds);
        assertEquals("https://api.external.example.com", req.externalBackendUrl);
    }

    @Test
    void conversionRequest_defaultsNull() {
        ConversionRequest req = new ConversionRequest();
        assertNull(req.threescaleUrl);
        assertNull(req.serviceIds);
        assertNull(req.externalBackendUrl);
    }

    @Test
    void conversionRequest_multipleServiceIds() {
        ConversionRequest req = new ConversionRequest();
        req.serviceIds = List.of("1", "2", "3", "4");
        assertEquals(4, req.serviceIds.size());
        assertEquals("3", req.serviceIds.get(2));
    }

    // ── ValidationResult ─────────────────────────────────────────────────────

    @Test
    void validationResult_valid() {
        ValidationResult result = new ValidationResult();
        result.valid = true;
        result.items = List.of(
                new ValidationResult.ValidationItem("YAML Syntax: test.yaml", "OK", "Valid YAML"),
                new ValidationResult.ValidationItem("CRD: kuadrant.io/v1", "OK", "Known CRD")
        );

        assertTrue(result.valid);
        assertEquals(2, result.items.size());
    }

    @Test
    void validationResult_invalid() {
        ValidationResult result = new ValidationResult();
        result.valid = false;
        result.items = List.of(
                new ValidationResult.ValidationItem("YAML Syntax: bad.yaml", "ERROR", "Invalid YAML: tab")
        );

        assertFalse(result.valid);
        assertEquals("ERROR", result.items.get(0).status);
    }

    @Test
    void validationItem_allStatuses() {
        ValidationResult.ValidationItem ok = new ValidationResult.ValidationItem("a", "OK", "fine");
        ValidationResult.ValidationItem warn = new ValidationResult.ValidationItem("b", "WARNING", "note");
        ValidationResult.ValidationItem err = new ValidationResult.ValidationItem("c", "ERROR", "bad");

        assertEquals("OK", ok.status);
        assertEquals("fine", ok.message);
        assertEquals("WARNING", warn.status);
        assertEquals("ERROR", err.status);
        assertEquals("bad", err.message);
    }

    @Test
    void validationItem_checkField() {
        ValidationResult.ValidationItem item = new ValidationResult.ValidationItem(
                "CRD: gateway.networking.k8s.io/v1", "OK", "Known CRD group");
        assertEquals("CRD: gateway.networking.k8s.io/v1", item.check);
    }
}
