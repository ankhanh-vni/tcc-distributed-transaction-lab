package com.tcc.inventory.api;

import com.tcc.common.dto.TccStatusResponse;
import com.tcc.inventory.api.dto.ReserveRequest;
import com.tcc.inventory.service.InventoryTccService;
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
@RequestMapping("/tcc/inventory/reservations")
public class InventoryTccController {

    private final InventoryTccService service;

    public InventoryTccController(InventoryTccService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TccStatusResponse> tryReserve(@Valid @RequestBody ReserveRequest request) {
        var state = service.tryReserve(request.txId(), request);
        return ResponseEntity.ok(new TccStatusResponse(request.txId(), state));
    }

    @PutMapping("/{txId}/confirm")
    public ResponseEntity<TccStatusResponse> confirm(@PathVariable UUID txId) {
        var state = service.confirm(txId);
        return ResponseEntity.ok(new TccStatusResponse(txId, state));
    }

    @DeleteMapping("/{txId}")
    public ResponseEntity<TccStatusResponse> cancel(@PathVariable UUID txId) {
        var state = service.cancel(txId);
        return ResponseEntity.ok(new TccStatusResponse(txId, state));
    }

    @GetMapping("/{txId}")
    public ResponseEntity<TccStatusResponse> get(@PathVariable UUID txId) {
        return ResponseEntity.ok(new TccStatusResponse(txId, service.getState(txId)));
    }
}
