package com.getddo.core.event.service;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.getddo.core.event.domain.EventRegistration;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;

/** 등록·수정이 동일한 기간 정밀도, TEXT 바이트 상한과 경품·응모 조건을 사용하도록 검증한다. */
final class EventWriteValidator {
	private static final int MAX_DESCRIPTION_BYTES = 65_535;

	private EventWriteValidator() {
	}

	static EventRegistration normalizeAndValidate(EventRegistration input) {
		EventRegistration settings = new EventRegistration(input.getActorId(), input.getTitle(), input.getDescription(),
				input.getImageKey(), input.getDetailImageKey(), input.getEventType(), input.isWeightingEnabled(), input.getMaxTicketsPerUser(),
				input.getMembershipRule(),
				input.getStartsAt() == null ? null : input.getStartsAt().truncatedTo(ChronoUnit.MICROS),
				input.getEndsAt() == null ? null : input.getEndsAt().truncatedTo(ChronoUnit.MICROS), input.getPrizes());
		if (blankOrTooLong(settings.getTitle(), 200) || settings.getDescription() == null
				|| settings.getDescription().isBlank() || descriptionTooLong(settings.getDescription())
				|| tooLong(settings.getImageKey(), 500) || tooLong(settings.getDetailImageKey(), 500)) {
			throw new EventException(EventErrorCode.INVALID_DETAILS);
		}
		if (!EventTimeRange.contains(settings.getStartsAt()) || !EventTimeRange.contains(settings.getEndsAt())
				|| !settings.getEndsAt().isAfter(settings.getStartsAt())) {
			throw new EventException(EventErrorCode.INVALID_PERIOD);
		}
		if (settings.getEventType() == null || settings.getMembershipRule() == null || !validTicketConfiguration(settings)) {
			throw new EventException(EventErrorCode.INVALID_CONFIGURATION);
		}
		if (settings.getPrizes() == null || settings.getPrizes().isEmpty()) {
			throw new EventException(EventErrorCode.INVALID_PRIZES);
		}
		Set<Integer> ranks = new HashSet<>();
		Set<UUID> ids = new HashSet<>();
		for (EventRegistration.Prize prize : settings.getPrizes()) {
			if (prize == null || prize.getRank() < 1 || prize.getWinnerCount() < 1
					|| blankOrTooLong(prize.getName(), 200) || tooLong(prize.getImageKey(), 500)
					|| descriptionTooLong(prize.getDescription()) || !ranks.add(prize.getRank())
					|| (prize.getId() != null && !ids.add(prize.getId()))) {
				throw new EventException(EventErrorCode.INVALID_PRIZES);
			}
		}
		return settings;
	}

	private static boolean validTicketConfiguration(EventRegistration settings) {
		Integer limit = settings.getMaxTicketsPerUser();
		if (settings.getEventType() == EventType.NO_TICKET) {
			return !settings.isWeightingEnabled() && limit == null;
		}
		if (!settings.isWeightingEnabled()) {
			return Integer.valueOf(1).equals(limit);
		}
		return limit == null || Integer.valueOf(5).equals(limit);
	}

	private static boolean blankOrTooLong(String value, int maximum) {
		return value == null || value.isBlank() || value.length() > maximum;
	}

	private static boolean tooLong(String value, int maximum) {
		return value != null && value.length() > maximum;
	}

	private static boolean descriptionTooLong(String value) {
		return value != null && value.getBytes(StandardCharsets.UTF_8).length > MAX_DESCRIPTION_BYTES;
	}
}
