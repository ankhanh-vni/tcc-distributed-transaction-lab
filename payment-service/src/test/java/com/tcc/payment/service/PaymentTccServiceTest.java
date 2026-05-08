package com.tcc.payment.service;

import com.tcc.common.tcc.ParticipantState;
import com.tcc.common.tcc.TccErrorCode;
import com.tcc.common.tcc.TccException;
import com.tcc.payment.api.dto.AuthorizeRequest;
import com.tcc.payment.repo.AccountRepository;
import com.tcc.payment.repo.PaymentAuthorizationRepository;
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
class PaymentTccServiceTest {

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

    @Autowired PaymentTccService service;
    @Autowired AccountRepository accounts;
    @Autowired PaymentAuthorizationRepository auths;

    @Test
    void try_freezesAmount() {
        var tx = UUID.randomUUID();
        var before = accounts.findByCustomerId("CUST-1").orElseThrow().getBalance();

        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("100.00")));

        var a = accounts.findByCustomerId("CUST-1").orElseThrow();
        assertThat(a.getBalance()).isEqualByComparingTo(before.subtract(new BigDecimal("100.00")));
        assertThat(a.getFrozenAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void try_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("10.00")));
        var balance = accounts.findByCustomerId("CUST-1").orElseThrow().getBalance();

        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("10.00")));

        assertThat(accounts.findByCustomerId("CUST-1").orElseThrow().getBalance()).isEqualByComparingTo(balance);
    }

    @Test
    void confirm_capturesAmount() {
        var tx = UUID.randomUUID();
        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("25.00")));
        var balanceAfterFreeze = accounts.findByCustomerId("CUST-1").orElseThrow().getBalance();

        service.confirm(tx);

        var a = accounts.findByCustomerId("CUST-1").orElseThrow();
        assertThat(a.getBalance()).isEqualByComparingTo(balanceAfterFreeze);
        assertThat(a.getFrozenAmount()).isEqualByComparingTo("0");
    }

    @Test
    void cancel_releasesFrozenAmount() {
        var tx = UUID.randomUUID();
        var before = accounts.findByCustomerId("CUST-1").orElseThrow().getBalance();
        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("75.00")));

        service.cancel(tx);

        var a = accounts.findByCustomerId("CUST-1").orElseThrow();
        assertThat(a.getBalance()).isEqualByComparingTo(before);
        assertThat(a.getFrozenAmount()).isEqualByComparingTo("0");
    }

    @Test
    void confirm_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("5.00")));
        service.confirm(tx);
        var balance = accounts.findByCustomerId("CUST-1").orElseThrow().getBalance();

        service.confirm(tx);

        assertThat(accounts.findByCustomerId("CUST-1").orElseThrow().getBalance()).isEqualByComparingTo(balance);
    }

    @Test
    void cancel_isIdempotent() {
        var tx = UUID.randomUUID();
        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("3.00")));
        service.cancel(tx);
        var balance = accounts.findByCustomerId("CUST-1").orElseThrow().getBalance();

        service.cancel(tx);

        assertThat(accounts.findByCustomerId("CUST-1").orElseThrow().getBalance()).isEqualByComparingTo(balance);
    }

    @Test
    void confirm_afterCancel_returns409() {
        var tx = UUID.randomUUID();
        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("1.00")));
        service.cancel(tx);

        assertThatThrownBy(() -> service.confirm(tx))
                .isInstanceOf(TccException.class)
                .matches(e -> ((TccException) e).getCode() == TccErrorCode.CONFIRM_AFTER_CANCEL);
    }

    @Test
    void cancel_afterConfirm_returns409() {
        var tx = UUID.randomUUID();
        service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-1", new BigDecimal("1.00")));
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
        assertThat(auths.findById(tx)).isPresent();
    }

    @Test
    void try_insufficientBalance_throws() {
        var tx = UUID.randomUUID();

        assertThatThrownBy(() -> service.tryAuthorize(tx, new AuthorizeRequest(tx, "CUST-2", new BigDecimal("9999"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient balance");
    }
}
