package com.getddo.api.drawing.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawPublication;

/** 실제 저장된 최신 공개 버전이다. drawRunId는 이 버전의 원본이며 요청 실행과 항상 같지는 않다. */
public record DrawPublicationResponse(UUID eventId, UUID publicationId, UUID drawRunId, int revision,
		Instant publishedAt, Instant updatedAt, UUID updatedBy, String updatedByName) {
	/** 실제 공개 버전의 식별자·확인자·시각을 응답 값으로 변환한다. */
	public static DrawPublicationResponse from(DrawPublication publication, Map<UUID, String> names) {
		return new DrawPublicationResponse(publication.getEventId(), publication.getId(), publication.getSourceDrawId(),
				publication.getRevision(), publication.getPublishedAt(), publication.getPublishedAt(), publication.getConfirmedBy(),
				publication.getConfirmedBy() == null ? null : names.get(publication.getConfirmedBy()));
	}
}
