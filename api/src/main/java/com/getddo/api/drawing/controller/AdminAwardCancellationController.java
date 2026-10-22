package com.getddo.api.drawing.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.drawing.dto.request.AwardCancellationRequest;
import com.getddo.api.drawing.dto.response.AwardCancellationResponse;
import com.getddo.api.drawing.dto.response.DrawExecutionResponse;
import com.getddo.core.drawing.domain.AwardCancellationReceipt;
import com.getddo.core.drawing.domain.DrawExecutionResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.service.AwardCancellationService;
import com.getddo.core.drawing.service.DrawAdminNameService;
import com.getddo.core.user.domain.User;

/** 승인된 AD05·AD09 진입점. 업무 트랜잭션과 실행 상태 처리는 서비스에 위임한다. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAwardCancellationController {
	private final DrawAdminNameService names;
	private final AwardCancellationService service;

	@PostMapping("/wins/{winId}/cancellations")
	public ResponseEntity<ResponseEnvelope<AwardCancellationResponse>> cancel(
			@CurrentUser(membershipRequired = false) User actor, @PathVariable UUID winId,
			@RequestHeader("Idempotency-Key") UUID requestKey, @Valid @RequestBody AwardCancellationRequest request) {
		AwardCancellationReceipt receipt = service.cancel(actor.getId(), winId, requestKey, request.reason());
		DrawRunStatus status = receipt.execution().getStatus();
		return ResponseEntity.status(status == DrawRunStatus.CONFIRMED ? 200 : 202)
				.body(ResponseEnvelope.success(AwardCancellationResponse.from(receipt.cancellation(), status)));
	}

	@PostMapping("/cancellations/{cancellationId}/redraws")
	public ResponseEntity<ResponseEnvelope<DrawExecutionResponse>> startOrResume(
			@CurrentUser(membershipRequired = false) User actor, @PathVariable UUID cancellationId) {
		DrawExecutionResult execution = service.startOrResume(actor.getId(), cancellationId);
		return ResponseEntity.status(execution.getStatus() == DrawRunStatus.CONFIRMED ? 200 : 202)
				.body(ResponseEnvelope.success(DrawExecutionResponse.from(execution, names.forExecution(execution))));
	}
}
