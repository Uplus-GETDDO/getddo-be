package com.getddo.core.event.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 등록·조회에서 사용하는 저장된 이벤트와 경품 정보. 관리자용 자동 추첨 기준 시각을 제공한다. */
@Getter
@EqualsAndHashCode
public final class RegisteredEvent {
	private static final Duration DRAW_DELAY = Duration.ofMinutes(1);

	private final UUID id;
	private final String title;
	private final String description;
	private final String imageKey;
	private final String detailImageKey;
	private final EventType eventType;
	private final boolean weightingEnabled;
	private final Integer maxTicketsPerUser;
	private final MembershipRule membershipRule;
	private final Instant startsAt;
	private final Instant endsAt;
	private final EventStatus status;
	private final Instant createdAt;
	private final Instant updatedAt;
	private final List<Prize> prizes;

	public RegisteredEvent(UUID id,
			String title,
			String description,
			String imageKey,
			String detailImageKey,
			EventType eventType,
			boolean weightingEnabled,
			Integer maxTicketsPerUser,
			MembershipRule membershipRule,
			Instant startsAt,
			Instant endsAt,
			EventStatus status,
			Instant createdAt,
			Instant updatedAt,
			List<Prize> prizes) {
		this.id = id;
		this.title = title;
		this.description = description;
		this.imageKey = imageKey;
		this.detailImageKey = detailImageKey;
		this.eventType = eventType;
		this.weightingEnabled = weightingEnabled;
		this.maxTicketsPerUser = maxTicketsPerUser;
		this.membershipRule = membershipRule;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.status = status;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.prizes = List.copyOf(prizes);
	}

	/** 마감 1분 후의 최초 자동 추첨 기준이다. 실제 실행·관리자 확인·수동 공개 시각은 별도다. */
	public Instant getDrawScheduledAt() {
		return endsAt.plus(DRAW_DELAY);
	}

	@Getter
	@RequiredArgsConstructor
	@EqualsAndHashCode
	public static final class Prize {
		private final UUID id;
		private final int rank;
		private final String name;
		private final String description;
		private final String imageKey;
		private final int winnerCount;
	}
}
