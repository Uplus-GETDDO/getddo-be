package com.getddo.db.attendance.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.getddo.db.attendance.entity.StreakPolicyEntity;

public interface StreakPolicyJpaRepository extends JpaRepository<StreakPolicyEntity, UUID> {

	List<StreakPolicyEntity> findByPolicySetIdIn(Collection<UUID> policySetIds);

	/** 묶음의 단계를 모두 지운다. 새 단계를 저장하기 전에 즉시 반영해 (묶음, 일수) UNIQUE와 부딪히지 않게 한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from StreakPolicyEntity s where s.policySetId = :setId")
	int deleteByPolicySetId(@Param("setId") UUID setId);
}
