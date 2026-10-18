package com.swiftcheck.aml.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.SourceFile;

@Repository
public interface SourceFileRepository extends JpaRepository<SourceFile, Long> {

    List<SourceFile> findByExecutionIdOrderByIdAsc(long executionId);
}
