package com.redhat.migrationtoolkit.rhcl.controller;

import com.redhat.migrationtoolkit.rhcl.entity.ConversionHistoryEntity;
import com.redhat.migrationtoolkit.rhcl.entity.ProjectEntity;
import com.redhat.migrationtoolkit.rhcl.service.PackageService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class PackageControllerTest {

    @InjectMock
    PackageService packageService;

    @Inject
    EntityManager em;

    @AfterEach
    @Transactional
    void cleanUp() {
        em.createQuery("DELETE FROM ConversionHistoryEntity").executeUpdate();
        em.createQuery("DELETE FROM ProjectEntity").executeUpdate();
    }

    @Test
    void downloadZip_validRequest_returns200() {
        byte[] fakeZip = new byte[]{80, 75, 3, 4}; // PK header
        when(packageService.createZip(anyString(), any())).thenReturn(fakeZip);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "yamlFiles": {"gateway.yaml": "apiVersion: gateway.networking.k8s.io/v1"},
                          "packageName": "test-package"
                        }
                        """)
                .when().post("/api/download/zip")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString("test-package.zip"));
    }

    @Test
    void downloadZip_missingYamlFiles_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"packageName": "test-package"}
                        """)
                .when().post("/api/download/zip")
                .then()
                .statusCode(400);
    }

    @Test
    void downloadZip_emptyYamlFiles_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"yamlFiles": {}, "packageName": "test-package"}
                        """)
                .when().post("/api/download/zip")
                .then()
                .statusCode(400);
    }

    @Test
    void downloadZip_defaultPackageName_usedWhenMissing() {
        byte[] fakeZip = new byte[]{80, 75, 3, 4};
        when(packageService.createZip(anyString(), any())).thenReturn(fakeZip);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"yamlFiles": {"file.yaml": "content"}}
                        """)
                .when().post("/api/download/zip")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString("migration-package.zip"));
    }

    @Test
    void downloadFromHistory_notFound_returns404() {
        given()
                .when().get("/api/download/history/99999")
                .then()
                .statusCode(404);
    }

    @Test
    void downloadFromHistory_found_returnsZip() {
        byte[] fakeZip = new byte[]{80, 75, 3, 4};
        when(packageService.createZip(anyString(), any())).thenReturn(fakeZip);

        Long id = createHistory("My Service", "kind: Gateway");

        given()
                .when().get("/api/download/history/" + id)
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString(".zip"));
    }

    @Test
    void downloadFromHistory_nullServiceName_usesDefault() {
        byte[] fakeZip = new byte[]{80, 75, 3, 4};
        when(packageService.createZip(anyString(), any())).thenReturn(fakeZip);

        Long id = createHistory(null, "kind: HTTPRoute");

        given()
                .when().get("/api/download/history/" + id)
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString("service-" + id));
    }

    @Transactional
    Long createHistory(String serviceName, String yamlContent) {
        ProjectEntity project = new ProjectEntity();
        project.name = "Pkg-Test-Project";
        project.threescaleUrl = "https://3scale.example.com";
        project.persist();

        ConversionHistoryEntity h = new ConversionHistoryEntity();
        h.project = project;
        h.serviceId = "svc-pkg";
        h.serviceName = serviceName;
        h.status = "COMPLETED";
        h.yamlContent = yamlContent;
        h.persist();
        return h.id;
    }
}
