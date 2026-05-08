package com.tcc.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", unique = true, nullable = false)
    private String customerId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(name = "frozen_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal frozenAmount;

    @Version
    private Long version;

    protected Account() {
    }

    public Account(String customerId, BigDecimal balance) {
        this.customerId = customerId;
        this.balance = balance;
        this.frozenAmount = BigDecimal.ZERO;
    }

    public void freeze(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException("insufficient balance for customer=" + customerId
                    + " requested=" + amount + " balance=" + balance);
        }
        balance = balance.subtract(amount);
        frozenAmount = frozenAmount.add(amount);
    }

    public void capture(BigDecimal amount) {
        if (frozenAmount.compareTo(amount) < 0) {
            throw new IllegalStateException("frozen_amount underflow for customer=" + customerId);
        }
        frozenAmount = frozenAmount.subtract(amount);
    }

    public void unfreeze(BigDecimal amount) {
        if (frozenAmount.compareTo(amount) < 0) {
            throw new IllegalStateException("frozen_amount underflow for customer=" + customerId);
        }
        frozenAmount = frozenAmount.subtract(amount);
        balance = balance.add(amount);
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getFrozenAmount() {
        return frozenAmount;
    }
}
