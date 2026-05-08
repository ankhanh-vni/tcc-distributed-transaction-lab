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
    @Test
    void allParticipantsSucceed_terminatesAsConfirmed() {
        doNothing().when(participantClient).callTry(any(), any());
        doNothing().when(participantClient).callConfirm(any());

        UUID txId = coordinator.placeOrder(sampleOrder(), null);

        var g = globals.findById(txId).orElseThrow();
        assertThat(g.getState()).isEqualTo(GlobalTxState.CONFIRMED);
        var ps = participants.findByTxIdOrderByIdAsc(txId);
        assertThat(ps).hasSize(3);
        assertThat(ps).allMatch(p -> p.getState() == ParticipantTxState.CONFIRMED);
        verify(participantClient, times(3)).callTry(any(), any());
        verify(participantClient, times(3)).callConfirm(any());
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
        verify(participantClient, times(0)).callConfirm(any());
    }

    /** Scenario 5/6: participant returns 409 during Confirm → HEURISTIC. */
    @Test
    void confirmReturns409_isHeuristic() {
        doNothing().when(participantClient).callTry(any(), any());
        doNothing().when(participantClient).callConfirm(any());
        doThrow(new ParticipantCallException("payment", "CONFIRM", 409, "Confirm after Cancel", new RuntimeException()))
                .when(participantClient).callConfirm(argParticipantNamed("payment"));

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
                .when(participantClient).callConfirm(argParticipantNamed("order"));
        // The other two participants succeed normally.
        doNothing().when(participantClient).callConfirm(argParticipantNamed("inventory"));
        doNothing().when(participantClient).callConfirm(argParticipantNamed("payment"));

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
        doNothing().when(participantClient).callConfirm(any());
        UUID txId = coordinator.placeOrder(sampleOrder(), null);

        // Drive a few more times; should remain CONFIRMED, no extra RPCs.
        coordinator.drive(txId, null);
        coordinator.drive(txId, null);

        var g = globals.findById(txId).orElseThrow();
        assertThat(g.getState()).isEqualTo(GlobalTxState.CONFIRMED);
        verify(participantClient, times(3)).callTry(any(), any());
        verify(participantClient, times(3)).callConfirm(any());
    }

    private TransactionParticipant argParticipantNamed(String name) {
        return org.mockito.ArgumentMatchers.argThat(p -> p != null && name.equals(p.getParticipant()));
    }
}
