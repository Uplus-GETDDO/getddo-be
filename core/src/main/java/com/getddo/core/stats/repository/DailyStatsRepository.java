package com.getddo.core.stats.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.getddo.core.stats.domain.DailyStats.EntryCounts;
import com.getddo.core.ticket.domain.GrantSourceType;

/**
 * 관리자 일별 집계 조회. 읽기 전용이며 아무것도 잠그지 않는다. 모든 메서드는 활동이 있는 KST 날짜만 담고 없는 날은 담지
 * 않는다. 시각 인자는 {@code [from, to)} 구간(UTC)이다.
 */
public interface DailyStatsRepository {

	/** 접수된 응모의 응모자 수(중복 제거)와 건수를 응모 접수 시각의 KST 날짜별로 센다. */
	Map<LocalDate, EntryCounts> countEntries(Instant from, Instant to);

	/**
	 * 응모·출석·미션 제출·게임 시작 중 하나 이상을 한 일반 사용자 수를 KST 날짜별로 센다(중복 제거, 관리자 계정의 활동은 제외). 출석은 출석 기준 날짜를 쓴다.
	 *
	 * @param fromDate 출석 기준 날짜 하한(포함)
	 * @param toDate   출석 기준 날짜 상한(포함)
	 */
	Map<LocalDate, Long> countActiveUsers(Instant from, Instant to, LocalDate fromDate, LocalDate toDate);

	/** 지급된 응모권 장수를 최초 지급 시각의 KST 날짜와 지급 근거별로 센다. */
	Map<LocalDate, Map<GrantSourceType, Long>> countGrantedTickets(Instant from, Instant to);

	/** 응모에 사용된 응모권 장수를 사용 시각의 KST 날짜별로 센다. 반환은 빼지 않는다. */
	Map<LocalDate, Long> countUsedTickets(Instant from, Instant to);

	/** 어뷰징 탐지 기록 수를 탐지 대상 행위 발생 시각의 KST 날짜별로 센다. */
	Map<LocalDate, Long> countAbuseCases(Instant from, Instant to);

	/** 활성 일반 사용자 수. */
	long countActiveRegularUsers();
}
