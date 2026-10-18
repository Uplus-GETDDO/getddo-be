package com.getddo.db.attendance.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.getddo.db.attendance.entity.StreakPolicyEntity;

public interface StreakPolicyJpaRepository extends JpaRepository<StreakPolicyEntity, UUID> {

	List<StreakPolicyEntity> findByPolicySetIdIn(Collection<UUID> policySetIds);
}
