package com.tcc.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    @Version
    private Long version;

    protected Product() {
    }

    public Product(String sku, int availableQty) {
        this.sku = sku;
        this.availableQty = availableQty;
        this.reservedQty = 0;
    }

    public void reserve(int qty) {
        if (availableQty < qty) {
            throw new IllegalStateException("not enough stock for sku=" + sku + " requested=" + qty + " available=" + availableQty);
        }
        availableQty -= qty;
        reservedQty += qty;
    }

    public void confirmReservation(int qty) {
        if (reservedQty < qty) {
            throw new IllegalStateException("reserved_qty underflow for sku=" + sku);
        }
        reservedQty -= qty;
    }

    public void releaseReservation(int qty) {
        if (reservedQty < qty) {
            throw new IllegalStateException("reserved_qty underflow for sku=" + sku);
        }
        reservedQty -= qty;
        availableQty += qty;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailableQty() {
        return availableQty;
    }

    public int getReservedQty() {
        return reservedQty;
    }
}
