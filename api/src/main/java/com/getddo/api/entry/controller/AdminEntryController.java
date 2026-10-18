package com.getddo.api.entry.controller;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.entry.dto.response.AdminEntryResponse;
import com.getddo.api.entry.dto.response.AdminParticipantResponse;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.AdminEntryFilter;
import com.getddo.core.entry.domain.EntryStatus;
import com.getddo.core.entry.service.AdminEntryQueryService;
import com.getddo.core.user.domain.User;

/** AO06·AO07 관리자 응모 내역과 이벤트 응모자 조회. 읽기 전용이다. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminEntryController {

	private final AdminEntryQueryService service;

	/**
	 * AO06 모든 사용자의 응모 내역(접수·거절)을 결과 기록 시각 최신순으로 조회한다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 * @param userId 사용자 필터. 생략하면 전체
	 * @param eventId 이벤트 필터. 생략하면 전체
	 * @param status 처리 결과 필터. 생략하면 접수와 거절 모두
	 * @param from 시작 시각(포함). 생략하면 제한 없음
	 * @param to 끝 시각(제외). 생략하면 제한 없음
	 */
	@GetMapping("/entries")
	public ResponseEnvelope<PageResult<AdminEntryResponse>> entries(
			@CurrentUser(membershipRequired = false) User user,
			@RequestParam(defaultValue = "1") @Min(1) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@RequestParam(required = false) UUID userId,
			@RequestParam(required = false) UUID eventId,
			@RequestParam(required = false) EntryStatus status,
			@Parameter(description = "시작 시각(포함). 시간대가 필요하다. UTC는 Z로 쓰고, 오프셋의 +는 %2B로 인코딩한다.",
					example = "2026-09-01T00:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
			@Parameter(description = "끝 시각(제외). 시간대가 필요하다. UTC는 Z로 쓰고, 오프셋의 +는 %2B로 인코딩한다.",
					example = "2026-10-01T00:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
		if (from != null && to != null && !from.toInstant().isBefore(to.toInstant())) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
		AdminEntryFilter filter = new AdminEntryFilter(userId, eventId, status, from == null ? null : from.toInstant(),
				to == null ? null : to.toInstant());
		return ResponseEnvelope.success(
				service.findEntries(user, filter, new PageQuery(page, size)).map(AdminEntryResponse::from));
	}

	/**
	 * AO07 한 이벤트의 응모자를 최초 응모 시각 최신순으로 조회한다. 없는 이벤트는 404 {@code ENTRY-004}다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 * @param eventId 조회할 이벤트
	 */
	@GetMapping("/events/{eventId}/participants")
	public ResponseEnvelope<PageResult<AdminParticipantResponse>> participants(
			@CurrentUser(membershipRequired = false) User user,
			@PathVariable UUID eventId,
			@RequestParam(defaultValue = "1") @Min(1) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEnvelope.success(service.findParticipants(user, eventId, new PageQuery(page, size))
				.map(AdminParticipantResponse::from));
	}
}
