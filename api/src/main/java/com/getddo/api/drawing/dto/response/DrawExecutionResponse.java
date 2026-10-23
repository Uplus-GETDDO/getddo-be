package com.getddo.api.drawing.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawExecutionResult;
import com.getddo.core.drawing.domain.DrawRunStatus;

/** 실행 상태와 확정 시 저장 결과만 제공한다. 실패 원문은 노출하지 않는다. */
public record DrawExecutionResponse(UUID runId, DrawRunStatus status, Confirmation confirmation) {
	/** 내부 실행 결과를 HTTP 응답으로 변환한다. 확정 시에만 저장된 결과를 포함하며 실패 예외 원문을 노출하지 않는다. */
	public static DrawExecutionResponse from(DrawExecutionResult result, Map<UUID, String> names) {
		Confirmation confirmation = result.getConfirmation() == null ? null : new Confirmation(
				result.getConfirmation().getConfirmedAt(), result.getConfirmation().getResults().stream().map(stored -> new Result(
				stored.getId(), stored.getSelection().getPrizeId(), stored.getSelection().getRank(), stored.getSelection().getSlotNumber(),
				stored.getSelection().getSelectionOrder(), stored.getSelection().getCandidateId(), stored.getSelection().getUserId(),
				stored.getSelection().getUserId() == null ? null : names.get(stored.getSelection().getUserId()),
				stored.getSelection().getResultType().name())).toList());
		return new DrawExecutionResponse(result.getRunId(), result.getStatus(), confirmation);
	}
	public record Confirmation(Instant confirmedAt, List<Result> results) {
		public Confirmation {
			results = List.copyOf(results);
		}
	}
	public record Result(UUID resultId, UUID prizeId, int rank, int slotNumber, Integer selectionOrder,
			UUID candidateId, UUID userId, String userName, String resultType) {
	}
}
