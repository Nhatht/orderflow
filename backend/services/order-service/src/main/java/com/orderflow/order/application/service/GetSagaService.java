package com.orderflow.order.application.service;

import com.orderflow.order.application.dto.SagaView;
import com.orderflow.order.application.port.in.GetSagaQuery;
import com.orderflow.order.application.port.out.SagaStateRepositoryPort;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import com.orderflow.order.domain.model.OrderSaga;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetSagaService implements GetSagaQuery {

    private final SagaStateRepositoryPort sagas;

    @Override
    public SagaView getByOrderId(UUID orderId) {
        OrderSaga saga = sagas.findByOrderId(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        var steps = sagas.history(orderId).stream()
                .map(s -> new SagaView.Step(s.step(), s.outcome(), s.detail(), s.occurredAt()))
                .toList();

        return new SagaView(orderId, saga.status().name(), saga.currentStep().name(),
                saga.failureReason(), saga.startedAt(), saga.updatedAt(), steps);
    }
}
