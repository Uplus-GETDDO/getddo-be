package com.getddo.core.event.service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.event.domain.EventRegistration;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventActorRepository;
import com.getddo.core.event.repository.EventRepository;
import com.getddo.core.notification.domain.EventNotificationChange;
import com.getddo.core.notification.service.EventChangeNotificationService;

/** 시작 전 이벤트·경품 수정과 시작 예약 중지·변경 안내 등록을 하나의 트랜잭션으로 처리한다. */
@Service
@RequiredArgsConstructor
public class EventModificationService {
	private final EventRepository events;
	private final EventActorRepository actors;
	private final EventChangeNotificationService notifications;
	private final TimeProvider timeProvider;
	private final AuditLogService auditLogs;

	/**
	 * 이벤트 잠금을 얻은 뒤 현재 시각과 기존 시작 시각으로 수정 자격을 판단한다.
	 * 기존 경품 ID와 전체 설정이 같으면 저장을 생략하고, ID 없는 경품은 항상 신규 등록한다.
	 */
	@Transactional
	public RegisteredEvent modify(UUID eventId, EventRegistration input) {
		requireAdmin(input);
		EventRegistration settings = EventWriteValidator.normalizeAndValidate(input);
		RegisteredEvent current = eventId == null ? null : events.findByIdForUpdate(eventId).orElse(null);
		if (current == null) {
			throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
		}
		// 잠금 대기 중 시작 시각이 지난 경우에도 수정을 허용하지 않는다.
		Instant now = timeProvider.now();
		if (current.getStatus() != EventStatus.SCHEDULED || !now.isBefore(current.getStartsAt())) {
			throw new EventException(EventErrorCode.MODIFICATION_NOT_ALLOWED);
		}
		if (settings.getStartsAt().isBefore(current.getStartsAt())) {
			throw new EventException(EventErrorCode.START_CANNOT_ADVANCE);
		}
		Set<UUID> currentIds = current.getPrizes().stream().map(RegisteredEvent.Prize::getId).collect(Collectors.toSet());
		if (settings.getPrizes().stream().anyMatch(prize -> prize.getId() != null && !currentIds.contains(prize.getId()))) {
			throw new EventException(EventErrorCode.INVALID_PRIZES);
		}
		if (sameSettings(current, settings)) {
			return current;
		}
		// 저장소가 flush한 뒤 GD-70의 JDBC 조회가 변경된 이벤트를 확인한다.
		RegisteredEvent updated = events.update(eventId, settings);
		if (updated.getStartsAt().isAfter(current.getStartsAt())) {
			// 실제 시작 연기에만 발생 키를 발급한다. 동일 시작 시각의 재요청은 변경 알림을 만들지 않는다.
			notifications.register(EventNotificationChange.scheduleChanged(UUID.randomUUID(), eventId,
					updated.getTitle(), current.getStartsAt(), updated.getStartsAt(), now));
		}
		auditLogs.record(new AuditLogCommand(input.getActorId(), "EVENT_UPDATE", "EVENT", eventId,
				null, EventAuditSnapshot.from(current), EventAuditSnapshot.from(updated), null));
		return updated;
	}

	private void requireAdmin(EventRegistration input) {
		if (input == null || input.getActorId() == null) {
			throw new EventException(EventErrorCode.USER_CONTEXT_REQUIRED);
		}
		EventActorRepository.Actor actor = actors.findById(input.getActorId())
				.orElseThrow(() -> new EventException(EventErrorCode.USER_CONTEXT_REQUIRED));
		if (!actor.isActive() || !actor.isAdmin()) {
			throw new EventException(EventErrorCode.ADMIN_REQUIRED);
		}
	}

	private boolean sameSettings(RegisteredEvent event, EventRegistration settings) {
		boolean sameConfiguration = Objects.equals(event.getTitle(), settings.getTitle())
				&& Objects.equals(event.getDescription(), settings.getDescription())
				&& Objects.equals(event.getImageKey(), settings.getImageKey())
				&& Objects.equals(event.getDetailImageKey(), settings.getDetailImageKey())
				&& event.getEventType() == settings.getEventType()
				&& event.isWeightingEnabled() == settings.isWeightingEnabled()
				&& Objects.equals(event.getMaxTicketsPerUser(), settings.getMaxTicketsPerUser())
				&& event.getMembershipRule() == settings.getMembershipRule()
				&& event.getStartsAt().equals(settings.getStartsAt()) && event.getEndsAt().equals(settings.getEndsAt())
				&& event.getPrizes().size() == settings.getPrizes().size();
		if (!sameConfiguration) {
			return false;
		}
		Map<Integer, RegisteredEvent.Prize> byRank = event.getPrizes().stream()
				.collect(Collectors.toMap(RegisteredEvent.Prize::getRank, Function.identity()));
		return settings.getPrizes().stream().allMatch(prize -> samePrize(byRank.get(prize.getRank()), prize));
	}

	private boolean samePrize(RegisteredEvent.Prize existing, EventRegistration.Prize prize) {
		return existing != null && existing.getId().equals(prize.getId())
				&& prize.getWinnerCount() == existing.getWinnerCount()
				&& Objects.equals(prize.getName(), existing.getName())
				&& Objects.equals(prize.getDescription(), existing.getDescription())
				&& Objects.equals(prize.getImageKey(), existing.getImageKey());
	}
}
