package com.orderflow.inventory.adapter.out.persistence;

import com.orderflow.inventory.adapter.out.persistence.mapper.InventoryMapper;
import com.orderflow.inventory.adapter.out.persistence.repository.StockReservationJpaRepository;
import com.orderflow.inventory.application.port.out.ReservationRepositoryPort;
import com.orderflow.inventory.domain.model.StockReservation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReservationPersistenceAdapter implements ReservationRepositoryPort {

    private final StockReservationJpaRepository repository;
    private final InventoryMapper mapper;

    @Override
    public StockReservation save(StockReservation reservation) {
        var entity = repository.findById(reservation.id())
                .map(existing -> {
                    mapper.updateEntity(existing, reservation);
                    return existing;
                })
                .orElseGet(() -> mapper.toEntity(reservation));

        return mapper.toDomain(repository.save(entity));
    }

    @Override
    public Optional<StockReservation> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<StockReservation> findByOrderIdAndProductId(UUID orderId, UUID productId) {
        return repository.findByOrderIdAndProductId(orderId, productId).map(mapper::toDomain);
    }

    @Override
    public List<StockReservation> findByOrderId(UUID orderId) {
        return repository.findByOrderId(orderId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<StockReservation> findExpired(Instant now, int limit) {
        return repository.findExpired(now, Limit.of(limit)).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
