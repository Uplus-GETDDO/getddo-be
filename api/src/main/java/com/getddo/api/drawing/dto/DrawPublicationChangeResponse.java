package com.getddo.api.drawing.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationChange;

/** 실제 공개 버전 간 변경만 제공한다. 취소 여부나 미확인 결과 조회를 대신하지 않는다. */
public record DrawPublicationChangeResponse(UUID id, UUID publicationId, int revision, UUID drawRunId,
		String reason, UUID confirmedBy, String confirmedByName, Instant confirmedAt, Instant updatedAt,
		List<SlotChange> changes, List<Execution> executions) {
	/** 실제 공개 버전과 실행별 근거를 기존 변경 이력 필드에 대응시킨다. */
	public static DrawPublicationChangeResponse from(DrawPublicationChange change, Map<UUID, String> names) {
		DrawPublication publication = change.getPublication();
		return new DrawPublicationChangeResponse(publication.getId(), publication.getId(), publication.getRevision(),
				publication.getSourceDrawId(), publication.getReason(), publication.getConfirmedBy(),
				publication.getConfirmedBy() == null ? null : names.get(publication.getConfirmedBy()),
				publication.getConfirmedAt(), publication.getPublishedAt(),
				change.getChanges().stream().map(slot -> SlotChange.from(slot, names)).toList(),
				change.getExecutions().stream().map(execution -> Execution.from(execution, names)).toList());
	}

	public record Execution(UUID runId, UUID confirmationId, UUID confirmedBy, String confirmedByName, Instant confirmedAt,
			List<Cancellation> cancellations) {
		/** 한 실행의 확인 근거와 연결 취소를 함께 변환한다. */
		static Execution from(DrawPublication.Execution execution, Map<UUID, String> names) {
			return new Execution(execution.getRunId(), execution.getConfirmationId(), execution.getConfirmedBy(),
					execution.getConfirmedBy() == null ? null : names.get(execution.getConfirmedBy()),
					execution.getConfirmedAt(),
					execution.getCancellations().stream().map(Cancellation::from).toList());
		}
	}
	public record Cancellation(UUID id, UUID resultId, String reason) {
		/** 공개 근거에 필요한 취소 식별자와 사유만 반환한다. */
		static Cancellation from(DrawPublication.Cancellation cancellation) {
			return new Cancellation(cancellation.getId(), cancellation.getResultId(), cancellation.getReason());
		}
	}

	public record SlotChange(UUID prizeId, int slotNumber, UUID beforeUserId, String beforeUserName,
			UUID afterUserId, String afterUserName) {
		/** 경품 자리의 공개 전후 사용자만 응답 값으로 변환한다. */
		static SlotChange from(DrawPublicationChange.SlotChange change, Map<UUID, String> names) {
			return new SlotChange(change.getPrizeId(), change.getSlotNumber(), change.getBeforeUserId(),
					change.getBeforeUserId() == null ? null : names.get(change.getBeforeUserId()),
					change.getAfterUserId(), change.getAfterUserId() == null ? null : names.get(change.getAfterUserId()));
		}
	}
}
