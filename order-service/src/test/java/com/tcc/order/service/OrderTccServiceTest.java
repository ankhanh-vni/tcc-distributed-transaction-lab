package com.tcc.order.service;

import com.tcc.common.tcc.ParticipantState;
import com.tcc.common.tcc.TccErrorCode;
import com.tcc.common.tcc.TccException;
import com.tcc.order.api.dto.CreateOrderRequest;
import com.tcc.order.domain.OrderState;
import com.tcc.order.repo.CustomerOrderRepository;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@EnabledIf("dockerAvailable")
class OrderTccServiceTest {

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
    }

    @Autowired OrderTccService service;
    @Autowired CustomerOrderRepository orders;

    private CreateOrderRequest req(UUID tx) {
        return new CreateOrderRequest(tx, "CUST-1", "SKU-A", 1, new BigDecimal("100.00"));
    }

    @Test
    void try_createsPendingOrder() {
        var tx = UUID.randomUUID();

        var state = service.tryCreate(tx, req(tx));

        assertThat(state).isEqualTo(ParticipantState.TRIED);
        var saved = orders.findById(tx).orElseThrow();
        assertThat(saved.getState()).isEqualTo(OrderState.PENDING);
    }

    @Test
    void try_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryCreate(tx, req(tx));

        var state = service.tryCreate(tx, req(tx));

        assertThat(state).isEqualTo(ParticipantState.TRIED);
        assertThat(orders.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void confirm_marksConfirmed() {
        var tx = UUID.randomUUID();
        service.tryCreate(tx, req(tx));

        var state = service.confirm(tx);

        assertThat(state).isEqualTo(ParticipantState.CONFIRMED);
        assertThat(orders.findById(tx).orElseThrow().getState()).isEqualTo(OrderState.CONFIRMED);
    }

    @Test
    void cancel_marksCancelled() {
        var tx = UUID.randomUUID();
        service.tryCreate(tx, req(tx));

        var state = service.cancel(tx);

        assertThat(state).isEqualTo(ParticipantState.CANCELLED);
        assertThat(orders.findById(tx).orElseThrow().getState()).isEqualTo(OrderState.CANCELLED);
    }

    @Test
    void confirm_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryCreate(tx, req(tx));
        service.confirm(tx);

        assertThat(service.confirm(tx)).isEqualTo(ParticipantState.CONFIRMED);
    }

    @Test
    void cancel_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryCreate(tx, req(tx));
        service.cancel(tx);

        assertThat(service.cancel(tx)).isEqualTo(ParticipantState.CANCELLED);
    }

    @Test
    void confirm_afterCancel_returns409() {
        var tx = UUID.randomUUID();
        service.tryCreate(tx, req(tx));
        service.cancel(tx);

        assertThatThrownBy(() -> service.confirm(tx))
                .isInstanceOf(TccException.class)
                .matches(e -> ((TccException) e).getCode() == TccErrorCode.CONFIRM_AFTER_CANCEL);
    }

    @Test
    void cancel_afterConfirm_returns409() {
        var tx = UUID.randomUUID();
        service.tryCreate(tx, req(tx));
        service.confirm(tx);

        assertThatThrownBy(() -> service.cancel(tx))
                .isInstanceOf(TccException.class)
                .matches(e -> ((TccException) e).getCode() == TccErrorCode.CANCEL_AFTER_CONFIRM);
    }

    @Test
    void cancel_withoutPriorTry_writesPreventiveTombstone() {
        var tx = UUID.randomUUID();

        var state = service.cancel(tx);

        assertThat(state).isEqualTo(ParticipantState.CANCELLED);
        assertThat(orders.findById(tx)).isPresent();
        assertThat(orders.findById(tx).get().getState()).isEqualTo(OrderState.CANCELLED);
    }
}
