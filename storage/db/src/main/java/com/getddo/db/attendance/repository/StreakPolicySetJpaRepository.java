package com.getddo.db.attendance.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.getddo.db.attendance.entity.StreakPolicySetEntity;

public interface StreakPolicySetJpaRepository extends JpaRepository<StreakPolicySetEntity, UUID> {

	Page<StreakPolicySetEntity> findAllBy(Pageable pageable);

	boolean existsByEffectiveMonth(LocalDate effectiveMonth);

	/** 묶음 한 건을 잠가 읽는다. 수정 중 같은 묶음을 동시에 고치지 못하게 한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from StreakPolicySetEntity s where s.id = :id")
	Optional<StreakPolicySetEntity> findByIdForUpdate(@Param("id") UUID id);
}
