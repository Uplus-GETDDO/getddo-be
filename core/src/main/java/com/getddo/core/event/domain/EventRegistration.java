package com.getddo.core.event.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 이벤트·경품 등록과 수정에서 공유하는 불변 설정 입력. 수정에서는 기존 경품 ID를 전달한다. */
@Getter
@EqualsAndHashCode
public final class EventRegistration {
	private final UUID actorId;
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
	private final List<Prize> prizes;

	public EventRegistration(UUID actorId,
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
			List<Prize> prizes) {
		this.actorId = actorId;
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
		// null 목록·원소는 서비스의 업무 검증에서 처리한다. 원본 목록의 변경만 차단한다.
		this.prizes = prizes == null ? null : Collections.unmodifiableList(new ArrayList<>(prizes));
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

		/** 등록·새 경품 추가에서는 저장소가 ID를 발급한다. */
		public Prize(int rank, String name, String description, String imageKey, int winnerCount) {
			this(null, rank, name, description, imageKey, winnerCount);
		}
	}
}
