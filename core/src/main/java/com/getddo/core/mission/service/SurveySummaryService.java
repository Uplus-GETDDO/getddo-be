package com.getddo.core.mission.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.core.mission.domain.SurveyFreeTextAnswer;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.domain.SurveyQuestionType;
import com.getddo.core.mission.domain.SurveySummary;
import com.getddo.core.mission.domain.SurveySummary.DailyCount;
import com.getddo.core.mission.domain.SurveySummary.OptionCount;
import com.getddo.core.mission.domain.SurveySummary.QuestionSummary;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.repository.MissionRepository;
import com.getddo.core.mission.repository.SurveySummaryRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

/**
 * 관리자의 설문 응답 집계 조회(R5). 읽기 전용이며 활성 관리자만 호출할 수 있다.
 *
 * <p>기간은 KST 날짜 {@code [from, to]}(양끝 포함)이고 최대 {@value #MAX_DAYS}일이다. 완료된 제출만 세며 자유 서술 문항은
 * 집계하지 않는다. 날짜별 제출 수와 선택지별 응답 수를 같은 반복 가능한 읽기 스냅샷에서 구해 서로 맞게 한다.</p>
 */
@Service
@RequiredArgsConstructor
public class SurveySummaryService {

	/** 한 번에 집계할 수 있는 최대 날짜 수. */
	public static final int MAX_DAYS = 31;

	private final MissionRepository missionRepository;
	private final SurveySummaryRepository summaryRepository;
	private final TimeProvider timeProvider;

	/**
	 * 설문 미션의 응답을 집계한다.
	 *
	 * @param from KST 시작 날짜(포함)
	 * @param to   KST 끝 날짜(포함)
	 * @throws MissionException 관리자가 아니거나({@code MISSION-006}), 미션이 없거나({@code MISSION-003}), 설문 미션이
	 *         아니거나 기간이 올바르지 않은 경우({@code MISSION-001})
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SurveySummary summarize(User admin, UUID missionId, LocalDate from, LocalDate to) {
		SurveyScope scope = surveyScope(admin, missionId, from, to);
		Map<LocalDate, Long> perDay = summaryRepository.countCompletedSubmissionsByDay(missionId, scope.start,
				scope.end);
		Map<UUID, Long> perOption = summaryRepository.countSelectionsByOption(missionId, scope.start, scope.end);
		return new SurveySummary(dailyCounts(from, to, perDay), questionSummaries(scope.detail, perOption));
	}

	/**
	 * 설문 미션의 자유 서술 문항에 남은 답변을 제출 시각 최신순으로 조회한다. 익명이라 답변 원문과 제출 시각만 돌려준다.
	 * 완료된 제출의 답변만 담고, 기간은 {@link #summarize}와 같은 KST 날짜 규칙이다.
	 *
	 * @param questionId 자유 서술 문항 ID. 이 미션의 자유 서술 문항이어야 한다
	 * @throws MissionException 관리자가 아니거나({@code MISSION-006}), 미션이 없거나({@code MISSION-003}), 설문 미션이
	 *         아니거나 문항이 이 미션의 자유 서술 문항이 아니거나 기간이 올바르지 않은 경우({@code MISSION-001})
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResult<SurveyFreeTextAnswer> findFreeTextAnswers(User admin, UUID missionId, UUID questionId,
			LocalDate from, LocalDate to, PageQuery page) {
		SurveyScope scope = surveyScope(admin, missionId, from, to);
		boolean freeTextQuestion = questionId != null && scope.detail.getSurveyQuestions().stream()
				.anyMatch(question -> question.getId().equals(questionId)
						&& question.getQuestionType() == SurveyQuestionType.FREE_TEXT);
		if (!freeTextQuestion) {
			throw new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION);
		}
		return summaryRepository.findFreeTextAnswers(questionId, scope.start, scope.end, page);
	}

	/** 조회 대상 설문 미션과 조회 기간의 UTC 범위. */
	private static final class SurveyScope {

		private final MissionDetail detail;
		private final Instant start;
		private final Instant end;

		private SurveyScope(MissionDetail detail, Instant start, Instant end) {
			this.detail = detail;
			this.start = start;
			this.end = end;
		}
	}

	/**
	 * 관리자·기간·미션·설문 유형을 같은 순서와 오류 코드로 확인하고, KST 날짜 범위를 UTC 시각 범위(시작 포함, 끝 제외)로 바꾼다.
	 */
	private SurveyScope surveyScope(User admin, UUID missionId, LocalDate from, LocalDate to) {
		requireActiveAdmin(admin);
		requireValidPeriod(from, to);
		MissionDetail detail = missionRepository.findDetailById(missionId)
				.orElseThrow(() -> new MissionException(MissionErrorCode.MISSION_NOT_FOUND));
		if (detail.getMission().getMissionType() != MissionType.SURVEY) {
			throw new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION);
		}
		return new SurveyScope(detail, timeProvider.toUtc(from.atStartOfDay()),
				timeProvider.toUtc(to.plusDays(1).atStartOfDay()));
	}

	private static List<DailyCount> dailyCounts(LocalDate from, LocalDate to, Map<LocalDate, Long> perDay) {
		List<DailyCount> days = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			days.add(new DailyCount(date, perDay.getOrDefault(date, 0L)));
		}
		return days;
	}

	private static List<QuestionSummary> questionSummaries(MissionDetail detail, Map<UUID, Long> perOption) {
		return detail.getSurveyQuestions().stream()
				.filter(question -> question.getQuestionType() == SurveyQuestionType.SINGLE_CHOICE)
				.map(question -> questionSummary(question, perOption))
				.toList();
	}

	private static QuestionSummary questionSummary(SurveyQuestion question, Map<UUID, Long> perOption) {
		List<OptionCount> options = question.getOptions().stream()
				.map(option -> new OptionCount(option.getId(), option.getOptionText(),
						perOption.getOrDefault(option.getId(), 0L)))
				.toList();
		return new QuestionSummary(question.getId(), question.getQuestionText(), options);
	}

	private static void requireValidPeriod(LocalDate from, LocalDate to) {
		boolean valid = from != null && to != null && !to.isBefore(from)
				&& ChronoUnit.DAYS.between(from, to) < MAX_DAYS;
		if (!valid) {
			throw new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION);
		}
	}

	private static void requireActiveAdmin(User user) {
		if (user == null || user.getRole() != UserRole.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
			throw new MissionException(MissionErrorCode.ADMIN_REQUIRED);
		}
	}
}
