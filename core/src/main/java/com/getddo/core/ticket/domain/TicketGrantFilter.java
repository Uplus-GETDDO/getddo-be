package com.getddo.core.ticket.domain;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;

/**
 * 관리자 응모권 지급 이력 조회 조건(AO03). 모든 조건은 선택이며 서로 AND로 결합한다. 기간은 지급 시각 기준 {@code [from, to)}다.
 */
@Getter
public final class TicketGrantFilter {

	private static final TicketGrantFilter NONE = new TicketGrantFilter(null, null, null, null, null, null, null);

	private final UUID userId;
	/** 사용자 이름 부분 일치(대소문자 무시). 없으면 null. */
	private final String keyword;
	/** 미션 지급만 좁힌다. 미션이 아닌 지급은 미션이 없으므로 이 조건이 있으면 제외된다. */
	private final UUID missionId;
	private final GrantSourceType sourceType;
	private final TicketGrade grade;
	private final Instant from;
	private final Instant to;

	private TicketGrantFilter(UUID userId, String keyword, UUID missionId, GrantSourceType sourceType,
			TicketGrade grade, Instant from, Instant to) {
		this.userId = userId;
		this.keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
		this.missionId = missionId;
		this.sourceType = sourceType;
		this.grade = grade;
		this.from = from;
		this.to = to;
	}

	/** 조회 조건을 만든다. 값은 검증하지 않으며 기간은 {@link #hasValidPeriod()}로 확인한다. */
	public static TicketGrantFilter of(UUID userId, String keyword, UUID missionId, GrantSourceType sourceType,
			TicketGrade grade, Instant from, Instant to) {
		return new TicketGrantFilter(userId, keyword, missionId, sourceType, grade, from, to);
	}

	/** 조건이 없는 조회 조건. 전체를 조회한다. */
	public static TicketGrantFilter none() {
		return NONE;
	}

	/** 시작과 끝이 모두 있으면 시작이 끝보다 앞서는지 확인한다. */
	public boolean hasValidPeriod() {
		return from == null || to == null || from.isBefore(to);
	}
}
