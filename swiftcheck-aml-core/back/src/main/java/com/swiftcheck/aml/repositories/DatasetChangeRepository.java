package com.swiftcheck.aml.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.ChangeType;
import com.swiftcheck.aml.entities.DatasetChange;

@Repository
public interface DatasetChangeRepository extends JpaRepository<DatasetChange, Long> {

    Page<DatasetChange> findByExecutionIdOrderByIdAsc(long executionId, Pageable pageable);

    Page<DatasetChange> findByExecutionIdAndChangeTypeOrderByIdAsc(long executionId, ChangeType changeType,
            Pageable pageable);
}
