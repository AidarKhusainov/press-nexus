package com.nexus.press.app.repository;

import com.nexus.press.app.repository.entity.CategoryEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import java.util.UUID;

public interface CategoryRepository extends ReactiveCrudRepository<CategoryEntity, UUID> {
}

