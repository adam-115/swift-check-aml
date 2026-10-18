package com.swiftcheck.aml.repositories;

import com.swiftcheck.aml.entities.AmlSearchHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AmlSearchHistoryRepository extends JpaRepository<AmlSearchHistory, Long> {
}
