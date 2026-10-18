package com.getddo.core.stats.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.getddo.core.ticket.domain.GrantSourceType;

/**
 * 관리자 일별 집계(R1). 날짜는 KST 업무일이며 활동이 없는 날도 0으로 채운다.
 *
 * @param days 조회 기간의 날짜별 집계. 날짜 오름차순
 * @param totalUserCount 조회 시점의 활성 일반 사용자 수. 참여율의 분모이며 날짜별 값이 아니다
 */
public record DailyStats(List<Day> days, long totalUserCount) {

	public DailyStats {
		days = List.copyOf(days);
		if (totalUserCount < 0) {
			throw new IllegalArgumentException("활성 일반 사용자 수는 음수일 수 없다.");
		}
	}

	/**
	 * 하루치 집계.
	 *
	 * @param entrantCount 그날 응모한 사용자 수(중복 제거). 응모권을 쓰지 않는 이벤트의 응모도 포함한다
	 * @param entryCount 그날 접수된 응모 건수
	 * @param activeUserCount 그날 응모·출석·미션 제출·게임 시작 중 하나 이상을 한 일반 사용자 수(중복 제거). 관리자 계정의 활동은 제외한다
	 * @param grantedTickets 그날 지급된 응모권 장수를 지급 근거별로 나눈 값. 모든 근거 키를 담는다
	 * @param usedTickets 그날 응모에 사용된 응모권 장수. 반환은 빼지 않은 총량이다
	 * @param blockedCount 그날 발생한 어뷰징 탐지 기록 수
	 */
	public record Day(LocalDate date, long entrantCount, long entryCount, long activeUserCount,
			Map<GrantSourceType, Long> grantedTickets, long usedTickets, long blockedCount) {

		/**
		 * @throws IllegalArgumentException 날짜가 없거나 집계값이 음수이거나 응모자 수가 응모 건수보다 크거나 {@code grantedTickets}에 지급 근거 키가 빠진 경우
		 */
		public Day {
			if (date == null || entrantCount < 0 || entryCount < 0 || activeUserCount < 0 || usedTickets < 0
					|| blockedCount < 0) {
				throw new IllegalArgumentException("날짜가 있어야 하고 집계값은 음수일 수 없다.");
			}
			if (entrantCount > entryCount) {
				throw new IllegalArgumentException("응모자 수는 응모 건수보다 클 수 없다.");
			}
			Map<GrantSourceType, Long> copy = new EnumMap<>(GrantSourceType.class);
			for (GrantSourceType type : GrantSourceType.values()) {
				Long count = grantedTickets == null ? null : grantedTickets.get(type);
				if (count == null || count < 0) {
					throw new IllegalArgumentException("지급 근거 " + type + "의 장수가 없거나 음수다.");
				}
				copy.put(type, count);
			}
			grantedTickets = Collections.unmodifiableMap(copy);
		}
	}

	/** 날짜별 응모자 수와 응모 건수. */
	public record EntryCounts(long entrantCount, long entryCount) {

		public static final EntryCounts NONE = new EntryCounts(0, 0);
	}
}
