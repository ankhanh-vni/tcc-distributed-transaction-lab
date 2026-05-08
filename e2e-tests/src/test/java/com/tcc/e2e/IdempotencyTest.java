package com.tcc.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Scenarios 3 and 4: a confirmed/cancelled participant accepts duplicate Confirm/Cancel
 * calls without applying the side effect twice. We exercise the participant directly
 * (calling its TCC endpoint twice) to isolate the idempotency property.
 */
@EnabledIf("com.tcc.e2e.TccSystem#dockerAvailable")
class IdempotencyTest {

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
            s.execute("update account set balance=1000.00, frozen_amount=0 where customer_id='CUST-1'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Scenario 3: duplicate Confirm. */
    @Test
    void duplicateConfirm_isIdempotent() {
        UUID txId = UUID.randomUUID();
        // Try directly on inventory
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .contentType(ContentType.JSON)
                .body("{\"txId\":\"" + txId + "\",\"sku\":\"SKU-A\",\"qty\":1}")
                .when().post("/tcc/inventory/reservations")
                .then().statusCode(200).body("state", equalTo("TRIED"));

        // First Confirm
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .when().put("/tcc/inventory/reservations/" + txId + "/confirm")
                .then().statusCode(200).body("state", equalTo("CONFIRMED"));

        // Second Confirm — must succeed and not apply the side effect twice
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .when().put("/tcc/inventory/reservations/" + txId + "/confirm")
                .then().statusCode(200).body("state", equalTo("CONFIRMED"));

        // Verify reserved_qty is back to baseline (decremented exactly once)
        try (Connection inv = TccSystem.openConnection("inventory_db", "inventory", "inventory")) {
            int reserved = queryInt(inv, "select reserved_qty from product where sku='SKU-A'");
            assertThat(reserved).isEqualTo(0); // committed once, reserved decremented once
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Scenario 4: duplicate Cancel. */
    @Test
    void duplicateCancel_isIdempotent() {
        UUID txId = UUID.randomUUID();
        // Try
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .contentType(ContentType.JSON)
                .body("{\"txId\":\"" + txId + "\",\"sku\":\"SKU-A\",\"qty\":2}")
                .when().post("/tcc/inventory/reservations")
                .then().statusCode(200);

        int availableAfterTry;
        try (Connection inv = TccSystem.openConnection("inventory_db", "inventory", "inventory")) {
            availableAfterTry = queryInt(inv, "select available_qty from product where sku='SKU-A'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // First Cancel
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .when().delete("/tcc/inventory/reservations/" + txId)
                .then().statusCode(200).body("state", equalTo("CANCELLED"));
        // Second Cancel
        RestAssured.given().baseUri(TccSystem.inventoryUrl())
                .when().delete("/tcc/inventory/reservations/" + txId)
                .then().statusCode(200).body("state", equalTo("CANCELLED"));

        try (Connection inv = TccSystem.openConnection("inventory_db", "inventory", "inventory")) {
            int available = queryInt(inv, "select available_qty from product where sku='SKU-A'");
            assertThat(available).isEqualTo(availableAfterTry + 2); // restored exactly once
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int queryInt(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : Integer.MIN_VALUE;
        }
    }
}
