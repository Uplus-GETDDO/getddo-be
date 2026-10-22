package com.getddo.api.abuse.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.getddo.core.abuse.domain.AbuseCaseSummary;

/** 관리자 목록에 허용된 필드만 응답하며 관련 이벤트의 null도 명시적으로 반환한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AbuseCaseSummaryResponse(UUID id, UUID userId, String userNickname, UUID eventId, String eventTitle,
		String sourceType, String detectionType, String detectionReason, Instant blockedAt, Instant blockedUntil) {

	/** 저장 근거·내부 시각·개인정보를 포함하지 않는 공개 목록 DTO로 변환한다. */
	public static AbuseCaseSummaryResponse from(AbuseCaseSummary summary) {
		return new AbuseCaseSummaryResponse(summary.getId(), summary.getUserId(), summary.getUserNickname(),
				summary.getEventId(), summary.getEventTitle(), summary.getSourceType(), summary.getDetectionType(),
				summary.getDetectionReason(), summary.getBlockedAt(), summary.getBlockedUntil());
	}
}
