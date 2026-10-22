package com.getddo.api.abuse.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.getddo.core.abuse.domain.AbuseCaseDetail;

/** 목록의 열 필드와 관리자 전용 evidence를 공용 계약의 평탄한 구조로 반환한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AbuseCaseDetailResponse(UUID id, UUID userId, String userNickname, UUID eventId, String eventTitle,
		String sourceType, String detectionType, String detectionReason, Instant blockedAt, Instant blockedUntil,
		AbuseEvidenceResponse evidence) {

	/** 목록 변환을 재사용하고 원문 JSON·검토 필드·현재 차단 여부를 추가하지 않는다. */
	public static AbuseCaseDetailResponse from(AbuseCaseDetail detail) {
		AbuseCaseSummaryResponse summary = AbuseCaseSummaryResponse.from(detail.getSummary());
		return new AbuseCaseDetailResponse(summary.id(), summary.userId(), summary.userNickname(), summary.eventId(),
				summary.eventTitle(), summary.sourceType(), summary.detectionType(), summary.detectionReason(),
				summary.blockedAt(), summary.blockedUntil(), AbuseEvidenceResponse.from(detail.getEvidence()));
	}
}
