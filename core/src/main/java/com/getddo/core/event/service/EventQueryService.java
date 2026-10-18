package com.getddo.core.event.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.AdminEventQuery;
import com.getddo.core.event.domain.EventQueryFilter;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.EventSort;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventQueryRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import lombok.RequiredArgsConstructor;

/** 사용자·관리자 조회 권한을 확인하고 동일 트랜잭션에서 목록과 전체 건수를 읽는다. */
@Service
@RequiredArgsConstructor
public class EventQueryService {
	private final EventQueryRepository events;
	private final TimeProvider timeProvider;

	/** 활성 사용자의 목록을 공개 상태 기준으로 조회하며, 목록과 건수는 같은 읽기 트랜잭션에 묶는다. */
	@Transactional(readOnly = true)
	public PageResult<EventView> findUserEvents(User user, int page, int size, EventQueryFilter filter) {
		validateActor(user, false);
		PageResult<EventView> result = events.findAll(validateQuery(page, size, filter), new PageQuery(page, size), true);
		if (user.getRole() != UserRole.USER || result.getItems().isEmpty()) {
			return result.map(event -> event.withMyEntered(false));
		}
		List<UUID> eventIds = result.getItems().stream().map(event -> event.getDetails().getId()).toList();
		Set<UUID> enteredIds = events.findEnteredEventIds(user.getId(), eventIds);
		return result.map(event -> event.withMyEntered(enteredIds.contains(event.getDetails().getId())));
	}

	/** 활성 사용자의 상세 조회. 저장소가 반환한 공개 상태를 사용자 응답에 사용한다. */
	@Transactional(readOnly = true)
	public EventView findUserEvent(User user, UUID eventId) {
		validateActor(user, false);
		return findEvent(eventId);
	}

	/** 관리자 권한 확인 후 KST 날짜 범위를 UTC로 변환하고 실제 상태 기준으로 목록·건수를 조회한다. */
	@Transactional(readOnly = true)
	public PageResult<EventView> findAdminEvents(User user, int page, int size, AdminEventQuery query) {
		validateActor(user, true);
		return events.findAll(validateQuery(page, size, toFilter(query)), new PageQuery(page, size), false);
	}

	/** 관리자 상세의 이벤트·경품을 같은 읽기 트랜잭션에서 조회한다. */
	@Transactional(readOnly = true)
	public EventView findAdminEvent(User user, UUID eventId) {
		validateActor(user, true);
		return findEvent(eventId);
	}

	/** 삭제된 이벤트도 저장소에서 제외하므로 존재하지 않는 ID와 동일하게 404 업무 오류로 처리한다. */
	private EventView findEvent(UUID eventId) {
		if (eventId == null) {
			throw new EventException(EventErrorCode.INVALID_QUERY);
		}
		return events.findById(eventId)
				.orElseThrow(() -> new EventException(EventErrorCode.EVENT_NOT_FOUND));
	}

	private void validateActor(User user, boolean adminOnly) {
		if (user == null) {
			throw new EventException(EventErrorCode.USER_CONTEXT_REQUIRED);
		}
		if (user.getStatus() != UserStatus.ACTIVE || (adminOnly && user.getRole() != UserRole.ADMIN)) {
			throw new EventException(EventErrorCode.ACCESS_DENIED);
		}
	}

	/** 입력의 양끝 날짜를 모두 포함하도록 종료일 다음 날 자정을 제외 상한으로 만든다. */
	private EventQueryFilter toFilter(AdminEventQuery query) {
		if (query == null) {
			throw new EventException(EventErrorCode.INVALID_QUERY);
		}
		try {
			return new EventQueryFilter(query.getStatus(), query.getEventType(), null, query.getKeyword(),
					startOfDay(query.getFromDate()), endOfDay(query.getToDate()), query.getWeightingEnabled(),
					startOfDay(query.getCreatedFromDate()), endOfDay(query.getCreatedToDate()),
					EventSort.fromQuery(query.getSort()));
		} catch (DateTimeException exception) {
			// HTTP 외의 호출에서도 날짜 계산 범위 초과를 업무 오류로 처리한다.
			throw new EventException(EventErrorCode.INVALID_QUERY, exception);
		}
	}

	private Instant startOfDay(LocalDate date) {
		return date == null ? null : timeProvider.toUtc(date.atStartOfDay());
	}

	private Instant endOfDay(LocalDate date) {
		return date == null ? null : timeProvider.toUtc(date.plusDays(1).atStartOfDay());
	}

	/** 페이지와 UTC 검색 경계를 검사해 잘못된 조건이 DB 쿼리로 전달되지 않도록 한다. */
	private EventQueryFilter validateQuery(int page, int size, EventQueryFilter filter) {
		if (page < 1 || size < 1 || size > 100 || filter == null
				|| !validRange(filter.getFrom(), filter.getTo())
				|| !validRange(filter.getCreatedFrom(), filter.getCreatedTo())) {
			throw new EventException(EventErrorCode.INVALID_QUERY);
		}
		return filter;
	}

	private boolean validRange(Instant from, Instant to) {
		return (from == null || EventTimeRange.contains(from))
				&& (to == null || EventTimeRange.contains(to))
				&& (from == null || to == null || from.isBefore(to));
	}
}
