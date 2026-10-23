package com.getddo.api.event.dto.request;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.getddo.core.event.domain.EventRegistration;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.MembershipRule;

public record EventWriteRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank String description,
		@Size(max = 500) String imageKey,
		@Size(max = 500) String detailImageKey,
		@NotNull EventType eventType,
		@NotNull Boolean weightingEnabled,
		Integer maxTicketsPerUser,
		@NotNull MembershipRule membershipRule,
		@NotNull OffsetDateTime startsAt,
		@NotNull OffsetDateTime endsAt,
		@NotEmpty List<@NotNull @Valid PrizeWrite> prizes
) {
	/** 등록 요청을 변환한다. 기존 경품 ID의 등록 허용 여부는 Service에서 검증한다. */
	public EventRegistration toRegistration(UUID actorId) {
		return toModification(actorId);
	}

	/** 수정에서는 기존 경품 ID를 보존하고 새 경품만 ID 없이 전달한다. */
	public EventRegistration toModification(UUID actorId) {
		return new EventRegistration(actorId, title, description, imageKey, detailImageKey, eventType,
				Boolean.TRUE.equals(weightingEnabled), maxTicketsPerUser, membershipRule,
				startsAt == null ? null : startsAt.toInstant(), endsAt == null ? null : endsAt.toInstant(),
				prizes == null ? null : prizes.stream().map(prize -> prize == null ? null : prize.toPrize()).toList());
	}

	public record PrizeWrite(
			UUID id,
			@Positive int rank,
			@NotBlank @Size(max = 200) String name,
			String description,
			@Size(max = 500) String imageKey,
			@Positive int winnerCount
	) {
		EventRegistration.Prize toPrize() {
			return new EventRegistration.Prize(id, rank, name, description, imageKey, winnerCount);
		}
	}
}
