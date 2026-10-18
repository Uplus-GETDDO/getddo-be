package com.getddo.api.stats.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.getddo.core.stats.domain.DailyStats;
import com.getddo.core.ticket.domain.GrantSourceType;

/**
 * 관리자 일별 집계(R1). 날짜는 KST 업무일이며 활동이 없는 날도 0으로 담는다. {@code grantedTickets}는 모든 지급 근거 키를 담는다.
 * {@code totalUserCount}는 조회 시점의 활성 일반 사용자 수다.
 */
public record DailyStatsResponse(List<Day> days, long totalUserCount) {

	public record Day(LocalDate date, long entrantCount, long entryCount, long activeUserCount,
			Map<GrantSourceType, Long> grantedTickets, long usedTickets, long blockedCount) {
	}

	public static DailyStatsResponse from(DailyStats stats) {
		return new DailyStatsResponse(
				stats.days().stream()
						.map(day -> new Day(day.date(), day.entrantCount(), day.entryCount(), day.activeUserCount(),
								day.grantedTickets(), day.usedTickets(), day.blockedCount()))
						.toList(),
				stats.totalUserCount());
	}
}
