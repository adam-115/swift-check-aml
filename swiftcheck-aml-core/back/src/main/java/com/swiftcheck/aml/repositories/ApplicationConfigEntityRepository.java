package com.swiftcheck.aml.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;

@Repository
public interface ApplicationConfigEntityRepository extends JpaRepository<ApplicationConfigEntity, Long> {

}
