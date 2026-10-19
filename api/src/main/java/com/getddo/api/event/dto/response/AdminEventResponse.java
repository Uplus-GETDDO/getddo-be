package com.getddo.api.event.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;

/** 관리자 등록·조회·수정 응답. 실제 DB 상태와 이미지 키·운영 시각을 제공한다. */
public record AdminEventResponse(
		UUID id,
		String title,
		String imageUrl,
		EventType eventType,
		boolean weightingEnabled,
		MembershipRule membershipRule,
		Instant startsAt,
		Instant endsAt,
		EventStatus status,
		Instant drawScheduledAt,
		Instant serverTime,
		String description,
		Integer maxTicketsPerUser,
		List<PrizeResponse> prizes,
		String imageKey,
		Instant createdAt,
		Instant updatedAt,
		Instant canceledAt,
		List<PrizeImage> prizeImages
) {
	public static AdminEventResponse from(RegisteredEvent event, Instant serverTime) {
		return from(event, serverTime, null, ignored -> null);
	}

	public static AdminEventResponse from(RegisteredEvent event, Instant serverTime, Function<String, String> imageUrls) {
		return from(event, serverTime, null, imageUrls);
	}

	public static AdminEventResponse from(EventView event, Instant serverTime) {
		return from(event, serverTime, ignored -> null);
	}

	public static AdminEventResponse from(EventView event, Instant serverTime, Function<String, String> imageUrls) {
		return from(event.getDetails(), serverTime, event.getCanceledAt(), imageUrls);
	}

	private static AdminEventResponse from(RegisteredEvent event, Instant serverTime,
			Instant canceledAt, Function<String, String> imageUrls) {
		return new AdminEventResponse(event.getId(), event.getTitle(), imageUrls.apply(event.getImageKey()), event.getEventType(), event.isWeightingEnabled(),
				event.getMembershipRule(), event.getStartsAt(), event.getEndsAt(), event.getStatus(),
				event.getDrawScheduledAt(), serverTime,
				event.getDescription(), event.getMaxTicketsPerUser(), event.getPrizes().stream().map(prize -> PrizeResponse.from(prize, imageUrls)).toList(),
				event.getImageKey(), event.getCreatedAt(), event.getUpdatedAt(),
				canceledAt, event.getPrizes().stream().map(PrizeImage::from).toList());
	}

	public record PrizeImage(UUID prizeId, String imageKey) {
		public static PrizeImage from(RegisteredEvent.Prize prize) {
			return new PrizeImage(prize.getId(), prize.getImageKey());
		}
	}
}
