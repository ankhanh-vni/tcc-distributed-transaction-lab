package com.tcc.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

@EnabledIf("com.tcc.e2e.TccSystem#dockerAvailable")
class ClientIdempotencyTest {
    private String sku;
    private String customer;
    private String key;

    @BeforeEach
    void fixtures() throws Exception {
        TccSystem.start();
        sku = "SKU-" + UUID.randomUUID();
        customer = "CUST-" + UUID.randomUUID();
        key = "order-" + UUID.randomUUID();
        try (var c = TccSystem.openConnection("inventory_db", "inventory", "inventory");
             var s = c.prepareStatement("insert into product(sku, available_qty, reserved_qty) values (?,100,0)")) {
            s.setString(1, sku); s.executeUpdate();
        }
        try (var c = TccSystem.openConnection("payment_db", "payment", "payment");
             var s = c.prepareStatement("insert into account(customer_id, balance, frozen_amount) values (?,1000,0)")) {
            s.setString(1, customer); s.executeUpdate();
        }
    }

    private String body(String amount) {
        return "{\"customerId\":\"" + customer + "\",\"sku\":\"" + sku + "\",\"qty\":1,\"amount\":" + amount + "}";
    }

    private String post(String amount) {
        return RestAssured.given().baseUri(TccSystem.coordinatorUrl()).contentType(ContentType.JSON)
                .header("Idempotency-Key", key).body(body(amount)).post("/api/orders")
                .then().statusCode(200).extract().jsonPath().getString("txId");
    }

    @Test
    void lostResponseRetryKeepsOneOrderAndOneCharge() throws Exception {
        String first = post("10.00");
        assertThat(post("10")).isEqualTo(first);
        assertSingleEffect(first);
    }

    @Test
    void conflictingPayloadReturns409WithoutAnotherOrder() throws Exception {
        String first = post("10.00");
        RestAssured.given().baseUri(TccSystem.coordinatorUrl()).contentType(ContentType.JSON)
                .header("Idempotency-Key", key).body(body("11.00")).post("/api/orders")
                .then().statusCode(409).body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
        assertSingleEffect(first);
    }

    @Test
    void simultaneousRequestsReserveKeyOnce() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(4);
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return post("10.00");
            })).toList();
            try { assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            finally { go.countDown(); }
            var ids = new java.util.HashSet<String>();
            for (var f : futures) ids.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertSingleEffect(ids.iterator().next());
        }
    }

    private void assertSingleEffect(String txId) throws Exception {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                RestAssured.given().baseUri(TccSystem.coordinatorUrl()).get("/api/transactions/" + txId)
                        .then().statusCode(200).body("state", equalTo("CONFIRMED")));
        try (var c = TccSystem.openConnection("inventory_db", "inventory", "inventory");
             var s = c.prepareStatement("select available_qty, reserved_qty from product where sku=?")) {
            s.setString(1, sku);
            try (var rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(99);
                assertThat(rs.getInt(2)).isZero();
            }
        }
        try (var c = TccSystem.openConnection("payment_db", "payment", "payment");
             var s = c.prepareStatement("select balance, frozen_amount from account where customer_id=?")) {
            s.setString(1, customer);
            try (var rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("990.00");
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("0.00");
            }
        }
        try (var c = TccSystem.openConnection("order_db", "orders", "orders");
             var s = c.prepareStatement("select count(*) from customer_order where customer_id=?")) {
            s.setString(1, customer);
            try (var rs = s.executeQuery()) { rs.next(); assertThat(rs.getLong(1)).isEqualTo(1); }
        }
    }
}
