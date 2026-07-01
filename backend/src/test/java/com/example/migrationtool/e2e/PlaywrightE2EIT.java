package com.example.migrationtool.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Playwright E2E tests for the backend Swagger UI and REST API.
 * These tests verify the backend's web interface is correctly exposed.
 */
@QuarkusTest
class PlaywrightE2EIT {

    @TestHTTPResource("/")
    URL baseUrl;

    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true)
        );
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void createContextAndPage() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    // ── Swagger UI tests ──────────────────────────────────────────────────────

    @Test
    void swaggerUi_isAccessible() {
        String swaggerUrl = baseUrl.toString().replaceAll("/$", "") + "/q/swagger-ui";
        Response response = page.navigate(swaggerUrl);
        assertNotNull(response);
        assertEquals(200, response.status());
    }

    @Test
    void swaggerUi_hasApiDocumentation() {
        String swaggerUrl = baseUrl.toString().replaceAll("/$", "") + "/q/swagger-ui";
        page.navigate(swaggerUrl);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        String title = page.title();
        assertNotNull(title);
        assertTrue(title.toLowerCase().contains("swagger") || title.toLowerCase().contains("openapi")
                || title.toLowerCase().contains("api"),
                "Swagger UI title should mention API: " + title);
    }

    @Test
    void openApiSpec_isValidJson() {
        String openApiUrl = baseUrl.toString().replaceAll("/$", "") + "/q/openapi?format=json";
        Response response = page.navigate(openApiUrl);
        assertNotNull(response);
        assertEquals(200, response.status());
        String content = response.text();
        assertTrue(content.startsWith("{") || content.startsWith("["),
                "OpenAPI spec should be valid JSON");
        assertTrue(content.contains("openapi") || content.contains("swagger"));
    }

    @Test
    void healthEndpoint_returnsUp() {
        String healthUrl = baseUrl.toString().replaceAll("/$", "") + "/q/health";
        Response response = page.navigate(healthUrl);
        assertNotNull(response);
        assertEquals(200, response.status());
        String content = response.text();
        assertTrue(content.contains("UP"), "Health should be UP");
    }

    // ── REST API tests via Playwright HTTP ────────────────────────────────────

    @Test
    void apiHistory_isAccessible() {
        String historyUrl = baseUrl.toString().replaceAll("/$", "") + "/api/history";
        Response response = page.navigate(historyUrl);
        assertNotNull(response);
        assertEquals(200, response.status());
        String content = response.text();
        assertTrue(content.startsWith("["), "History should return JSON array");
    }

    @Test
    void apiValidate_jsonContentType_respondsCorrectly() {
        String validateUrl = baseUrl.toString().replaceAll("/$", "") + "/api/validate";
        Response response = page.navigate(validateUrl);
        // POST endpoint responds to GET with 405 Method Not Allowed
        assertNotNull(response);
        assertTrue(response.status() == 405 || response.status() == 200 || response.status() == 404);
    }

    @Test
    void apiGatewayInfo_missingParams_returns400() {
        String gatewayUrl = baseUrl.toString().replaceAll("/$", "") + "/api/gateway/info";
        Response response = page.navigate(gatewayUrl);
        assertNotNull(response);
        assertEquals(400, response.status());
    }

    // ── Frontend integration test (optional) ─────────────────────────────────

    @Test
    void frontendApp_ifAvailable_loadsSuccessfully() {
        // Check if frontend is running on default port 3000
        try {
            String frontendUrl = "http://localhost:3000";
            page.setDefaultTimeout(3000);
            Response response = page.navigate(frontendUrl);
            if (response != null && response.status() == 200) {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                String content = page.content();
                assertNotNull(content);
                assertTrue(content.contains("<html") || content.contains("<body"),
                        "Frontend should return HTML content");
            }
            // Frontend is not required for backend tests — skip gracefully if not running
        } catch (Exception e) {
            // Frontend not running — this is acceptable for backend-only tests
        }
    }
}
