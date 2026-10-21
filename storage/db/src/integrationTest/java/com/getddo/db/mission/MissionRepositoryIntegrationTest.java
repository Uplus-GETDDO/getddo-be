package com.getddo.db.mission;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionSearch;
import com.getddo.core.mission.domain.MissionStatus;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.core.mission.domain.QuizOption;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.domain.QuizQuestionType;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.domain.SurveyQuestionType;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/** 미션과 문항·선택지 Entity 매핑과 저장소 구현을 Flyway로 만든 실제 MySQL 스키마(V003)에서 검증한다. */
class MissionRepositoryIntegrationTest extends MissionIntegrationTestSupport {

	@Test
	@DisplayName("퀴즈 미션을 문항·선택지와 함께 저장하면 모든 ID와 생성·수정 시각이 채워진다")
	void insertsQuizMissionWithQuestions() {
		// given
		// when
		MissionDetail saved = saveQuizMission("퀴즈 미션");
		// then
		Mission mission = saved.getMission();
		assertThat(mission.getId()).isNotNull();
		assertThat(mission.getCreatedAt()).isEqualTo(clock.instant());
		assertThat(mission.getUpdatedAt()).isEqualTo(clock.instant());
		assertThat(mission.getStatus()).isEqualTo(MissionStatus.ACTIVE);
		assertThat(saved.getQuizQuestions()).hasSize(3)
				.allSatisfy(question -> assertThat(question.getId()).isNotNull());
		assertThat(saved.getQuizQuestions().get(0).getOptions())
				.allSatisfy(option -> assertThat(option.getId()).isNotNull());
		assertThat(count("select count(*) from mission_quiz_questions where mission_id = ?", bytes(mission.getId())))
				.isEqualTo(3);
		assertThat(count("""
				select count(*) from quiz_question_options o
				join mission_quiz_questions q on q.id = o.quiz_question_id where q.mission_id = ?
				""", bytes(mission.getId()))).isEqualTo(4);
	}

	@Test
	@DisplayName("상세 조회는 문항과 선택지를 표시 순서대로 돌려주고 정답 정보를 보존한다")
	void findsQuizDetailInDisplayOrder() {
		// given
		MissionDetail saved = saveQuizMission("퀴즈 미션");
		// when
		MissionDetail found = missionRepository.findDetailById(saved.getMission().getId()).orElseThrow();
		// then
		assertThat(found.getMission().getTitle()).isEqualTo("퀴즈 미션");
		assertThat(found.getMission().getMissionType()).isEqualTo(MissionType.QUIZ);
		assertThat(found.getMission().getStartsAt()).isEqualTo(OPENS);
		assertThat(found.getMission().getEndsAt()).isEqualTo(CLOSES);
		assertThat(found.getSurveyQuestions()).isEmpty();
		List<QuizQuestion> questions = found.getQuizQuestions();
		assertThat(questions).extracting(QuizQuestion::getDisplayOrder).containsExactly(1, 2, 3);
		assertThat(questions).extracting(QuizQuestion::getQuestionType)
				.containsExactly(QuizQuestionType.SINGLE_CHOICE, QuizQuestionType.SHORT_ANSWER, QuizQuestionType.OX);
		assertThat(questions.get(0).getOptions()).extracting(QuizOption::getOptionText).containsExactly("서울", "부산");
		assertThat(questions.get(0).getOptions()).extracting(QuizOption::isCorrect).containsExactly(true, false);
		assertThat(questions.get(0).getCorrectAnswer()).isNull();
		assertThat(questions.get(1).getCorrectAnswer()).isEqualTo("겟또");
		assertThat(questions.get(1).getOptions()).isEmpty();
	}

	@Test
	@DisplayName("설문 미션은 필수 여부와 선택지를 보존하고 설명 이미지 경로를 저장한다")
	void insertsAndFindsSurveyMission() {
		// given
		Mission withImage = Mission.register(rewardPolicyId, adminId, "설문 미션", "설명", MissionType.SURVEY, OPENS, CLOSES,
				"images/missions/survey.png");
		MissionDetail detail = new MissionDetail(withImage, List.of(), List.of(
				SurveyQuestion.of(SurveyQuestionType.FREE_TEXT, "의견은?", false, 1, List.of())));
		UUID missionId = transaction.execute(status -> missionRepository.insert(detail)).getMission().getId();
		MissionDetail survey = saveSurveyMission("만족도 설문");
		// when
		MissionDetail found = missionRepository.findDetailById(survey.getMission().getId()).orElseThrow();
		// then
		assertThat(missionRepository.findById(missionId).orElseThrow().getImageKey())
				.isEqualTo("images/missions/survey.png");
		assertThat(found.getQuizQuestions()).isEmpty();
		assertThat(found.getSurveyQuestions()).extracting(SurveyQuestion::isRequired).containsExactly(true, false);
		assertThat(found.getSurveyQuestions().get(0).getOptions()).hasSize(2);
		assertThat(found.getSurveyQuestions().get(1).getOptions()).isEmpty();
	}

	@Test
	@DisplayName("없는 미션은 비어 있는 결과로 돌려준다")
	void missingMissionIsEmpty() {
		// given
		// when
		// then
		assertThat(missionRepository.findById(UUID.randomUUID())).isEmpty();
		assertThat(missionRepository.findDetailById(UUID.randomUUID())).isEmpty();
	}

	@Test
	@DisplayName("목록은 유형·상태·제목 키워드로 거르고 운영 시작이 늦은 순으로 페이지를 나눈다")
	void searchesWithFiltersAndPaging() {
		// given
		UUID early = saveMission("가을 퀴즈", MissionType.QUIZ, Instant.parse("2026-09-01T00:00:00Z"));
		UUID middle = saveMission("가을 설문", MissionType.SURVEY, Instant.parse("2026-10-01T00:00:00Z"));
		UUID late = saveMission("겨울 퀴즈 100%", MissionType.QUIZ, Instant.parse("2026-11-01T00:00:00Z"));
		// when
		PageResult<Mission> all = missionRepository.search(MissionSearch.all(), new PageQuery(1, 1000));
		PageResult<Mission> firstPage = missionRepository.search(
				new MissionSearch(null, null, "퀴즈"), new PageQuery(1, 1));
		PageResult<Mission> secondPage = missionRepository.search(
				new MissionSearch(null, null, "퀴즈"), new PageQuery(2, 1));
		PageResult<Mission> quizzes = missionRepository.search(
				new MissionSearch(MissionType.QUIZ, MissionStatus.ACTIVE, null), new PageQuery(1, 1000));
		PageResult<Mission> escaped = missionRepository.search(
				new MissionSearch(null, null, "100%"), new PageQuery(1, 10));
		PageResult<Mission> noWildcard = missionRepository.search(
				new MissionSearch(null, null, "%"), new PageQuery(1, 10));
		PageResult<Mission> ended = missionRepository.search(
				new MissionSearch(null, MissionStatus.ENDED, "가을"), new PageQuery(1, 10));
		// then
		assertThat(all.getItems()).extracting(Mission::getId).containsSubsequence(late, middle, early);
		assertThat(firstPage.getTotalElements()).isEqualTo(2);
		assertThat(firstPage.getItems()).extracting(Mission::getId).containsExactly(late);
		assertThat(secondPage.getItems()).extracting(Mission::getId).containsExactly(early);
		assertThat(quizzes.getItems()).extracting(Mission::getId).contains(early, late).doesNotContain(middle);
		assertThat(escaped.getItems()).extracting(Mission::getId).containsExactly(late);
		assertThat(noWildcard.getItems()).extracting(Mission::getId).containsExactly(late);
		assertThat(ended.getItems()).isEmpty();
	}

	@Test
	@DisplayName("서버 기본 Locale이 터키어여도 영문 대소문자를 구분하지 않고 제목을 찾는다")
	void keywordSearchIgnoresDefaultLocale() {
		// given
		UUID island = saveMission("Island Quiz", MissionType.QUIZ, Instant.parse("2026-10-01T00:00:00Z"));
		Locale original = Locale.getDefault();
		// when
		PageResult<Mission> found;
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			found = missionRepository.search(new MissionSearch(null, null, "ISLAND"), new PageQuery(1, 10));
		} finally {
			Locale.setDefault(original);
		}
		// then
		assertThat(found.getItems()).extracting(Mission::getId).containsExactly(island);
	}

	@Test
	@DisplayName("종료 시각이 지났거나 정각인 운영 중 미션만 종료로 바꾸고 수정 시각을 기준 시각으로 쓰며 다시 실행하면 바꾸는 것이 없다")
	void endsOnlyMissionsWhoseEndHasPassed() {
		// given
		Instant startsAt = Instant.parse("2026-10-01T00:00:00Z");
		UUID endedAtBoundary = saveMission("정각에 끝나는 미션", MissionType.QUIZ, startsAt);
		UUID stillOpen = saveMission("아직 열린 미션", MissionType.SURVEY, startsAt.plusSeconds(3600));
		Instant now = startsAt.plusSeconds(86400);
		// when
		int first = transaction.execute(status -> missionRepository.endExpired(now));
		int second = transaction.execute(status -> missionRepository.endExpired(now));
		// then
		assertThat(first).isEqualTo(1);
		assertThat(second).isZero();
		assertThat(missionRepository.findById(endedAtBoundary).orElseThrow().getStatus()).isEqualTo(MissionStatus.ENDED);
		assertThat(missionRepository.findById(endedAtBoundary).orElseThrow().getUpdatedAt()).isEqualTo(now);
		assertThat(missionRepository.findById(stillOpen).orElseThrow().getStatus()).isEqualTo(MissionStatus.ACTIVE);
		assertThat(missionRepository.findById(stillOpen).orElseThrow().getUpdatedAt()).isEqualTo(clock.instant());
	}

	private UUID saveMission(String title, MissionType type, Instant startsAt) {
		Mission mission = Mission.register(rewardPolicyId, adminId, title, "설명", type, startsAt, startsAt.plusSeconds(86400), null);
		MissionDetail detail = type == MissionType.QUIZ
				? new MissionDetail(mission, List.of(QuizQuestion.of(QuizQuestionType.SHORT_ANSWER, "문제", 1, "정답", List.of())), List.of())
				: new MissionDetail(mission, List.of(), List.of(SurveyQuestion.of(SurveyQuestionType.FREE_TEXT, "질문", false, 1, List.of())));
		return transaction.execute(status -> missionRepository.insert(detail)).getMission().getId();
	}
}
