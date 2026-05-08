package com.tcc.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Scenario 8: simulate a coordinator crash during the TRYING phase by directly seeding
 * the coordinator log via SQL — exactly the persistent state a real coordinator would
 * have left if it had died right after the seed-transaction step but before any RPCs.
 * Then trigger a recovery scan and verify the system reaches a terminal state.
 */
@EnabledIf("com.tcc.e2e.TccSystem#dockerAvailable")
class CoordinatorCrashRecoveryTest {

    @BeforeAll
    static void up() {
        TccSystem.start();
    }

    @Test
    void seededTryingTransaction_recoveryDrivesToTerminal() throws Exception {
        UUID txId = UUID.randomUUID();
        // Seed: a global transaction in TRYING with all participants PENDING — i.e., the
        // coordinator persisted the seed and crashed before doing any RPC. Backdate
        // updated_at so the recovery threshold (8s) fires on this tx.
        try (Connection c = TccSystem.openConnection("coordinator_db", "coordinator", "coordinator");
             Statement s = c.createStatement()) {
            s.execute(
                    "INSERT INTO global_transaction (tx_id, business_key, state, created_at, updated_at) " +
                    "VALUES ('" + txId + "', 'manual-crash-sim', 'TRYING', now() - interval '30 seconds', now() - interval '30 seconds')");
            s.execute(
                    "INSERT INTO transaction_participant (tx_id, participant, base_url, resource_path, state, payload_json) " +
                    "VALUES ('" + txId + "', 'inventory', 'http://inventory-service:8081', '/tcc/inventory/reservations', 'PENDING', " +
                    "'{\"txId\":\"" + txId + "\",\"sku\":\"SKU-A\",\"qty\":1}')");
            s.execute(
                    "INSERT INTO transaction_participant (tx_id, participant, base_url, resource_path, state, payload_json) " +
                    "VALUES ('" + txId + "', 'payment', 'http://payment-service:8082', '/tcc/payment/authorizations', 'PENDING', " +
                    "'{\"txId\":\"" + txId + "\",\"customerId\":\"CUST-1\",\"amount\":1.00}')");
            s.execute(
                    "INSERT INTO transaction_participant (tx_id, participant, base_url, resource_path, state, payload_json) " +
                    "VALUES ('" + txId + "', 'order', 'http://order-service:8083', '/tcc/orders', 'PENDING', " +
                    "'{\"txId\":\"" + txId + "\",\"customerId\":\"CUST-1\",\"sku\":\"SKU-A\",\"qty\":1,\"amount\":1.00}')");
        }

        // Trigger recovery scan
        RestAssured.given().baseUri(TccSystem.coordinatorUrl())
                .when().post("/api/admin/recovery/run")
                .then().statusCode(200);

        // Eventually CONFIRMED (recovery resumes Try; all participants succeed for valid fixtures).
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                RestAssured.given().baseUri(TccSystem.coordinatorUrl())
                        .when().get("/api/transactions/" + txId)
                        .then().statusCode(200)
                        .body("state", equalTo("CONFIRMED")));
    }
}
