package com.nexus.press.app.repository;

import com.nexus.press.app.repository.entity.TagEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import java.util.UUID;

public interface TagRepository extends ReactiveCrudRepository<TagEntity, UUID> {
}

