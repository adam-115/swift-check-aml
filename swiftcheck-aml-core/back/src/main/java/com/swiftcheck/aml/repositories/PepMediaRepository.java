package com.swiftcheck.aml.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.PepMedia;

@Repository
public interface PepMediaRepository extends JpaRepository<PepMedia, Long> {

    List<PepMedia> findByEntityId(String entityId);

    List<PepMedia> findByDatasetNameAndOrigin(String datasetName, String origin);

    Optional<PepMedia> findByDatasetNameAndEntityIdAndOrigin(String datasetName, String entityId, String origin);
}
