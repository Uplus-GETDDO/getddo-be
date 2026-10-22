package com.getddo.api.drawing.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationConfirmation;
import com.getddo.core.drawing.domain.DrawResultConfirmation;

/** 확인 저장과 실제 공개 여부를 구분한다. 미공개이면 공개 식별자·버전·시각은 null이다. */
public record DrawResultConfirmationResponse(UUID eventId, UUID confirmationId, UUID runId,
		UUID confirmedBy, String confirmedByName, Instant confirmedAt, boolean isPublished, UUID publicationId, Integer revision,
		Instant publishedAt) {
	/** 관리자 확인과 실제 공개 여부를 구분하고 미공개 필드는 null로 응답한다. */
	public static DrawResultConfirmationResponse from(DrawPublicationConfirmation result, Map<UUID, String> names) {
		DrawResultConfirmation confirmation = result.getConfirmation();
		DrawPublication publication = result.getPublication();
		return new DrawResultConfirmationResponse(result.getEventId(), confirmation.getId(), confirmation.getRunId(),
				confirmation.getConfirmedBy(),
				confirmation.getConfirmedBy() == null ? null : names.get(confirmation.getConfirmedBy()),
				confirmation.getConfirmedAt(), publication != null,
				publication == null ? null : publication.getId(), publication == null ? null : publication.getRevision(),
				publication == null ? null : publication.getPublishedAt());
	}
}
