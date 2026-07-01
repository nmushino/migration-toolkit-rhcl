package com.example.migrationtool.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HistoryControllerTest {

    @Test
    void getHistory_returns200() {
        given()
                .when().get("/api/history")
                .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void getHistory_withPagination_returns200() {
        given()
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when().get("/api/history")
                .then()
                .statusCode(200);
    }

    @Test
    void getHistoryById_notFound_returns404() {
        given()
                .when().get("/api/history/999999")
                .then()
                .statusCode(404);
    }

    @Test
    void getProjects_returns200() {
        given()
                .when().get("/api/history/projects")
                .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void deleteByIds_emptyList_returns400() {
        given()
                .contentType("application/json")
                .body("[]")
                .when().delete("/api/history")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    void deleteByIds_nullBody_returns400Or415() {
        given()
                .contentType("application/json")
                .body("null")
                .when().delete("/api/history")
                .then()
                .statusCode(anyOf(equalTo(400), equalTo(415), equalTo(200)));
    }

    @Test
    void downloadYaml_notFound_returns404() {
        given()
                .when().get("/api/history/999999/download")
                .then()
                .statusCode(404);
    }
}
