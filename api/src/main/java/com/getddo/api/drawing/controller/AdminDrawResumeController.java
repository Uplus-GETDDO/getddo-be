package com.getddo.api.drawing.controller;

import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.drawing.dto.response.DrawExecutionResponse;
import com.getddo.core.drawing.domain.DrawExecutionResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.service.DrawAdminNameService;
import com.getddo.core.drawing.service.DrawExecutionService;
import com.getddo.core.user.domain.User;

/** 최초 실행 재개만 제공하며 AD05·AD09는 GD-138 책임이다. */
@RestController
@RequestMapping("/api/v1/admin/draws")
@RequiredArgsConstructor
public class AdminDrawResumeController {
	private final DrawAdminNameService names;
	private final DrawExecutionService executions;
	/** 최초 추첨의 동일 실행 재개를 서비스에 위임하고, CONFIRMED·NO_ENTRIES 정상 종료는 200·그 밖의 실행 상태는 202로 반환한다. 실행 종류와 관리자 권한은 서비스에서 검증한다. */
	@PostMapping("/{runId}/resume")
	public ResponseEntity<ResponseEnvelope<DrawExecutionResponse>> resume(@PathVariable UUID runId,
			@CurrentUser User actor) {
		DrawExecutionResult result = executions.resumeInitial(runId, actor.getId());
		HttpStatus status = (result.getStatus() == DrawRunStatus.CONFIRMED || result.getStatus() == DrawRunStatus.NO_ENTRIES)
		? HttpStatus.OK : HttpStatus.ACCEPTED;
		return ResponseEntity.status(status).body(ResponseEnvelope.success(DrawExecutionResponse.from(result, names.forExecution(result))));
	}
}
