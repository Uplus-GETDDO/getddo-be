package com.getddo.db.stats.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.getddo.core.stats.domain.DailyStats.EntryCounts;
import com.getddo.core.stats.repository.DailyStatsRepository;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.user.repository.AdminUserQueryRepository;

/**
 * 관리자 일별 집계 구현. 시각은 UTC로 저장돼 있어 KST 날짜는 +9시간으로 환산해 묶는다(KST는 서머타임이 없다). 값은 모두
 * 파라미터로 바인딩한다.
 */
@Repository
@RequiredArgsConstructor
public class DailyStatsRepositoryImpl implements DailyStatsRepository {

	/** 거절된 응모(REJECTED)도 같은 테이블에 저장되므로 접수 완료(ACCEPTED)만 센다. */
	private static final String ENTRY_SQL = """
			select cast(date(date_add(e.created_at, interval 9 hour)) as char) as day,
			       count(distinct e.user_id) as entrants, count(*) as entries
			from event_entries e
			where e.status = 'ACCEPTED' and e.created_at >= :from and e.created_at < :to
			group by day
			""";
	/** 출석은 KST 기준 날짜를 그대로 쓰고, 나머지는 시각을 KST 날짜로 환산한다. UNION이 같은 날 같은 사용자를 한 번만 남긴다. 관리자 계정의 활동은 제외한다. */
	private static final String ACTIVE_USER_SQL = """
			select a.day as day, count(*) as cnt
			from (
			  select e.user_id as user_id, cast(date(date_add(e.created_at, interval 9 hour)) as char) as day
			  from event_entries e where e.status = 'ACCEPTED' and e.created_at >= :from and e.created_at < :to
			  union
			  select t.user_id, cast(t.attendance_date as char)
			  from attendances t where t.attendance_date >= :fromDate and t.attendance_date <= :toDate
			  union
			  select m.user_id, cast(date(date_add(m.created_at, interval 9 hour)) as char)
			  from mission_submissions m where m.created_at >= :from and m.created_at < :to
			  union
			  select g.user_id, cast(date(date_add(g.created_at, interval 9 hour)) as char)
			  from game_plays g where g.created_at >= :from and g.created_at < :to
			) a
			join users u on u.id = a.user_id and u.role = 'USER'
			group by a.day
			""";
	private static final String GRANTED_SQL = """
			select cast(date(date_add(t.created_at, interval 9 hour)) as char) as day,
			       case when t.attendance_reward_claim_id is not null then 'ATTENDANCE'
			            when t.mission_reward_claim_id is not null then 'MISSION'
			            else 'GAME' end as source,
			       count(*) as cnt
			from tickets t
			where t.created_at >= :from and t.created_at < :to
			group by day, source
			""";
	private static final String USED_SQL = """
			select cast(date(date_add(h.created_at, interval 9 hour)) as char) as day, count(*) as cnt
			from ticket_histories h
			where h.operation_type = 'USE' and h.created_at >= :from and h.created_at < :to
			group by day
			""";
	private static final String ABUSE_SQL = """
			select cast(date(date_add(a.occurred_at, interval 9 hour)) as char) as day, count(*) as cnt
			from abuse_cases a
			where a.occurred_at >= :from and a.occurred_at < :to
			group by day
			""";

	private final EntityManager entityManager;
	private final AdminUserQueryRepository users;

	@Override
	@SuppressWarnings("unchecked")
	public Map<LocalDate, EntryCounts> countEntries(Instant from, Instant to) {
		List<Object[]> rows = entityManager.createNativeQuery(ENTRY_SQL)
				.setParameter("from", from)
				.setParameter("to", to)
				.getResultList();
		Map<LocalDate, EntryCounts> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put(LocalDate.parse((String) row[0]),
					new EntryCounts(((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
		}
		return counts;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<LocalDate, Long> countActiveUsers(Instant from, Instant to, LocalDate fromDate, LocalDate toDate) {
		List<Object[]> rows = entityManager.createNativeQuery(ACTIVE_USER_SQL)
				.setParameter("from", from)
				.setParameter("to", to)
				.setParameter("fromDate", fromDate)
				.setParameter("toDate", toDate)
				.getResultList();
		return dailyCounts(rows);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<LocalDate, Map<GrantSourceType, Long>> countGrantedTickets(Instant from, Instant to) {
		List<Object[]> rows = entityManager.createNativeQuery(GRANTED_SQL)
				.setParameter("from", from)
				.setParameter("to", to)
				.getResultList();
		Map<LocalDate, Map<GrantSourceType, Long>> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.computeIfAbsent(LocalDate.parse((String) row[0]), day -> new EnumMap<>(GrantSourceType.class))
					.put(GrantSourceType.valueOf((String) row[1]), ((Number) row[2]).longValue());
		}
		return counts;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<LocalDate, Long> countUsedTickets(Instant from, Instant to) {
		return dailyCounts(entityManager.createNativeQuery(USED_SQL)
				.setParameter("from", from)
				.setParameter("to", to)
				.getResultList());
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<LocalDate, Long> countAbuseCases(Instant from, Instant to) {
		return dailyCounts(entityManager.createNativeQuery(ABUSE_SQL)
				.setParameter("from", from)
				.setParameter("to", to)
				.getResultList());
	}

	@Override
	public long countActiveRegularUsers() {
		return users.countActiveRegularUsers();
	}

	private static Map<LocalDate, Long> dailyCounts(List<Object[]> rows) {
		Map<LocalDate, Long> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue());
		}
		return counts;
	}
}
