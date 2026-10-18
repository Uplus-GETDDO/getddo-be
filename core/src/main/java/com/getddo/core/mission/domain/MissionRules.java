package com.getddo.core.mission.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;

/** 미션 도메인 객체가 공유하는 검증. 규칙을 어기면 {@link MissionException}을 던진다. */
final class MissionRules {

	private MissionRules() {
	}

	/** 조건이 거짓이면 미션 구성 오류로 본다. */
	static void definition(boolean valid) {
		if (!valid) {
			throw new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION);
		}
	}

	/** 조건이 거짓이면 답변 형식 오류로 본다. */
	static void answer(boolean valid) {
		if (!valid) {
			throw new MissionException(MissionErrorCode.INVALID_MISSION_ANSWER);
		}
	}

	/**
	 * 새로 등록하는 미션이 지켜야 할 공통 규칙이다. 등록 정보({@link MissionRegistration})와 새 미션({@link Mission#register})이
	 * 같은 규칙을 쓰도록 한곳에 둔다. 제목은 비어 있지 않고 200자 이하, 설명은 비어 있지 않고, 종료 시각은 시작 시각 뒤이며,
	 * 이미지 경로는 500자 이하다.
	 */
	static void registrable(String title, String description, Instant startsAt, Instant endsAt, String imageKey) {
		definition(hasText(title) && title.length() <= Mission.MAX_TITLE_LENGTH);
		definition(hasText(description));
		definition(endsAt.isAfter(startsAt));
		definition(imageKey == null || imageKey.length() <= Mission.MAX_IMAGE_KEY_LENGTH);
	}

	static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/** 표시 순서가 겹치지 않는지 확인한다. DB의 {@code UNIQUE (소속, display_order)}와 같은 규칙이다. */
	static <T> boolean distinctOrders(Collection<T> items, Function<T, Integer> order) {
		Set<Integer> seen = new HashSet<>();
		return items.stream().map(order).allMatch(seen::add);
	}
}
