package com.getddo.api.drawing.controller;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.drawing.dto.DrawPublicationChangeResponse;
import com.getddo.api.drawing.dto.DrawPublicationResponse;
import com.getddo.api.drawing.dto.DrawResultConfirmationResponse;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.drawing.service.DrawPublicationQueryService;
import com.getddo.core.drawing.service.DrawResultConfirmationService;
import com.getddo.core.user.domain.User;

/** 승인된 AD06·AD07 및 최초 공개 전 관리자 확인 API. 권한·단계·트랜잭션은 서비스가 검사한다. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminDrawPublicationController {
	private final DrawResultConfirmationService confirmations;
	private final DrawPublicationQueryService queries;

	/** 확인된 재추첨을 반영하고 실제 최신 공개 버전을 응답한다. */
	@PostMapping("/draws/{runId}/publication")
	public ResponseEnvelope<DrawPublicationResponse> publish(@PathVariable UUID runId,
			@CurrentUser(membershipRequired = false) User actor) {
		return ResponseEnvelope.success(DrawPublicationResponse.from(confirmations.confirmForPublication(runId,
				actor.getId())));
	}

	/** 검토한 최신 최초·재추첨 실행으로 최종 명단을 한 번에 확인·발표한다. */
	@PostMapping("/draws/{runId}/result-confirmation")
	public ResponseEnvelope<DrawResultConfirmationResponse> confirm(@PathVariable UUID runId,
			@CurrentUser(membershipRequired = false) User actor) {
		return ResponseEnvelope.success(DrawResultConfirmationResponse.from(confirmations.confirmBeforeInitial(runId,
				actor.getId())));
	}

	/** 실제 공개 버전 간 변경 이력을 페이지 응답으로 변환한다. */
	@GetMapping("/events/{eventId}/result-changes")
	public ResponseEnvelope<PageResult<DrawPublicationChangeResponse>> changes(@PathVariable UUID eventId,
			@CurrentUser(membershipRequired = false) User actor,
			@RequestParam(defaultValue = "1") @Min(1) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEnvelope.success(queries.changes(eventId, actor.getId(), new PageQuery(page, size))
				.map(DrawPublicationChangeResponse::from));
	}
}
