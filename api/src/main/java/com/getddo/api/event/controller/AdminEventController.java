package com.getddo.api.event.controller;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.event.config.EventImageUrls;
import com.getddo.api.event.dto.request.AdminEventQueryRequest;
import com.getddo.api.event.dto.request.EventWriteRequest;
import com.getddo.api.event.dto.request.EventCancellationRequest;
import com.getddo.api.event.dto.response.EventCancellationResponse;
import com.getddo.api.event.dto.response.AdminEventResponse;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.service.EventQueryService;
import com.getddo.core.event.service.EventRegistrationService;
import com.getddo.core.event.service.EventModificationService;
import com.getddo.core.event.service.EventDeletionService;
import com.getddo.core.event.service.EventCancellationService;
import com.getddo.core.user.domain.User;

/** 관리자 이벤트 CRUD API. 입력 형식과 응답 변환을 맡고 권한·업무 규칙은 서비스에 위임한다. */
@RestController
@RequestMapping("/api/v1/admin/events")
public class AdminEventController {
	private final EventRegistrationService registrationService;
	private final EventModificationService modificationService;
	private final EventDeletionService deletionService;
	private final EventCancellationService cancellationService;
	private final EventQueryService queryService;
	private final TimeProvider timeProvider;
	private final EventImageUrls imageUrls;

	public AdminEventController(EventRegistrationService registrationService, EventModificationService modificationService,
			EventDeletionService deletionService, EventCancellationService cancellationService, EventQueryService queryService,
			TimeProvider timeProvider, EventImageUrls imageUrls) {
		this.registrationService = registrationService;
		this.modificationService = modificationService;
		this.deletionService = deletionService;
		this.cancellationService = cancellationService;
		this.queryService = queryService;
		this.timeProvider = timeProvider;
		this.imageUrls = imageUrls;
	}

	/** 이벤트와 경품을 함께 등록하고 저장된 정보와 자동 추첨 기준 시각을 반환한다. */
	@PostMapping
	public ResponseEntity<ResponseEnvelope<AdminEventResponse>> register(
			@CurrentUser User user,
			@Valid @RequestBody EventWriteRequest request) {
		AdminEventResponse response = AdminEventResponse.from(
				registrationService.register(request.toRegistration(user.getId())), timeProvider.now(), imageUrls::resolve);
		return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
	}

	/** AE04: 수정 가능한 전체 설정과 경품 목록을 전달한다. 수정 제한·알림 연동은 서비스에서 처리한다. */
	@PutMapping("/{eventId}")
	public ResponseEnvelope<AdminEventResponse> modify(@CurrentUser User user, @PathVariable UUID eventId,
			@Valid @RequestBody EventWriteRequest request) {
		return ResponseEnvelope.success(AdminEventResponse.from(
				modificationService.modify(eventId, request.toModification(user.getId())), timeProvider.now(), imageUrls::resolve));
	}

	/** AE05: 삭제 조건·논리 삭제는 서비스에 위임하고 데이터 없는 성공 JSON을 반환한다. */
	@DeleteMapping("/{eventId}")
	public ResponseEnvelope<Void> delete(@CurrentUser User user, @PathVariable UUID eventId) {
		deletionService.delete(eventId, user.getId());
		return ResponseEnvelope.success(null);
	}

	/** AE08: 취소 사유와 이벤트 ID를 전달하고 취소·전체 반환의 완료 결과를 반환한다. */
	@PostMapping("/{eventId}/cancel")
	public ResponseEnvelope<EventCancellationResponse> cancel(@CurrentUser User user, @PathVariable UUID eventId,
			@Valid @RequestBody EventCancellationRequest request) {
		return ResponseEnvelope.success(EventCancellationResponse.from(
				cancellationService.cancel(eventId, user.getId(), request.reason())));
	}

	/** AE01 관리자 검색. from/to는 양끝 날짜를 포함하는 KST 날짜 범위다. 모집 기간과 겹치면 포함한다. */
	@GetMapping
	public ResponseEnvelope<PageResult<AdminEventResponse>> list(
			@CurrentUser User user,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int size,
			@Valid @ModelAttribute @ParameterObject AdminEventQueryRequest request,
			BindingResult bindingResult) {
		// DTO 바인딩·형식 검증 오류에도 기존 COMMON-005 응답을 유지한다.
		if (bindingResult.hasErrors()) {
			throw new BusinessException(CommonErrorCode.INVALID_FORMAT);
		}
		PageResult<EventView> result = queryService.findAdminEvents(user, page, size,
				request.toDomain());
		Instant now = timeProvider.now();
		return ResponseEnvelope.success(result.map(event -> AdminEventResponse.from(event, now, imageUrls::resolve)));
	}

	/** AE02 관리자 상세는 실제 운영 상태와 이미지 키를 제공한다. */
	@GetMapping("/{eventId}")
	public ResponseEnvelope<AdminEventResponse> detail(
			@CurrentUser User user,
			@PathVariable UUID eventId) {
		return ResponseEnvelope.success(AdminEventResponse.from(
				queryService.findAdminEvent(user, eventId),
				timeProvider.now(), imageUrls::resolve));
	}
}
