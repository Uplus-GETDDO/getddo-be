package com.getddo.core.mission.domain;

import java.time.Instant;

import lombok.Getter;

/**
 * 미션 목록 조회 조건. 값이 없는 조건은 적용하지 않는다.
 */
@Getter
public final class MissionSearch {

	private final MissionType missionType;
	private final MissionStatus status;
	/** 제목에 포함된 문자열. 대소문자를 구분하지 않는다. */
	private final String keyword;

	/** 운영 시작 시각이 {@code [startsFrom, startsBefore)}인 미션만. 값이 없으면 제한하지 않는다. */
	private final Instant startsFrom;
	private final Instant startsBefore;

	public MissionSearch(MissionType missionType, MissionStatus status, String keyword) {
		this(missionType, status, keyword, null, null);
	}

	/**
	 * @throws IllegalArgumentException 기간의 시작이 끝보다 늦거나 같은 경우
	 */
	public MissionSearch(MissionType missionType, MissionStatus status, String keyword, Instant startsFrom,
			Instant startsBefore) {
		if (startsFrom != null && startsBefore != null && !startsFrom.isBefore(startsBefore)) {
			throw new IllegalArgumentException("startsFrom must be before startsBefore");
		}
		this.missionType = missionType;
		this.status = status;
		this.keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
		this.startsFrom = startsFrom;
		this.startsBefore = startsBefore;
	}

	/** 모든 미션을 조회하는 조건. */
	public static MissionSearch all() {
		return new MissionSearch(null, null, null);
	}
}
