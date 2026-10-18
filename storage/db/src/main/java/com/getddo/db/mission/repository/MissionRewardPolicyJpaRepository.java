package com.getddo.db.mission.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.getddo.db.mission.entity.MissionRewardPolicyEntity;

public interface MissionRewardPolicyJpaRepository extends JpaRepository<MissionRewardPolicyEntity, UUID> {
}
