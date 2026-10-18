package com.getddo.core.mission.repository;

import java.time.Instant;
import java.util.UUID;

/** 미션 보상 정책 저장소. 호출자 트랜잭션 안에서 호출한다. */
public interface MissionRewardPolicyRepository {

	/**
	 * 미션 한 건이 쓸 보상 정책을 저장하고 즉시 DB에 반영한다. 미션 완료 보상은 등급 응모권 1장으로 고정이라 지급 수량은 1이다.
	 *
	 * @param effectiveFrom 정책 적용 시작 시각. 미션 운영 시작 시각을 쓴다
	 * @return 저장한 정책 ID
	 */
	UUID createFixedPolicy(UUID createdBy, Instant effectiveFrom);
}
