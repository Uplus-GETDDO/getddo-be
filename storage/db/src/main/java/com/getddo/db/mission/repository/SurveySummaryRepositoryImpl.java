package com.getddo.db.mission.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.SurveyFreeTextAnswer;
import com.getddo.core.mission.repository.SurveySummaryRepository;
import com.getddo.db.common.pagination.PageOffsets;
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

	private static final String FREE_TEXT_SQL = """
			select a.answer_text as answer_text, s.created_at as created_at
			from survey_answers a
			join mission_submissions s on s.id = a.submission_id
			where a.survey_question_id = :questionId and s.is_completed = true and a.answer_text is not null
			  and s.created_at >= :from and s.created_at < :to
			order by s.created_at desc, a.id desc
			""";
	private static final String FREE_TEXT_COUNT_SQL = """
			select count(*)
			from survey_answers a
			join mission_submissions s on s.id = a.submission_id
			where a.survey_question_id = :questionId and s.is_completed = true and a.answer_text is not null
			  and s.created_at >= :from and s.created_at < :to
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

	@Override
	public PageResult<SurveyFreeTextAnswer> findFreeTextAnswers(UUID questionId, Instant from, Instant to,
			PageQuery page) {
		byte[] question = UuidBinary.toBytes(questionId);
		long total = ((Number) entityManager.createNativeQuery(FREE_TEXT_COUNT_SQL)
				.setParameter("questionId", question)
				.setParameter("from", from)
				.setParameter("to", to)
				.getSingleResult()).longValue();
		// 시작 위치가 전체 건수를 넘으면 항목이 없다. JPA가 int로 바꿀 수 없는 위치도 같은 경우다.
		if (page.offset() >= total || PageOffsets.exceedsIntRange(page)) {
			return new PageResult<>(List.of(), page.getPage(), page.getSize(), total);
		}
		@SuppressWarnings("unchecked")
		NativeQuery<Tuple> select = entityManager.createNativeQuery(FREE_TEXT_SQL, Tuple.class)
				.unwrap(NativeQuery.class)
				.addScalar("answer_text", String.class)
				.addScalar("created_at", Instant.class);
		select.setParameter("questionId", question);
		select.setParameter("from", from);
		select.setParameter("to", to);
		select.setFirstResult((int) page.offset());
		select.setMaxResults(page.getSize());
		List<SurveyFreeTextAnswer> items = select.getResultList().stream()
				.map(row -> new SurveyFreeTextAnswer(row.get("answer_text", String.class),
						row.get("created_at", Instant.class)))
				.toList();
		return new PageResult<>(items, page.getPage(), page.getSize(), total);
	}
}
