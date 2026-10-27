package com.getddo.core.event.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.EventCancellationResult;
import com.getddo.core.event.domain.EventCancellationState;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventActorRepository;
import com.getddo.core.event.repository.EventRepository;
import com.getddo.core.notification.domain.EventNotificationChange;
import com.getddo.core.notification.service.EventChangeNotificationService;
import com.getddo.core.ticket.service.TicketBulkRefundService;

/** 이벤트 취소·차감분 반환·취소 안내 등록·감사 기록을 하나의 업무 트랜잭션에서 확정한다. */
@Service
@RequiredArgsConstructor
public class EventCancellationService {
	private static final int MAX_REASON_BYTES = 65_535;
	private final EventRepository events;
	private final EventActorRepository actors;
	private final TicketBulkRefundService refunds;
	private final EventChangeNotificationService notifications;
	private final AuditLogService auditLogs;
	private final TimeProvider timeProvider;

	/**
	 * 관리자 API가 여는 취소 트랜잭션이다. 이벤트 쓰기 잠금 → 응모·응모권 → 알림 순서로 처리한다.
	 * READ COMMITTED로 잠금 대기 중 확정된 USE 이력도 기존 반환 서비스의 일반 조회에서 볼 수 있게 한다.
	 * 이미 취소되었으면 409로 거절하며 반환·알림·감사 기록을 다시 만들지 않는다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public EventCancellationResult cancel(UUID eventId, UUID actorId, String reason) {
		requireAdmin(actorId);
		if (reason == null || reason.isBlank() || reason.getBytes(StandardCharsets.UTF_8).length > MAX_REASON_BYTES) {
			throw new EventException(EventErrorCode.INVALID_CANCELLATION_REASON);
		}
		EventCancellationState current = eventId == null ? null : events.findCancellationByIdForUpdate(eventId).orElse(null);
		if (current == null) {
			throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
		}
		if (current.getStatus() == EventStatus.CANCELED) {
			throw new EventException(EventErrorCode.ALREADY_CANCELED);
		}
		Instant canceledAt = timeProvider.now().truncatedTo(ChronoUnit.MICROS);
		events.markCanceled(eventId, canceledAt);
		// 거절·무료 응모는 제외한다. SQL 묶음 사이에도 같은 트랜잭션·이벤트 잠금을 유지한다.
		long refundedTicketCount = refunds.refundAll(events.findChargedEntryIds(eventId), reason);
		notifications.register(EventNotificationChange.canceled(UUID.randomUUID(), eventId, current.getTitle(), canceledAt));
		auditLogs.record(new AuditLogCommand(actorId, "EVENT_CANCEL", "EVENT", eventId, reason,
				Map.of("status", current.getStatus().name()),
				Map.of("status", EventStatus.CANCELED.name(), "canceledAt", canceledAt.toString(),
						"refundedTicketCount", refundedTicketCount), null));
		return new EventCancellationResult(eventId, current.getStatus(), refundedTicketCount, canceledAt);
	}

	private void requireAdmin(UUID actorId) {
		if (actorId == null) {
			throw new EventException(EventErrorCode.USER_CONTEXT_REQUIRED);
		}
		EventActorRepository.Actor actor = actors.findById(actorId)
				.orElseThrow(() -> new EventException(EventErrorCode.USER_CONTEXT_REQUIRED));
		if (!actor.isActive() || !actor.isAdmin()) {
			throw new EventException(EventErrorCode.ADMIN_REQUIRED);
		}
	}
}
