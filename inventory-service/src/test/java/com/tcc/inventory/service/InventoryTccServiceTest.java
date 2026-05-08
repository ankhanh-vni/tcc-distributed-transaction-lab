package com.tcc.inventory.service;

import com.tcc.common.tcc.ParticipantState;
import com.tcc.common.tcc.TccErrorCode;
import com.tcc.common.tcc.TccException;
import com.tcc.inventory.api.dto.ReserveRequest;
import com.tcc.inventory.repo.InventoryReservationRepository;
import com.tcc.inventory.repo.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@EnabledIf("dockerAvailable")
class InventoryTccServiceTest {

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
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired InventoryTccService service;
    @Autowired ProductRepository products;
    @Autowired InventoryReservationRepository reservations;

    @Test
    void try_reservesStock() {
        var tx = UUID.randomUUID();
        int before = products.findBySku("SKU-A").orElseThrow().getAvailableQty();

        var state = service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 2));

        assertThat(state).isEqualTo(ParticipantState.TRIED);
        var product = products.findBySku("SKU-A").orElseThrow();
        assertThat(product.getAvailableQty()).isEqualTo(before - 2);
        assertThat(product.getReservedQty()).isEqualTo(2);
    }

    @Test
    void try_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 1));
        int after = products.findBySku("SKU-A").orElseThrow().getAvailableQty();

        var state = service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 1));

        assertThat(state).isEqualTo(ParticipantState.TRIED);
        assertThat(products.findBySku("SKU-A").orElseThrow().getAvailableQty()).isEqualTo(after);
    }

    @Test
    void confirm_appliesReservation() {
        var tx = UUID.randomUUID();
        service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 3));
        var beforeAvail = products.findBySku("SKU-A").orElseThrow().getAvailableQty();

        var state = service.confirm(tx);

        assertThat(state).isEqualTo(ParticipantState.CONFIRMED);
        var p = products.findBySku("SKU-A").orElseThrow();
        assertThat(p.getAvailableQty()).isEqualTo(beforeAvail);
        assertThat(p.getReservedQty()).isEqualTo(0);
    }

    @Test
    void confirm_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 2));
        service.confirm(tx);
        int reservedAfterFirst = products.findBySku("SKU-A").orElseThrow().getReservedQty();

        var state = service.confirm(tx);

        assertThat(state).isEqualTo(ParticipantState.CONFIRMED);
        assertThat(products.findBySku("SKU-A").orElseThrow().getReservedQty()).isEqualTo(reservedAfterFirst);
    }

    @Test
    void cancel_releasesReservation() {
        var tx = UUID.randomUUID();
        var avail = products.findBySku("SKU-A").orElseThrow().getAvailableQty();
        service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 4));
        assertThat(products.findBySku("SKU-A").orElseThrow().getAvailableQty()).isEqualTo(avail - 4);

        var state = service.cancel(tx);

        assertThat(state).isEqualTo(ParticipantState.CANCELLED);
        var p = products.findBySku("SKU-A").orElseThrow();
        assertThat(p.getAvailableQty()).isEqualTo(avail);
        assertThat(p.getReservedQty()).isEqualTo(0);
    }

    @Test
    void cancel_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 1));
        service.cancel(tx);
        var avail = products.findBySku("SKU-A").orElseThrow().getAvailableQty();

        var state = service.cancel(tx);

        assertThat(state).isEqualTo(ParticipantState.CANCELLED);
        assertThat(products.findBySku("SKU-A").orElseThrow().getAvailableQty()).isEqualTo(avail);
    }

    @Test
    void cancel_withoutPriorTry_writesPreventiveTombstone() {
        var tx = UUID.randomUUID();

        var state = service.cancel(tx);

        assertThat(state).isEqualTo(ParticipantState.CANCELLED);
        assertThat(reservations.findById(tx)).isPresent();
        assertThat(reservations.findById(tx).get().getState()).isEqualTo(ParticipantState.CANCELLED);
    }

    @Test
    void try_afterPreventiveCancel_isRejected() {
        var tx = UUID.randomUUID();
        service.cancel(tx);

        assertThatThrownBy(() -> service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 1)))
                .isInstanceOf(TccException.class)
                .matches(ex -> ((TccException) ex).getCode() == TccErrorCode.CONFIRM_AFTER_CANCEL);
    }

    @Test
    void confirm_afterCancel_returns409() {
        var tx = UUID.randomUUID();
        service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 1));
        service.cancel(tx);

        assertThatThrownBy(() -> service.confirm(tx))
                .isInstanceOf(TccException.class)
                .matches(ex -> ((TccException) ex).getCode() == TccErrorCode.CONFIRM_AFTER_CANCEL);
    }

    @Test
    void cancel_afterConfirm_returns409() {
        var tx = UUID.randomUUID();
        service.tryReserve(tx, new ReserveRequest(tx, "SKU-A", 1));
        service.confirm(tx);

        assertThatThrownBy(() -> service.cancel(tx))
                .isInstanceOf(TccException.class)
                .matches(ex -> ((TccException) ex).getCode() == TccErrorCode.CANCEL_AFTER_CONFIRM);
    }

    @Test
    void confirm_unknownTx_isRejected() {
        assertThatThrownBy(() -> service.confirm(UUID.randomUUID()))
                .isInstanceOf(TccException.class)
                .matches(ex -> ((TccException) ex).getCode() == TccErrorCode.UNKNOWN_TX);
    }
}
