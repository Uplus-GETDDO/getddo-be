package com.getddo.db.mission.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionSearch;
import com.getddo.core.mission.domain.QuizOption;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.domain.SurveyOption;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.repository.MissionRepository;
import com.getddo.db.common.pagination.PageOffsets;
import com.getddo.db.mission.entity.MissionEntity;
import com.getddo.db.mission.entity.MissionQuizQuestionEntity;
import com.getddo.db.mission.entity.QuizQuestionOptionEntity;
import com.getddo.db.mission.entity.SurveyQuestionEntity;
import com.getddo.db.mission.entity.SurveyQuestionOptionEntity;
import com.getddo.db.mission.mapper.MissionMapper;

@Repository
@RequiredArgsConstructor
public class MissionRepositoryImpl implements MissionRepository {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("startsAt"), Sort.Order.desc("id"));

	private final MissionJpaRepository missionJpaRepository;
	private final MissionQuizQuestionJpaRepository quizQuestionJpaRepository;
	private final QuizQuestionOptionJpaRepository quizOptionJpaRepository;
	private final SurveyQuestionJpaRepository surveyQuestionJpaRepository;
	private final SurveyQuestionOptionJpaRepository surveyOptionJpaRepository;
	private final MissionMapper mapper;

	@Override
	public MissionDetail insert(MissionDetail detail) {
		MissionEntity savedMission = missionJpaRepository.saveAndFlush(mapper.toEntity(detail.getMission()));
		UUID missionId = savedMission.getId();
		List<QuizQuestion> quizQuestions = new ArrayList<>();
		for (QuizQuestion question : detail.getQuizQuestions()) {
			MissionQuizQuestionEntity savedQuestion = quizQuestionJpaRepository
					.saveAndFlush(mapper.toEntity(question, missionId));
			List<QuizOption> options = question.getOptions().stream()
					.map(option -> mapper.toEntity(option, savedQuestion.getId()))
					.map(quizOptionJpaRepository::saveAndFlush)
					.map(mapper::toDomain)
					.toList();
			quizQuestions.add(mapper.toDomain(savedQuestion, options));
		}
		List<SurveyQuestion> surveyQuestions = new ArrayList<>();
		for (SurveyQuestion question : detail.getSurveyQuestions()) {
			SurveyQuestionEntity savedQuestion = surveyQuestionJpaRepository
					.saveAndFlush(mapper.toEntity(question, missionId));
			List<SurveyOption> options = question.getOptions().stream()
					.map(option -> mapper.toEntity(option, savedQuestion.getId()))
					.map(surveyOptionJpaRepository::saveAndFlush)
					.map(mapper::toDomain)
					.toList();
			surveyQuestions.add(mapper.toDomain(savedQuestion, options));
		}
		return new MissionDetail(mapper.toDomain(savedMission), quizQuestions, surveyQuestions);
	}

	@Override
	public Optional<Mission> findById(UUID missionId) {
		return missionJpaRepository.findById(missionId).map(mapper::toDomain);
	}

	@Override
	public Optional<MissionDetail> findDetailById(UUID missionId) {
		return missionJpaRepository.findById(missionId).map(this::toDetail);
	}

	@Override
	public PageResult<Mission> search(MissionSearch search, PageQuery page) {
		// JPA는 오프셋을 int로 바꾸므로 범위를 넘는 페이지는 조회하지 않고 전체 건수만 확인한다.
		boolean beyondIntOffset = PageOffsets.exceedsIntRange(page);
		PageRequest pageable = PageOffsets.toPageRequest(page, NEWEST_FIRST);
		Page<MissionEntity> found = missionJpaRepository.search(search.getMissionType(), search.getStatus(),
				likePattern(search.getKeyword()), search.getStartsFrom(), search.getStartsBefore(), pageable);
		List<Mission> items = beyondIntOffset ? List.of() : found.getContent().stream().map(mapper::toDomain).toList();
		return new PageResult<>(items, page.getPage(), page.getSize(), found.getTotalElements());
	}

	private MissionDetail toDetail(MissionEntity mission) {
		return new MissionDetail(mapper.toDomain(mission), quizQuestions(mission.getId()),
				surveyQuestions(mission.getId()));
	}

	private List<QuizQuestion> quizQuestions(UUID missionId) {
		List<MissionQuizQuestionEntity> questions = quizQuestionJpaRepository
				.findByMissionIdOrderByDisplayOrderAsc(missionId);
		Map<UUID, List<QuizOption>> options = groupBy(
				quizOptionJpaRepository.findByQuizQuestionIdInOrderByDisplayOrderAsc(
						questions.stream().map(MissionQuizQuestionEntity::getId).toList()),
				QuizQuestionOptionEntity::getQuizQuestionId, mapper::toDomain);
		return questions.stream()
				.map(question -> mapper.toDomain(question, options.getOrDefault(question.getId(), List.of())))
				.toList();
	}

	private List<SurveyQuestion> surveyQuestions(UUID missionId) {
		List<SurveyQuestionEntity> questions = surveyQuestionJpaRepository
				.findByMissionIdOrderByDisplayOrderAsc(missionId);
		Map<UUID, List<SurveyOption>> options = groupBy(
				surveyOptionJpaRepository.findBySurveyQuestionIdInOrderByDisplayOrderAsc(
						questions.stream().map(SurveyQuestionEntity::getId).toList()),
				SurveyQuestionOptionEntity::getSurveyQuestionId, mapper::toDomain);
		return questions.stream()
				.map(question -> mapper.toDomain(question, options.getOrDefault(question.getId(), List.of())))
				.toList();
	}

	private static <E, D> Map<UUID, List<D>> groupBy(Collection<E> entities, Function<E, UUID> parentId,
			Function<E, D> toDomain) {
		return entities.stream().collect(
				Collectors.groupingBy(parentId, Collectors.mapping(toDomain, Collectors.toList())));
	}

	/** 키워드를 소문자 부분 일치 LIKE 패턴으로 바꾼다. 키워드 안의 {@code %}, {@code _}, {@code \}는 글자 그대로 찾는다. */
	private static String likePattern(String keyword) {
		if (keyword == null) {
			return null;
		}
		String escaped = keyword.toLowerCase(Locale.ROOT)
				.replace("\\", "\\\\")
				.replace("%", "\\%")
				.replace("_", "\\_");
		return "%" + escaped + "%";
	}
}
