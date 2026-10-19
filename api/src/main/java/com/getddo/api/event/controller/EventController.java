package com.getddo.api.event.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.event.config.EventImageUrls;
import com.getddo.api.event.dto.response.EventDetailResponse;
import com.getddo.api.event.dto.response.EventSummaryResponse;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.EventQueryFilter;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.service.EventQueryService;
import com.getddo.core.user.domain.User;

/** E01·E02 사용자 이벤트 목록·상세. 관리자도 이 조회 경로를 사용할 수 있다. */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {
	private final EventQueryService queryService;
	private final TimeProvider timeProvider;
	private final EventImageUrls imageUrls;

	public EventController(EventQueryService queryService, TimeProvider timeProvider, EventImageUrls imageUrls) {
		this.queryService = queryService;
		this.timeProvider = timeProvider;
		this.imageUrls = imageUrls;
	}

	/** 조회 조건과 페이지 입력을 서비스에 전달하고 결과를 사용자 목록 응답으로 변환한다. */
	@GetMapping
	public ResponseEnvelope<PageResult<EventSummaryResponse>> list(
			@CurrentUser User user,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) EventStatus status,
			@RequestParam(required = false) EventType eventType,
			@RequestParam(required = false) MembershipRule membershipRule) {
		PageResult<EventView> result = queryService.findUserEvents(user, page, size,
				new EventQueryFilter(status, eventType, membershipRule, null, null, null));
		Instant now = timeProvider.now();
		return ResponseEnvelope.success(result.map(event -> EventSummaryResponse.from(event, now, imageUrls::resolve)));
	}

	/** Spring이 변환한 이벤트 UUID로 상세 정보와 경품을 조회한다. */
	@GetMapping("/{eventId}")
	public ResponseEnvelope<EventDetailResponse> detail(
			@CurrentUser User user,
			@PathVariable UUID eventId) {
		return ResponseEnvelope.success(EventDetailResponse.from(
				queryService.findUserEvent(user, eventId), timeProvider.now(), imageUrls::resolve));
	}
}
