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

/** 사용자 상세 응답. 경품 종류·등수·당첨 인원이며 실제 당첨 결과는 제공하지 않는다. */
public record EventDetailResponse(UUID id, String title, String imageUrl, EventType eventType,
		boolean weightingEnabled, MembershipRule membershipRule, Instant startsAt, Instant endsAt,
		EventStatus status, Instant serverTime, String description,
		Integer maxTicketsPerUser, List<PrizeResponse> prizes) {
	public static EventDetailResponse from(EventView event, Instant serverTime) {
		return from(event, serverTime, ignored -> null);
	}

	public static EventDetailResponse from(EventView event, Instant serverTime, Function<String, String> imageUrls) {
		RegisteredEvent details = event.getDetails();
		return new EventDetailResponse(details.getId(), details.getTitle(), imageUrls.apply(details.getImageKey()), details.getEventType(),
				details.isWeightingEnabled(), details.getMembershipRule(), details.getStartsAt(), details.getEndsAt(),
				event.getPublicStatus(), serverTime,
				details.getDescription(), details.getMaxTicketsPerUser(),
				details.getPrizes().stream().map(prize -> PrizeResponse.from(prize, imageUrls)).toList());
	}
}
