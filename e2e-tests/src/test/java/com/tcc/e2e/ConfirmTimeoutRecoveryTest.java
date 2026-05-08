package com.tcc.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * Scenario 7: simulate a timeout during Confirm. The coordinator forwards
 * X-Sleep-Millis to participants so each Confirm sleeps longer than the
 * coordinator's read timeout. The first drive returns CONFIRMING. The recovery
 * scheduler (or admin endpoint) picks it up and re-drives, this time without
 * the sleep header → all participants confirm → terminal CONFIRMED.
 */
@EnabledIf("com.tcc.e2e.TccSystem#dockerAvailable")
class ConfirmTimeoutRecoveryTest {

    @BeforeAll
    static void up() {
        TccSystem.start();
        try (Connection inv = TccSystem.openConnection("inventory_db", "inventory", "inventory");
             Statement s = inv.createStatement()) {
            s.execute("update product set available_qty=20, reserved_qty=0 where sku='SKU-A'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try (Connection pay = TccSystem.openConnection("payment_db", "payment", "payment");
             Statement s = pay.createStatement()) {
            s.execute("update account set balance=10000.00, frozen_amount=0 where customer_id='CUST-1'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void confirmTimeout_recoveryEventuallyConfirms() {
        // Coordinator's read timeout is 2000ms; sleep 8000ms → first Confirm round times out.
        String txId = RestAssured.given()
                .baseUri(TccSystem.coordinatorUrl())
                .contentType(ContentType.JSON)
                .header("X-Inject-Confirm-Sleep-Millis", "8000")
                .body("""
                      {"customerId":"CUST-1","sku":"SKU-A","qty":1,"amount":50.00}
                      """)
                .when().post("/api/orders")
                .then().statusCode(200)
                .extract().jsonPath().getString("txId");

        // Eventually the recovery loop drives this to CONFIRMED. Allow up to 60s — the
        // sleep on each remaining participant Confirm is 8s, but the recovery drive does
        // NOT inject the sleep, so re-tries should be fast.
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        RestAssured.given().baseUri(TccSystem.coordinatorUrl())
                                .when().get("/api/transactions/" + txId)
                                .then().statusCode(200)
                                .body("state", org.hamcrest.Matchers.equalTo("CONFIRMED")));
    }
}
