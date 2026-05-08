package com.tcc.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;

/**
 * Scenarios 5 and 6: terminal-state guards.
 * 5: Confirm after Cancel → 409.
 * 6: Cancel after Confirm → 409.
 */
@EnabledIf("com.tcc.e2e.TccSystem#dockerAvailable")
class TerminalStateGuardTest {

    @BeforeAll
    static void up() {
        TccSystem.start();
    }

    /** Scenario 5: Confirm after Cancel is rejected. */
    @Test
    void confirmAfterCancel_returns409() {
        UUID txId = UUID.randomUUID();
        // Try
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .contentType(ContentType.JSON)
                .body("{\"txId\":\"" + txId + "\",\"sku\":\"SKU-A\",\"qty\":1}")
                .when().post("/tcc/inventory/reservations")
                .then().statusCode(200);
        // Cancel
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .when().delete("/tcc/inventory/reservations/" + txId)
                .then().statusCode(200);

        // Confirm now must 409
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .when().put("/tcc/inventory/reservations/" + txId + "/confirm")
                .then().statusCode(409)
                .body("code", equalTo("CONFIRM_AFTER_CANCEL"));
    }

    /** Scenario 6: Cancel after Confirm is rejected. */
    @Test
    void cancelAfterConfirm_returns409() {
        UUID txId = UUID.randomUUID();
        // Try
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .contentType(ContentType.JSON)
                .body("{\"txId\":\"" + txId + "\",\"sku\":\"SKU-A\",\"qty\":1}")
                .when().post("/tcc/inventory/reservations")
                .then().statusCode(200);
        // Confirm
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .when().put("/tcc/inventory/reservations/" + txId + "/confirm")
                .then().statusCode(200);

        // Cancel now must 409
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .when().delete("/tcc/inventory/reservations/" + txId)
                .then().statusCode(409)
                .body("code", equalTo("CANCEL_AFTER_CONFIRM"));
    }
}
