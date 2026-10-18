package com.getddo.core.stats.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.stats.domain.DailyStats;
import com.getddo.core.stats.domain.DailyStats.Day;
import com.getddo.core.stats.domain.DailyStats.EntryCounts;
import com.getddo.core.stats.exception.StatsErrorCode;
import com.getddo.core.stats.exception.StatsException;
import com.getddo.core.stats.repository.DailyStatsRepository;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

/**
 * 관리자 일별 집계 조회(R1). 읽기 전용이며 활성 관리자만 호출할 수 있다.
 *
 * <p>기간은 KST 날짜 {@code [from, to]}(양끝 포함)이고 최대 {@value #MAX_DAYS}일이다. 여러 도메인의 집계를 같은 반복 가능한
 * 읽기 스냅샷에서 구해 서로 맞게 한다.</p>
 */
@Service
@RequiredArgsConstructor
public class DailyStatsService {

	/** 한 번에 집계할 수 있는 최대 날짜 수. */
	public static final int MAX_DAYS = 31;

	private final DailyStatsRepository statsRepository;
	private final TimeProvider timeProvider;

	/**
	 * @param from KST 시작 날짜(포함)
	 * @param to   KST 끝 날짜(포함)
	 * @throws StatsException 관리자가 아니거나({@code STATS-002}) 기간이 올바르지 않은 경우({@code STATS-001})
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DailyStats daily(User admin, LocalDate from, LocalDate to) {
		requireActiveAdmin(admin);
		requireValidPeriod(from, to);
		Instant start = timeProvider.toUtc(from.atStartOfDay());
		Instant end = timeProvider.toUtc(to.plusDays(1).atStartOfDay());
		Map<LocalDate, EntryCounts> entries = statsRepository.countEntries(start, end);
		Map<LocalDate, Long> activeUsers = statsRepository.countActiveUsers(start, end, from, to);
		Map<LocalDate, Map<GrantSourceType, Long>> granted = statsRepository.countGrantedTickets(start, end);
		Map<LocalDate, Long> used = statsRepository.countUsedTickets(start, end);
		Map<LocalDate, Long> blocked = statsRepository.countAbuseCases(start, end);
		List<Day> days = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			EntryCounts entry = entries.getOrDefault(date, EntryCounts.NONE);
			days.add(new Day(date, entry.entrantCount(), entry.entryCount(), activeUsers.getOrDefault(date, 0L),
					grantedBySource(granted.get(date)), used.getOrDefault(date, 0L), blocked.getOrDefault(date, 0L)));
		}
		return new DailyStats(days, statsRepository.countActiveRegularUsers());
	}

	/** 모든 지급 근거 키를 담고 없는 근거는 0으로 채운다. */
	private static Map<GrantSourceType, Long> grantedBySource(Map<GrantSourceType, Long> counts) {
		Map<GrantSourceType, Long> result = new EnumMap<>(GrantSourceType.class);
		for (GrantSourceType type : GrantSourceType.values()) {
			result.put(type, counts == null ? 0L : counts.getOrDefault(type, 0L));
		}
		return result;
	}

	private static void requireValidPeriod(LocalDate from, LocalDate to) {
		boolean valid = from != null && to != null && !to.isBefore(from)
				&& ChronoUnit.DAYS.between(from, to) < MAX_DAYS;
		if (!valid) {
			throw new StatsException(StatsErrorCode.INVALID_PERIOD);
		}
	}

	private static void requireActiveAdmin(User user) {
		if (user == null || user.getRole() != UserRole.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
			throw new StatsException(StatsErrorCode.ADMIN_REQUIRED);
		}
	}
}
