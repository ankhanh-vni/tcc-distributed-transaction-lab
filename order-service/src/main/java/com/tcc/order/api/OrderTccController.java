package com.tcc.order.api;

import com.tcc.common.dto.TccStatusResponse;
import com.tcc.order.api.dto.CreateOrderRequest;
import com.tcc.order.service.OrderTccService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/tcc/orders")
public class OrderTccController {

    private final OrderTccService service;

    public OrderTccController(OrderTccService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TccStatusResponse> tryCreate(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(new TccStatusResponse(request.txId(), service.tryCreate(request.txId(), request)));
    }

    @PutMapping("/{txId}/confirm")
    public ResponseEntity<TccStatusResponse> confirm(@PathVariable UUID txId) {
        return ResponseEntity.ok(new TccStatusResponse(txId, service.confirm(txId)));
    }

    @DeleteMapping("/{txId}")
    public ResponseEntity<TccStatusResponse> cancel(@PathVariable UUID txId) {
        return ResponseEntity.ok(new TccStatusResponse(txId, service.cancel(txId)));
    }

    @GetMapping("/{txId}")
    public ResponseEntity<TccStatusResponse> get(@PathVariable UUID txId) {
        return ResponseEntity.ok(new TccStatusResponse(txId, service.getState(txId)));
    }
}
