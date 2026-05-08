package com.tcc.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario 2: payment Try fails (we drive this by setting an absurd amount that
 * exceeds CUST-2's balance, so payment legitimately rejects with 4xx). Coordinator
 * must cancel the (already TRIED) inventory and order participants.
 */
@EnabledIf("com.tcc.e2e.TccSystem#dockerAvailable")
class PaymentTryFailsTest {

    @BeforeAll
    static void up() {
        TccSystem.start();
    }

    @Test
    void paymentTryFails_globalIsCancelled() throws Exception {
        // Reset inventory baseline; CUST-2 starts with only $50 so $200 will exceed it.
        try (Connection inv = TccSystem.openConnection("inventory_db", "inventory", "inventory");
             Statement s = inv.createStatement()) {
            s.execute("update product set available_qty=5, reserved_qty=0 where sku='SKU-B'");
        }
        try (Connection pay = TccSystem.openConnection("payment_db", "payment", "payment");
             Statement s = pay.createStatement()) {
            s.execute("update account set balance=50.00, frozen_amount=0 where customer_id='CUST-2'");
        }

        String txId = RestAssured.given()
                .baseUri(TccSystem.coordinatorUrl())
                .contentType(ContentType.JSON)
                .body("""
                      {"customerId":"CUST-2","sku":"SKU-B","qty":1,"amount":200.00}
                      """)
                .when().post("/api/orders")
                .then().statusCode(200)
                .extract().jsonPath().getString("txId");

        // Verify global state is CANCELLED on the coordinator
        RestAssured.given().baseUri(TccSystem.coordinatorUrl())
                .when().get("/api/transactions/" + txId)
                .then().statusCode(200)
                .body("state", org.hamcrest.Matchers.equalTo("CANCELLED"));

        // Inventory was Tried; should now be CANCELLED with stock released
        try (Connection inv = TccSystem.openConnection("inventory_db", "inventory", "inventory")) {
            assertThat(queryString(inv, "select state from inventory_reservation where tx_id='" + txId + "'"))
                    .isEqualTo("CANCELLED");
            assertThat(queryInt(inv, "select available_qty from product where sku='SKU-B'")).isEqualTo(5);
            assertThat(queryInt(inv, "select reserved_qty from product where sku='SKU-B'")).isEqualTo(0);
        }
        // Payment never reached TRIED, but a preventive tombstone should be present
        try (Connection pay = TccSystem.openConnection("payment_db", "payment", "payment")) {
            assertThat(queryString(pay, "select state from payment_authorization where tx_id='" + txId + "'"))
                    .isEqualTo("CANCELLED");
        }
        // Order: was Tried (it ran before payment in coordinator order), now CANCELLED
        try (Connection ord = TccSystem.openConnection("order_db", "orders", "orders")) {
            assertThat(queryString(ord, "select state from customer_order where id='" + txId + "'"))
                    .isEqualTo("CANCELLED");
        }
    }

    private static String queryString(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int queryInt(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : Integer.MIN_VALUE;
        }
    }
}
