package com.getddo.core.mission.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;

/**
 * 서비스 공통 미션. 특정 이벤트에 속하지 않으며 운영 시작·종료 시각 안에서만 제출할 수 있다.
 *
 * <p>운영 중인 미션의 문항·조건·보상은 바꾸지 않으므로 값을 바꾸는 메서드를 두지 않는다. 변경은 새 미션을 등록한다.</p>
 */
@Getter
public final class Mission {

	public static final int MAX_TITLE_LENGTH = 200;
	public static final int MAX_IMAGE_KEY_LENGTH = 500;

	/** 저장 전에는 null. */
	private final UUID id;
	private final UUID rewardPolicyId;
	private final UUID createdBy;
	private final String title;
	private final String description;
	private final MissionType missionType;
	private final Instant startsAt;
	private final Instant endsAt;
	private final MissionStatus status;
	/** 설명 이미지 한 개의 저장소 경로. 없으면 null. */
	private final String imageKey;
	/** 저장 전에는 null. */
	private final Instant createdAt;
	/** 저장 전에는 null. */
	private final Instant updatedAt;

	public Mission(UUID id, UUID rewardPolicyId, UUID createdBy, String title, String description,
			MissionType missionType, Instant startsAt, Instant endsAt, MissionStatus status, String imageKey,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.rewardPolicyId = Objects.requireNonNull(rewardPolicyId, "rewardPolicyId");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
		this.title = Objects.requireNonNull(title, "title");
		this.description = Objects.requireNonNull(description, "description");
		this.missionType = Objects.requireNonNull(missionType, "missionType");
		this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
		this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
		// 저장소가 복원하는 경로에서도 DB 제약(chk_mission_dates, 컬럼 길이)과 같은 규칙을 지킨다.
		MissionRules.definition(endsAt.isAfter(startsAt));
		MissionRules.definition(title.length() <= MAX_TITLE_LENGTH);
		MissionRules.definition(imageKey == null || imageKey.length() <= MAX_IMAGE_KEY_LENGTH);
		this.status = Objects.requireNonNull(status, "status");
		this.imageKey = imageKey;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	/**
	 * 새 미션을 운영 상태로 등록한다.
	 *
	 * @throws com.getddo.core.mission.exception.MissionException 제목이 비었거나 200자를 넘을 때, 설명이 비었을 때,
	 *         종료 시각이 시작 시각 뒤가 아닐 때, 이미지 경로가 500자를 넘을 때
	 */
	public static Mission register(UUID rewardPolicyId, UUID createdBy, String title, String description,
			MissionType missionType, Instant startsAt, Instant endsAt, String imageKey) {
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(startsAt, "startsAt");
		Objects.requireNonNull(endsAt, "endsAt");
		MissionRules.registrable(title, description, startsAt, endsAt, imageKey);
		return new Mission(null, rewardPolicyId, createdBy, title, description, missionType, startsAt, endsAt,
				MissionStatus.ACTIVE, imageKey, null, null);
	}

	/**
	 * 서버가 제출을 <b>받는 순간</b> 새 제출을 받아도 되는지 확인한다. 기간 안이고 운영 중일 때만 참이다.
	 *
	 * <p>도착 시점에 한 번만 호출한다. 기간 안에 도착한 제출은 검증이 종료 뒤에 끝나도 달성·보상을 반영해야 하므로, 이미
	 * 받은 제출을 이어서 처리할 때는 미션 상태가 종료로 바뀌었을 수 있는 이 메서드 대신 도착 시각으로 {@link #isWithinPeriod}를
	 * 쓴다.</p>
	 */
	public boolean acceptsSubmissionAt(Instant receivedAt) {
		return status == MissionStatus.ACTIVE && isWithinPeriod(receivedAt);
	}

	/**
	 * 도착 시각이 운영 기간 안인지 확인한다. 운영 상태는 보지 않는다. 시작 시각 이상, 종료 시각 미만이며 정확히 종료 시각에
	 * 도착한 제출은 기간 밖이다.
	 */
	public boolean isWithinPeriod(Instant receivedAt) {
		return !receivedAt.isBefore(startsAt) && receivedAt.isBefore(endsAt);
	}
}
