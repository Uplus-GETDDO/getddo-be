package com.getddo.db.mission.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.getddo.core.mission.repository.SurveySummaryRepository;
import com.getddo.db.common.util.UuidBinary;

/**
 * 설문 응답 집계 구현. 제출 시각은 UTC로 저장돼 있어 KST 날짜는 +9시간으로 환산해 묶는다(KST는 서머타임이 없다). 값은 모두
 * 파라미터로 바인딩한다.
 */
@Repository
@RequiredArgsConstructor
public class SurveySummaryRepositoryImpl implements SurveySummaryRepository {

	private static final String DAILY_SQL = """
			select cast(date(date_add(s.created_at, interval 9 hour)) as char) as day, count(*) as cnt
			from mission_submissions s
			where s.mission_id = :missionId and s.is_completed = true
			  and s.created_at >= :from and s.created_at < :to
			group by day
			""";
	private static final String OPTION_SQL = """
			select a.selected_option_id as option_id, count(*) as cnt
			from survey_answers a
			join mission_submissions s on s.id = a.submission_id
			where a.mission_id = :missionId and s.is_completed = true and a.selected_option_id is not null
			  and s.created_at >= :from and s.created_at < :to
			group by a.selected_option_id
			""";

	private final EntityManager entityManager;

	@Override
	@SuppressWarnings("unchecked")
	public Map<LocalDate, Long> countCompletedSubmissionsByDay(UUID missionId, Instant from, Instant to) {
		List<Object[]> rows = entityManager.createNativeQuery(DAILY_SQL)
				.setParameter("missionId", UuidBinary.toBytes(missionId))
				.setParameter("from", from)
				.setParameter("to", to)
				.getResultList();
		Map<LocalDate, Long> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue());
		}
		return counts;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<UUID, Long> countSelectionsByOption(UUID missionId, Instant from, Instant to) {
		List<Object[]> rows = entityManager.createNativeQuery(OPTION_SQL)
				.setParameter("missionId", UuidBinary.toBytes(missionId))
				.setParameter("from", from)
				.setParameter("to", to)
				.getResultList();
		Map<UUID, Long> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put(UuidBinary.fromBytes((byte[]) row[0]), ((Number) row[1]).longValue());
		}
		return counts;
	}
}
