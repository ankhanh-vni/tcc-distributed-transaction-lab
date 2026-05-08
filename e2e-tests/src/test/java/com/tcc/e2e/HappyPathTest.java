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
import static org.hamcrest.Matchers.equalTo;

@EnabledIf("com.tcc.e2e.TccSystem#dockerAvailable")
class HappyPathTest {

    @BeforeAll
    static void up() {
        TccSystem.start();
    }

    /** Scenario 1: all three participants Try succeed → coordinator confirms everyone. */
    @Test
    void placeOrder_allSucceed_globalIsConfirmed() throws Exception {
        seedFixtures();

        String txId = RestAssured.given()
                .baseUri(TccSystem.coordinatorUrl())
                .contentType(ContentType.JSON)
                .body("""
                      {"customerId":"CUST-1","sku":"SKU-A","qty":1,"amount":100.00}
                      """)
                .when().post("/api/orders")
                .then().statusCode(200)
                .body("state", equalTo("CONFIRMED"))
                .extract().jsonPath().getString("txId");

        // Sanity: status endpoint reports same state
        RestAssured.given().baseUri(TccSystem.coordinatorUrl())
                .when().get("/api/transactions/" + txId)
                .then().statusCode(200).body("state", equalTo("CONFIRMED"));

        // Verify each participant's DB reflects the commit
        try (Connection inv = TccSystem.openConnection("inventory_db", "inventory", "inventory");
             Connection pay = TccSystem.openConnection("payment_db", "payment", "payment");
             Connection ord = TccSystem.openConnection("order_db", "orders", "orders")) {

            assertThat(queryString(inv, "select state from inventory_reservation where tx_id='" + txId + "'"))
                    .isEqualTo("CONFIRMED");
            assertThat(queryString(pay, "select state from payment_authorization where tx_id='" + txId + "'"))
                    .isEqualTo("CONFIRMED");
            assertThat(queryString(ord, "select state from customer_order where id='" + txId + "'"))
                    .isEqualTo("CONFIRMED");
        }
    }

    private static void seedFixtures() throws Exception {
        // Re-establish a baseline so repeated runs are idempotent.
        try (Connection inv = TccSystem.openConnection("inventory_db", "inventory", "inventory");
             Statement s = inv.createStatement()) {
            s.execute("update product set available_qty=10, reserved_qty=0 where sku='SKU-A'");
        }
        try (Connection pay = TccSystem.openConnection("payment_db", "payment", "payment");
             Statement s = pay.createStatement()) {
            s.execute("update account set balance=1000.00, frozen_amount=0 where customer_id='CUST-1'");
        }
    }

    private static String queryString(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
