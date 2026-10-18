package com.getddo.db.mission.repository;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.getddo.core.mission.repository.MissionRewardPolicyRepository;
import com.getddo.db.mission.entity.MissionRewardPolicyEntity;

@Repository
@RequiredArgsConstructor
public class MissionRewardPolicyRepositoryImpl implements MissionRewardPolicyRepository {

	private final MissionRewardPolicyJpaRepository jpaRepository;

	@Override
	public UUID createFixedPolicy(UUID createdBy, Instant effectiveFrom) {
		return jpaRepository.saveAndFlush(new MissionRewardPolicyEntity(createdBy, effectiveFrom)).getId();
	}
}
