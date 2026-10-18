package com.swiftcheck.aml.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.ListProvider;

@Repository
public interface ListProviderRepository extends JpaRepository<ListProvider, Long> {

}
