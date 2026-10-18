package com.getddo.db.attendance.repository;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.getddo.db.attendance.entity.StreakPolicySetEntity;

public interface StreakPolicySetJpaRepository extends JpaRepository<StreakPolicySetEntity, UUID> {

	Page<StreakPolicySetEntity> findAllBy(Pageable pageable);

	boolean existsByEffectiveMonth(LocalDate effectiveMonth);
}
