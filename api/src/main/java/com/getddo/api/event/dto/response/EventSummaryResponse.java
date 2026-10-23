package com.getddo.api.event.dto.response;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;

/** 사용자 목록 응답. 공개 상태를 제공하며 확정되지 않은 발표 예정 시각은 노출하지 않는다. */
public record EventSummaryResponse(UUID id, String title, String imageUrl, EventType eventType,
		boolean weightingEnabled, MembershipRule membershipRule, Instant startsAt, Instant endsAt,
		EventStatus status, Instant serverTime, boolean myEntered, long winnerCount) {
	public static EventSummaryResponse from(EventView event, Instant serverTime) {
		return from(event, serverTime, ignored -> null);
	}

	public static EventSummaryResponse from(EventView event, Instant serverTime, Function<String, String> imageUrls) {
		RegisteredEvent details = event.getDetails();
		return new EventSummaryResponse(details.getId(), details.getTitle(), imageUrls.apply(details.getImageKey()), details.getEventType(),
				details.isWeightingEnabled(), details.getMembershipRule(), details.getStartsAt(), details.getEndsAt(),
				event.getPublicStatus(), serverTime, event.isMyEntered(), event.getWinnerCount());
	}
}
