package com.tcc.coordinator.service;

import com.tcc.coordinator.api.dto.PlaceOrderRequest;
import com.tcc.coordinator.client.ParticipantCallException;
import com.tcc.coordinator.client.ParticipantClient;
import com.tcc.coordinator.domain.GlobalTransaction;
import com.tcc.coordinator.domain.GlobalTxState;
import com.tcc.coordinator.domain.ParticipantTxState;
import com.tcc.coordinator.domain.TransactionParticipant;
import com.tcc.coordinator.repo.GlobalTransactionRepository;
import com.tcc.coordinator.repo.TransactionParticipantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Coordinator orchestration tests with real Postgres (Testcontainers) but a mocked
 * ParticipantClient — so we can drive every failure scenario from the coordinator's
 * point of view without spinning up real participant services. The full end-to-end
 * tests live in the e2e-tests module.
 */
@Testcontainers
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIf("dockerAvailable")
class CoordinatorServiceTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Disable scheduled recovery so the test scheduler doesn't race with our assertions.
        registry.add("tcc.recovery.enabled", () -> "false");
        // Static participant config for tests.
        registry.add("tcc.participants.inventory.base-url", () -> "http://localhost:0");
        registry.add("tcc.participants.inventory.resource-path", () -> "/tcc/inventory/reservations");
        registry.add("tcc.participants.payment.base-url", () -> "http://localhost:0");
        registry.add("tcc.participants.payment.resource-path", () -> "/tcc/payment/authorizations");
        registry.add("tcc.participants.order.base-url", () -> "http://localhost:0");
        registry.add("tcc.participants.order.resource-path", () -> "/tcc/orders");
    }

    @Autowired CoordinatorService coordinator;
    @Autowired GlobalTransactionRepository globals;
    @Autowired TransactionParticipantRepository participants;
    @MockBean ParticipantClient participantClient;

    private PlaceOrderRequest sampleOrder() {
        return new PlaceOrderRequest("CUST-1", "SKU-A", 1, new BigDecimal("100.00"));
    }

    /** Scenario 1: all participants succeed → CONFIRMED. */
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE transaction_participant, global_transaction CASCADE");
    }

    @Test
    void allParticipantsSucceed_terminatesAsConfirmed() {
        doNothing().when(participantClient).callTry(any(), any());
        doNothing().when(participantClient).callConfirm(any(), any());

        UUID txId = coordinator.placeOrder(sampleOrder(), null);

        var g = globals.findById(txId).orElseThrow();
        assertThat(g.getState()).isEqualTo(GlobalTxState.CONFIRMED);
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        assertThat(ps).hasSize(3);
        assertThat(ps).allMatch(p -> p.getState() == ParticipantTxState.CONFIRMED);
        verify(participantClient, times(3)).callTry(any(), any());
        verify(participantClient, times(3)).callConfirm(any(), any());
    }

    /** Scenario 2: payment Try fails → CANCELLED, Confirm never called. */
    @Test
    void paymentTryFails_terminatesAsCancelled() {
        doNothing().when(participantClient).callTry(any(), any());
        doThrow(new ParticipantCallException("payment", "TRY", 500, "boom", new RuntimeException("boom")))
                .when(participantClient).callTry(argParticipantNamed("payment"), any());
        doNothing().when(participantClient).callCancel(any(), any());

        UUID txId = coordinator.placeOrder(sampleOrder(), null);

        var g = globals.findById(txId).orElseThrow();
        assertThat(g.getState()).isEqualTo(GlobalTxState.CANCELLED);
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        assertThat(ps).extracting(TransactionParticipant::getState)
                .containsExactlyInAnyOrder(ParticipantTxState.CANCELLED, ParticipantTxState.CANCELLED, ParticipantTxState.CANCELLED);
        verify(participantClient, atLeastOnce()).callCancel(any(), any());
        verify(participantClient, times(0)).callConfirm(any(), any());
    }

    /** Scenario 5/6: participant returns 409 during Confirm → HEURISTIC. */
    @Test
    void confirmReturns409_isHeuristic() {
        doNothing().when(participantClient).callTry(any(), any());
        doNothing().when(participantClient).callConfirm(any(), any());
        doThrow(new ParticipantCallException("payment", "CONFIRM", 409, "Confirm after Cancel", new RuntimeException()))
                .when(participantClient).callConfirm(argParticipantNamed("payment"), any());

        UUID txId = coordinator.placeOrder(sampleOrder(), null);

        var g = globals.findById(txId).orElseThrow();
        assertThat(g.getState()).isEqualTo(GlobalTxState.HEURISTIC);
        assertThat(g.getLastError()).contains("Confirm rejected with 409");
    }

    /** Scenario 5/6 mirror: participant returns 409 during Cancel → HEURISTIC. */
    @Test
    void cancelReturns409_isHeuristic() {
        doNothing().when(participantClient).callTry(any(), any());
        doThrow(new ParticipantCallException("payment", "TRY", 500, "boom", new RuntimeException()))
                .when(participantClient).callTry(argParticipantNamed("payment"), any());
        doNothing().when(participantClient).callCancel(any(), any());
        doThrow(new ParticipantCallException("inventory", "CANCEL", 409, "Cancel after Confirm", new RuntimeException()))
                .when(participantClient).callCancel(any(), argParticipantNamed("inventory"));

        UUID txId = coordinator.placeOrder(sampleOrder(), null);

        var g = globals.findById(txId).orElseThrow();
        assertThat(g.getState()).isEqualTo(GlobalTxState.HEURISTIC);
        assertThat(g.getLastError()).contains("Cancel rejected with 409");
    }

    /** Scenario 7 (forward retry path): Confirm transport-fails the first time, succeeds on re-drive. */
    @Test
    void confirmFailsTransport_thenRecoveryRedrive_terminatesAsConfirmed() {
        doNothing().when(participantClient).callTry(any(), any());

        // First Confirm to "order" fails as a transport error; second attempt succeeds.
        doThrow(new ParticipantCallException("order", "CONFIRM", -1, "timeout", new RuntimeException("timeout")))
                .doNothing()
                .when(participantClient).callConfirm(argParticipantNamed("order"), any());
        // The other two participants succeed normally.
        doNothing().when(participantClient).callConfirm(argParticipantNamed("inventory"), any());
        doNothing().when(participantClient).callConfirm(argParticipantNamed("payment"), any());

        UUID txId = coordinator.placeOrder(sampleOrder(), null);

        // After the first drive, two confirmed, one still TRIED, global=CONFIRMING.
        var afterFirst = globals.findById(txId).orElseThrow();
        assertThat(afterFirst.getState()).isEqualTo(GlobalTxState.CONFIRMING);

        // Re-drive (this is what RecoveryService would do).
        coordinator.drive(txId, null);

        var afterSecond = globals.findById(txId).orElseThrow();
        assertThat(afterSecond.getState()).isEqualTo(GlobalTxState.CONFIRMED);
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        assertThat(ps).allMatch(p -> p.getState() == ParticipantTxState.CONFIRMED);
    }

    /** Scenario 3/4: re-driving a CONFIRMED or CANCELLED transaction is a no-op. */
    @Test
    void redrivingTerminalTransaction_isNoOp() {
        doNothing().when(participantClient).callTry(any(), any());
        doNothing().when(participantClient).callConfirm(any(), any());
        UUID txId = coordinator.placeOrder(sampleOrder(), null);

        // Drive a few more times; should remain CONFIRMED, no extra RPCs.
        coordinator.drive(txId, null);
        coordinator.drive(txId, null);

        var g = globals.findById(txId).orElseThrow();
        assertThat(g.getState()).isEqualTo(GlobalTxState.CONFIRMED);
        verify(participantClient, times(3)).callTry(any(), any());
        verify(participantClient, times(3)).callConfirm(any(), any());
    }

    @Autowired CoordinatorTxOps txOps;

    private UUID seed(GlobalTxState state) {
        UUID id = UUID.randomUUID();
        txOps.seedTransaction(id, sampleOrder(), (tx, name) -> "{}");
        jdbc.update("update global_transaction set state=? where tx_id=?", state.name(), id);
        return id;
    }

    @Test
    void failedTryCrashSnapshot_cancelsWithoutAnyFurtherTryOrConfirm() {
        UUID id = seed(GlobalTxState.TRYING);
        jdbc.update("update transaction_participant set state='FAILED' where tx_id=? and participant='payment'", id);
        assertThat(coordinator.drive(id, null)).isEqualTo(GlobalTxState.CANCELLED);
        verify(participantClient, times(0)).callTry(any(), any());
        verify(participantClient, times(0)).callConfirm(any(), any());
        verify(participantClient, times(3)).callCancel(any(), any());
    }

    @Test
    void conflictCrashSnapshot_becomesHeuristicWithoutRpc() {
        UUID id = seed(GlobalTxState.CONFIRMING);
        jdbc.update("update transaction_participant set state='TRIED' where tx_id=?", id);
        jdbc.update("update transaction_participant set state='FAILED' where tx_id=? and participant='payment'", id);
        assertThat(coordinator.drive(id, null)).isEqualTo(GlobalTxState.HEURISTIC);
        org.mockito.Mockito.verifyNoInteractions(participantClient);
    }

    @Test
    void emptyParticipantLog_neverReportsSuccess() {
        UUID id = seed(GlobalTxState.TRYING);
        jdbc.update("delete from transaction_participant where tx_id=?", id);
        assertThat(coordinator.drive(id, null)).isEqualTo(GlobalTxState.HEURISTIC);
        org.mockito.Mockito.verifyNoInteractions(participantClient);
    }

    @Test
    void phaseDecisionsCannotReverse() {
        UUID id = seed(GlobalTxState.CONFIRMING);
        UUID owner = UUID.randomUUID();
        assertThat(txOps.acquire(id, owner)).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                txOps.transitionGlobal(id, owner, GlobalTxState.CANCELLING, "stale failure"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(globals.findById(id).orElseThrow().getState()).isEqualTo(GlobalTxState.CONFIRMING);
    }

    @Test
    void activeOwnerExcludesAnotherDrive_andExpiredOwnerCannotWriteOrReleaseNewLease() {
        UUID id = seed(GlobalTxState.TRYING);
        UUID old = UUID.randomUUID();
        assertThat(txOps.acquire(id, old)).isTrue();
        assertThat(coordinator.drive(id, null)).isEqualTo(GlobalTxState.TRYING);
        org.mockito.Mockito.verifyNoInteractions(participantClient);
        jdbc.update("update global_transaction set lease_until=clock_timestamp() - interval '1 second' where tx_id=?", id);
        UUID replacement = UUID.randomUUID();
        assertThat(txOps.acquire(id, replacement)).isTrue();
        Long row = participants.findByTxIdOrderByIdAsc(id).getFirst().getId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> txOps.failParticipant(
                id, old, row, GlobalTxState.TRYING, "late failure")).isInstanceOf(StaleDriverException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> txOps.markParticipant(
                id, old, row, GlobalTxState.TRYING, ParticipantTxState.TRIED, null)).isInstanceOf(StaleDriverException.class);
        txOps.release(id, old);
        assertThat(globals.findById(id).orElseThrow().getDriverToken()).isEqualTo(replacement);
        assertThat(participants.findById(row).orElseThrow().getState()).isEqualTo(ParticipantTxState.PENDING);
        txOps.release(id, replacement);
    }

    @Test
    void failedTryAndCancellationDecisionAreCommittedTogether() {
        UUID id = seed(GlobalTxState.TRYING);
        UUID owner = UUID.randomUUID();
        txOps.acquire(id, owner);
        Long row = participants.findByTxIdOrderByIdAsc(id).getFirst().getId();
        txOps.failParticipant(id, owner, row, GlobalTxState.TRYING, "timeout");
        assertThat(globals.findById(id).orElseThrow().getState()).isEqualTo(GlobalTxState.CANCELLING);
        assertThat(participants.findById(row).orElseThrow().getState()).isEqualTo(ParticipantTxState.FAILED);
    }

    @Test
    void overlappingDriverDoesNotSendRpc() throws Exception {
        UUID id = seed(GlobalTxState.TRYING);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("driver not released");
            return null;
        }).when(participantClient).callTry(argParticipantNamed("inventory"), any());
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> coordinator.drive(id, null));
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(coordinator.drive(id, null)).isEqualTo(GlobalTxState.TRYING);
                verify(participantClient, times(1)).callTry(any(), any());
            } finally { release.countDown(); }
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(GlobalTxState.CONFIRMED);
        }
    }

    @Test
    void crashAfterRpcBeforeOutcome_isReplayableFromPending() {
        UUID id = seed(GlobalTxState.TRYING);
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated process interruption"))
                .doNothing().when(participantClient).callTry(argParticipantNamed("inventory"), any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.drive(id, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(participants.findByTxIdOrderByIdAsc(id).getFirst().getState()).isEqualTo(ParticipantTxState.PENDING);
        assertThat(participants.findByTxIdOrderByIdAsc(id).getFirst().getLastAttemptedAt()).isNotNull();
        assertThat(coordinator.drive(id, null)).isEqualTo(GlobalTxState.CONFIRMED);
    }

    @Test
    void failedDecisionCommitRollsBackParticipantFailureToo() {
        UUID id = seed(GlobalTxState.TRYING);
        UUID owner = UUID.randomUUID();
        txOps.acquire(id, owner);
        Long row = participants.findByTxIdOrderByIdAsc(id).getFirst().getId();
        jdbc.execute("alter table global_transaction add constraint test_reject_cancel check (state <> 'CANCELLING') not valid");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    txOps.failParticipant(id, owner, row, GlobalTxState.TRYING, "timeout"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(globals.findById(id).orElseThrow().getState()).isEqualTo(GlobalTxState.TRYING);
            assertThat(participants.findById(row).orElseThrow().getState()).isEqualTo(ParticipantTxState.PENDING);
        } finally {
            jdbc.execute("alter table global_transaction drop constraint test_reject_cancel");
        }
    }

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    @Test
    void matchingKeyReusesTransactionWithEquivalentMoney() {
        UUID first = coordinator.placeOrder(sampleOrder(), null, null, "order-1");
        var equivalent = new PlaceOrderRequest("CUST-1", "SKU-A", 1, new BigDecimal("100"));
        UUID retry = coordinator.placeOrder(equivalent, "TRY", "8000", "order-1");
        assertThat(retry).isEqualTo(first);
        assertThat(globals.count()).isEqualTo(1);
        assertThat(participants.count()).isEqualTo(3);
        verify(participantClient, times(3)).callTry(any(), any());
        verify(participantClient, times(3)).callConfirm(any(), any());
    }

    @Test
    void keyReuseRejectsChangesToEveryBusinessField() {
        coordinator.placeOrder(sampleOrder(), null, null, "order-1");
        for (var changed : java.util.List.of(
                new PlaceOrderRequest("CUST-2", "SKU-A", 1, new BigDecimal("100.00")),
                new PlaceOrderRequest("CUST-1", "SKU-B", 1, new BigDecimal("100.00")),
                new PlaceOrderRequest("CUST-1", "SKU-A", 2, new BigDecimal("100.00")),
                new PlaceOrderRequest("CUST-1", "SKU-A", 1, new BigDecimal("101.00")))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.placeOrder(changed, null, null, "order-1"))
                    .isInstanceOf(IdempotencyConflictException.class);
        }
        assertThat(globals.count()).isEqualTo(1);
        verify(participantClient, times(3)).callTry(any(), any());
    }

    @Test
    void distinctKeysAllowIntentionalIdenticalOrders() {
        UUID first = coordinator.placeOrder(sampleOrder(), null, null, "order-1");
        UUID second = coordinator.placeOrder(sampleOrder(), null, null, "order-2");
        assertThat(second).isNotEqualTo(first);
        assertThat(globals.count()).isEqualTo(2);
    }

    @Test
    void cancelledTransactionKeepsItsKey() {
        doThrow(new ParticipantCallException("inventory", "TRY", 500, "failure", null))
                .when(participantClient).callTry(any(), any());
        UUID first = coordinator.placeOrder(sampleOrder(), null, null, "cancelled-order");
        org.mockito.Mockito.clearInvocations(participantClient);
        assertThat(coordinator.placeOrder(sampleOrder(), null, null, "cancelled-order")).isEqualTo(first);
        assertThat(globals.findById(first).orElseThrow().getState()).isEqualTo(GlobalTxState.CANCELLED);
        org.mockito.Mockito.verifyNoInteractions(participantClient);
    }

    @Test
    void concurrentKeyRetriesShareAnActiveTransaction() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            return null;
        }).when(participantClient).callTry(argParticipantNamed("inventory"), any());
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> coordinator.placeOrder(sampleOrder(), null, null, "concurrent-key"));
            UUID retry;
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                retry = coordinator.placeOrder(sampleOrder(), null, null, "concurrent-key");
                assertThat(globals.count()).isEqualTo(1);
                assertThat(globals.findById(retry).orElseThrow().getState()).isEqualTo(GlobalTxState.TRYING);
                verify(participantClient, times(1)).callTry(any(), any());
            } finally { release.countDown(); }
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(retry);
        }
    }

    @Test
    void crashAfterSeedCommit_retryResumesSameTransaction() throws Exception {
        String fingerprint = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(java.util.List.of("CUST-1", "SKU-A", 1, "100.00"));
        UUID seeded = txOps.seedOrGet("crash-key", fingerprint, sampleOrder(), (id, name) -> "{}").txId();
        assertThat(globals.findById(seeded).orElseThrow().getState()).isEqualTo(GlobalTxState.STARTED);
        assertThat(coordinator.placeOrder(sampleOrder(), null, null, "crash-key")).isEqualTo(seeded);
        assertThat(globals.findById(seeded).orElseThrow().getState()).isEqualTo(GlobalTxState.CONFIRMED);
        assertThat(globals.count()).isEqualTo(1);
    }

    @Test
    void failedSeedRollsBackKeyAndPartialParticipantLog() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> txOps.seedOrGet("rollback-key", "fingerprint", sampleOrder(),
                (id, name) -> { if (name.equals("payment")) throw new IllegalStateException("seed interrupted"); return "{}"; }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(globals.count()).isZero();
        assertThat(participants.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_idempotency", Long.class)).isZero();
        coordinator.placeOrder(sampleOrder(), null, null, "rollback-key");
        assertThat(globals.count()).isEqualTo(1);
    }

    @Test
    void httpRejectsInvalidKeyAndConflictingPayload() throws Exception {
        String body = "{\"customerId\":\"CUST-1\",\"sku\":\"SKU-A\",\"qty\":1,\"amount\":100.00}";
        for (String key : java.util.List.of("", "has spaces", "x".repeat(129))) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                    .contentType("application/json").header("Idempotency-Key", key).content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        assertThat(globals.count()).isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                .contentType("application/json").header("Idempotency-Key", "http-key").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                .contentType("application/json").header("Idempotency-Key", "http-key").content(body.replace("100.00", "101.00")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(globals.count()).isEqualTo(1);
    }

    @Test
    void terminalReplayDoesNotWaitForCreationOrDriverLocks() throws Exception {
        String key = "read-only-replay";
        UUID id = coordinator.placeOrder(sampleOrder(), null, null, key);
        try (var connection = jdbc.getDataSource().getConnection();
             var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try {
                try (var stmt = connection.prepareStatement("select pg_advisory_xact_lock(27182,hashtext(?))")) {
                    stmt.setString(1, key); stmt.execute();
                }
                try (var stmt = connection.prepareStatement("select tx_id from global_transaction where tx_id=? for update")) {
                    stmt.setObject(1, id); stmt.execute();
                }
                var retry = pool.submit(() -> coordinator.placeOrder(sampleOrder(), null, null, key));
                assertThat(retry.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(id);
            } finally { connection.rollback(); }
        }
    }

    @Test
    void directParticipantUpdatesKeepTransactionAndStateGuards() {
        UUID first = seed(GlobalTxState.TRYING);
        UUID other = seed(GlobalTxState.TRYING);
        UUID owner = UUID.randomUUID();
        txOps.acquire(first, owner);
        Long otherRow = participants.findByTxIdOrderByIdAsc(other).getFirst().getId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> txOps.markParticipant(first, owner, otherRow,
                GlobalTxState.TRYING, ParticipantTxState.TRIED, null)).isInstanceOf(IllegalStateException.class);
        Long row = participants.findByTxIdOrderByIdAsc(first).getFirst().getId();
        jdbc.update("update transaction_participant set state='CANCELLED' where id=?", row);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> txOps.markParticipant(first, owner, row,
                GlobalTxState.TRYING, ParticipantTxState.TRIED, null)).isInstanceOf(IllegalStateException.class);
        assertThat(participants.findById(row).orElseThrow().getState()).isEqualTo(ParticipantTxState.CANCELLED);
        assertThat(participants.findById(otherRow).orElseThrow().getState()).isEqualTo(ParticipantTxState.PENDING);
    }

    private TransactionParticipant argParticipantNamed(String name) {
        return org.mockito.ArgumentMatchers.argThat(p -> p != null && name.equals(p.getParticipant()));
    }
}
