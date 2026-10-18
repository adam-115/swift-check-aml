package com.swiftcheck.aml.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.ParserTestFailure;
import com.swiftcheck.aml.entities.TestFailureReason;

@Repository
public interface ParserTestFailureRepository extends JpaRepository<ParserTestFailure, Long> {

    Page<ParserTestFailure> findByTestIdOrderByIdAsc(long testId, Pageable pageable);

    Page<ParserTestFailure> findByTestIdAndReasonOrderByIdAsc(long testId, TestFailureReason reason, Pageable pageable);
}
