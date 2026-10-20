package com.getddo.core.mission.repository;

import java.util.Optional;
import java.util.UUID;

import com.getddo.core.mission.domain.MissionRewardClaim;

/** 미션 보상 청구 저장소. 사용자·미션당 청구는 한 건만 저장할 수 있다. */
public interface MissionRewardClaimRepository {

	/**
	 * 사용자의 미션 보상 청구를 조회한다.
	 *
	 * @param userId    사용자 ID
	 * @param missionId 미션 ID
	 * @return 청구가 없으면 비어 있다
	 */
	Optional<MissionRewardClaim> findByUserAndMission(UUID userId, UUID missionId);

	/**
	 * 새 청구를 저장한다. 응모권 지급 전에 호출하며, 지급 쪽이 같은 트랜잭션에서 이 청구를 읽는다.
	 * 같은 사용자·미션의 청구가 이미 있으면 유일 제약 위반 예외가 난다. 이 예외가 난 트랜잭션은 롤백 대상이 되므로 호출자는
	 * 그 트랜잭션을 이어 쓰지 않는다. 기존 청구는 {@link #findByUserAndMission}으로 먼저 확인하거나 새 트랜잭션에서 다시 읽는다.
	 *
	 * @param claim ID가 없는 청구
	 * @return ID가 채워진 청구
	 */
	MissionRewardClaim insert(MissionRewardClaim claim);
}
