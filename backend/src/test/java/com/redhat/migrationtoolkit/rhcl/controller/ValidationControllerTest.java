package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.dto.ValidationResult;
import com.redhat.migrationtoolkit.rhcl.service.ValidationService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class ValidationControllerTest {

    @InjectMock
    ValidationService validationService;

    @Test
    void validate_validFiles_returns200() {
        ValidationResult result = new ValidationResult();
        result.valid = true;
        result.items = List.of(
                new ValidationResult.ValidationItem("YAML Syntax: test.yaml", "OK", "Valid YAML")
        );
        when(validationService.validate(any())).thenReturn(result);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"gateway.yaml":"apiVersion: gateway.networking.k8s.io/v1\\nkind: Gateway"}
                        """)
                .when().post("/api/validate")
                .then()
                .statusCode(200)
                .body("valid", is(true))
                .body("items", hasSize(1));
    }

    @Test
    void validate_invalidFiles_returns200WithErrors() {
        ValidationResult result = new ValidationResult();
        result.valid = false;
        result.items = List.of(
                new ValidationResult.ValidationItem("YAML Syntax: bad.yaml", "ERROR", "Invalid YAML")
        );
        when(validationService.validate(any())).thenReturn(result);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"bad.yaml":"{{invalid"}
                        """)
                .when().post("/api/validate")
                .then()
                .statusCode(200)
                .body("valid", is(false))
                .body("items[0].status", equalTo("ERROR"));
    }

    @Test
    void validate_emptyBody_returns200() {
        ValidationResult result = new ValidationResult();
        result.valid = true;
        result.items = List.of();
        when(validationService.validate(any())).thenReturn(result);

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/api/validate")
                .then()
                .statusCode(200);
    }

    @Test
    void validate_multipleFiles_allValidated() {
        ValidationResult result = new ValidationResult();
        result.valid = true;
        result.items = List.of(
                new ValidationResult.ValidationItem("YAML Syntax: gateway.yaml", "OK", "Valid"),
                new ValidationResult.ValidationItem("YAML Syntax: httproute.yaml", "OK", "Valid")
        );
        when(validationService.validate(any())).thenReturn(result);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "gateway.yaml": "apiVersion: gateway.networking.k8s.io/v1",
                          "httproute.yaml": "apiVersion: gateway.networking.k8s.io/v1"
                        }
                        """)
                .when().post("/api/validate")
                .then()
                .statusCode(200)
                .body("items", hasSize(2));
    }
}
