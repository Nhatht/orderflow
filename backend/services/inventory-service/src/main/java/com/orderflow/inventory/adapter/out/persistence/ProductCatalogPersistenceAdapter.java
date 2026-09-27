package com.orderflow.inventory.adapter.out.persistence;

import com.orderflow.inventory.adapter.out.persistence.repository.ProductJpaRepository;
import com.orderflow.inventory.application.dto.ProductView;
import com.orderflow.inventory.application.port.out.ProductCatalogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ProductCatalogPersistenceAdapter implements ProductCatalogPort {

    private final ProductJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> findAllWithAvailability() {
        return repository.findAllWithAvailability();
    }
}
