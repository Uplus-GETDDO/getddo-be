package com.getddo.api.abuse.controller;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.abuse.dto.response.AbuseCaseSummaryResponse;
import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.core.abuse.domain.AbuseCaseFilter;
import com.getddo.core.abuse.domain.AbuseSourceType;
import com.getddo.core.abuse.service.AbuseCaseQueryService;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.User;

/** AR01 관리자 차단 이력 목록의 입력 해석과 공개 응답 변환을 담당한다. */
@RestController
@RequestMapping("/api/v1/admin/abuse-cases")
@RequiredArgsConstructor
public class AdminAbuseCaseController {

	private final AbuseCaseQueryService service;

	/** 페이지·필터 값 검증은 COMMON-002, 바인딩 실패는 기존 공통 COMMON-005를 따른다. */
	@Operation(summary = "AR01 어뷰징 차단 이력 목록", description = "활성 ADMIN 전용. 만료된 차단을 포함하며 차단 시작 시각 최신순으로 조회합니다.")
	@GetMapping
	public ResponseEnvelope<PageResult<AbuseCaseSummaryResponse>> list(
			@CurrentUser(membershipRequired = false) User actor,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) UUID userId,
			@RequestParam(required = false) UUID eventId,
			@Parameter(description = "요청 기능. 현재 ENTRY만 지원합니다.")
			@RequestParam(required = false) String sourceType,
			@Parameter(description = "차단 시작 시각의 하한(포함). 시간대가 필요합니다.", example = "2026-10-21T00:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
			@Parameter(description = "차단 시작 시각의 상한(제외). 시간대가 필요합니다.", example = "2026-10-22T00:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
		if (page < 1 || size < 1 || size > 100
				|| (sourceType != null && !AbuseSourceType.ENTRY.name().equals(sourceType))
				|| (from != null && to != null && !from.toInstant().isBefore(to.toInstant()))) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
		AbuseCaseFilter filter = new AbuseCaseFilter(userId, eventId,
				sourceType == null ? null : AbuseSourceType.ENTRY,
				from == null ? null : from.toInstant(), to == null ? null : to.toInstant());
		return ResponseEnvelope.success(service.findAll(actor, filter, new PageQuery(page, size))
				.map(AbuseCaseSummaryResponse::from));
	}
}
