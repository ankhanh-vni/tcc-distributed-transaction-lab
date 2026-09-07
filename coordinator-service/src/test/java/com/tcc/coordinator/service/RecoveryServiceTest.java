package com.tcc.coordinator.service;

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

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * Scenario 8 from the plan: simulate a coordinator crash by seeding the persistent log
 * directly with a TRYING global transaction, then run recovery and assert it drives the
 * transaction to a terminal state. This is the most MicroTx-like test in the suite —
 * recovery is exactly the reason MicroTx's TCS exists.
 */
@Testcontainers
@SpringBootTest
@EnabledIf("dockerAvailable")
class RecoveryServiceTest {

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
        registry.add("tcc.recovery.enabled", () -> "false");
        registry.add("tcc.recovery.stuck-after-ms", () -> "1");
        registry.add("tcc.participants.inventory.base-url", () -> "http://localhost:0");
        registry.add("tcc.participants.inventory.resource-path", () -> "/tcc/inventory/reservations");
        registry.add("tcc.participants.payment.base-url", () -> "http://localhost:0");
        registry.add("tcc.participants.payment.resource-path", () -> "/tcc/payment/authorizations");
        registry.add("tcc.participants.order.base-url", () -> "http://localhost:0");
        registry.add("tcc.participants.order.resource-path", () -> "/tcc/orders");
    }

    @Autowired RecoveryService recovery;
    @Autowired GlobalTransactionRepository globals;
    @Autowired TransactionParticipantRepository participants;
    @MockBean ParticipantClient participantClient;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE transaction_participant, global_transaction CASCADE");
    }

    @Test
    void recovers_stuckTryingTransaction_toCancelled_whenInventoryIsTried() {
        // Simulate: coordinator started a tx, inventory Try succeeded, then coordinator crashed
        // before calling payment.
        UUID txId = UUID.randomUUID();
        var g = new GlobalTransaction(txId, "order:CUST-1:SKU-A", GlobalTxState.TRYING);
        globals.saveAndFlush(g);
        var inv = participants.saveAndFlush(new TransactionParticipant(txId, "inventory", "http://x", "/tcc/inventory/reservations",
                ParticipantTxState.TRIED, "{\"txId\":\"" + txId + "\",\"sku\":\"SKU-A\",\"qty\":1}"));
        var pay = participants.saveAndFlush(new TransactionParticipant(txId, "payment", "http://x", "/tcc/payment/authorizations",
                ParticipantTxState.PENDING, "{\"txId\":\"" + txId + "\",\"customerId\":\"CUST-1\",\"amount\":\"100.00\"}"));
        var ord = participants.saveAndFlush(new TransactionParticipant(txId, "order", "http://x", "/tcc/orders",
                ParticipantTxState.PENDING, "{\"txId\":\"" + txId + "\",\"customerId\":\"CUST-1\"}"));
        backdate(g);

        // Scenario interpretation: TRYING with one PENDING participant means we don't know
        // if Try ever reached payment. A continuation Try might succeed or fail; here we
        // simulate that on resume, payment Try fails (perhaps the in-flight request was
        // dropped). Result must be CANCELLED.
        doNothing().when(participantClient).callTry(any(), any());
        doNothing().when(participantClient).callCancel(any(), any());
        org.mockito.Mockito.doThrow(new com.tcc.coordinator.client.ParticipantCallException(
                "payment", "TRY", -1, "transport failed", new RuntimeException()))
                .when(participantClient).callTry(
                        org.mockito.ArgumentMatchers.argThat(p -> p != null && "payment".equals(p.getParticipant())),
                        any());

        int driven = recovery.recoverOnce();

        assertThat(driven).isEqualTo(1);
        var after = globals.findById(txId).orElseThrow();
        assertThat(after.getState()).isEqualTo(GlobalTxState.CANCELLED);
        verify(participantClient, atLeastOnce()).callCancel(any(), any());
    }

    @Test
    void recovers_stuckConfirmingTransaction_toConfirmed() {
        UUID txId = UUID.randomUUID();
        var g = new GlobalTransaction(txId, "order:CUST-1:SKU-A", GlobalTxState.CONFIRMING);
        globals.saveAndFlush(g);
        participants.saveAndFlush(new TransactionParticipant(txId, "inventory", "http://x", "/tcc/inventory/reservations",
                ParticipantTxState.CONFIRMED, "{}"));
        participants.saveAndFlush(new TransactionParticipant(txId, "payment", "http://x", "/tcc/payment/authorizations",
                ParticipantTxState.TRIED, "{}"));
        participants.saveAndFlush(new TransactionParticipant(txId, "order", "http://x", "/tcc/orders",
                ParticipantTxState.TRIED, "{}"));
        backdate(g);

        doNothing().when(participantClient).callConfirm(any(), any());

        int driven = recovery.recoverOnce();

        assertThat(driven).isEqualTo(1);
        assertThat(globals.findById(txId).orElseThrow().getState()).isEqualTo(GlobalTxState.CONFIRMED);
        verify(participantClient, org.mockito.Mockito.times(2)).callConfirm(any(), any());
    }

    @Test
    void recovery_skipsRecentTransactions() {
        UUID txId = UUID.randomUUID();
        var g = new GlobalTransaction(txId, "fresh", GlobalTxState.CONFIRMING);
        globals.saveAndFlush(g);
        participants.saveAndFlush(new TransactionParticipant(txId, "inventory", "http://x", "/tcc/inventory/reservations",
                ParticipantTxState.TRIED, "{}"));
        jdbc.update("update global_transaction set updated_at=clock_timestamp() + interval '1 hour' where tx_id=?", txId);
        assertThat(recovery.recoverOnce()).isZero();
        org.mockito.Mockito.verifyNoInteractions(participantClient);
    }

    @Test
    void scanIsBoundedAndExcludesActiveLeasesAndHeuristics() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID leased = UUID.randomUUID();
        UUID heuristic = UUID.randomUUID();
        for (UUID id : java.util.List.of(first, second, leased, heuristic)) {
            globals.saveAndFlush(new GlobalTransaction(id, "scan", GlobalTxState.TRYING));
        }
        jdbc.update("update global_transaction set updated_at=clock_timestamp() - interval '2 hours'");
        jdbc.update("update global_transaction set updated_at=clock_timestamp() - interval '3 hours' where tx_id=?", first);
        jdbc.update("update global_transaction set lease_until=clock_timestamp() + interval '1 hour', driver_token=? where tx_id=?", UUID.randomUUID(), leased);
        jdbc.update("update global_transaction set state='HEURISTIC' where tx_id=?", heuristic);
        assertThat(globals.findRecoverable(1, 1)).extracting(GlobalTransaction::getTxId).containsExactly(first);
        assertThat(globals.findRecoverable(1, 10)).extracting(GlobalTransaction::getTxId).containsExactly(first, second);
    }

    /** Push the global tx's updated_at into the past so the recovery threshold matches it. */
    private void backdate(GlobalTransaction g) {
        jdbc.update("update global_transaction set updated_at=clock_timestamp() - interval '1 hour' where tx_id=?", g.getTxId());
    }
}
