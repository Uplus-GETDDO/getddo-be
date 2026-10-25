package com.getddo.db.mission.repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.MissionSubmission;
import com.getddo.core.mission.domain.QuizAnswer;
import com.getddo.core.mission.domain.SurveyAnswer;
import com.getddo.core.mission.repository.MissionSubmissionRepository;
import com.getddo.db.common.pagination.PageOffsets;
import com.getddo.db.mission.entity.MissionSubmissionEntity;
import com.getddo.db.mission.entity.QuizAnswerEntity;
import com.getddo.db.mission.entity.SurveyAnswerEntity;
import com.getddo.db.mission.mapper.MissionMapper;

@Repository
@RequiredArgsConstructor
public class MissionSubmissionRepositoryImpl implements MissionSubmissionRepository {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final MissionSubmissionJpaRepository submissionJpaRepository;
	private final QuizAnswerJpaRepository quizAnswerJpaRepository;
	private final SurveyAnswerJpaRepository surveyAnswerJpaRepository;
	private final MissionMapper mapper;

	@Override
	public MissionSubmission insert(MissionSubmission submission) {
		MissionSubmissionEntity saved = submissionJpaRepository.saveAndFlush(mapper.toEntity(submission));
		List<QuizAnswer> quizAnswers = submission.getQuizAnswers().stream()
				.map(answer -> mapper.toEntity(answer, saved.getId(), saved.getMissionId()))
				.map(quizAnswerJpaRepository::saveAndFlush)
				.map(mapper::toDomain)
				.toList();
		List<SurveyAnswer> surveyAnswers = submission.getSurveyAnswers().stream()
				.map(answer -> mapper.toEntity(answer, saved.getId(), saved.getMissionId()))
				.map(surveyAnswerJpaRepository::saveAndFlush)
				.map(mapper::toDomain)
				.toList();
		return mapper.toDomain(saved, quizAnswers, surveyAnswers);
	}

	@Override
	public Optional<MissionSubmission> findById(UUID submissionId) {
		return submissionJpaRepository.findById(submissionId).map(entity -> withAnswers(List.of(entity)).get(0));
	}

	@Override
	public Optional<MissionSubmission> findCompleted(UUID userId, UUID missionId) {
		return submissionJpaRepository
				.findFirstByUserIdAndMissionIdAndCompletedTrueOrderByCreatedAtAscIdAsc(userId, missionId)
				.map(entity -> withAnswers(List.of(entity)).get(0));
	}

	@Override
	public PageResult<MissionSubmission> findByUser(UUID userId, UUID missionId, PageQuery page) {
		// JPA는 오프셋을 int로 바꾸므로 범위를 넘는 페이지는 조회하지 않고 전체 건수만 확인한다.
		boolean beyondIntOffset = PageOffsets.exceedsIntRange(page);
		PageRequest pageable = PageOffsets.toPageRequest(page, NEWEST_FIRST);
		Page<MissionSubmissionEntity> found = submissionJpaRepository.findByUserIdAndMissionId(userId, missionId,
				pageable);
		List<MissionSubmission> items = beyondIntOffset ? List.of() : withAnswers(found.getContent());
		return new PageResult<>(items, page.getPage(), page.getSize(), found.getTotalElements());
	}

	@Override
	public Set<UUID> findCompletedMissionIds(UUID userId, Collection<UUID> missionIds) {
		if (missionIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(submissionJpaRepository.findCompletedMissionIds(userId, missionIds));
	}

	@Override
	public void lockUser(UUID userId) {
		submissionJpaRepository.lockUser(userId);
	}

	/** 제출 목록의 답변을 두 번의 조회로 모아 붙인다. 입력 순서를 유지한다. */
	private List<MissionSubmission> withAnswers(List<MissionSubmissionEntity> submissions) {
		if (submissions.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = submissions.stream().map(MissionSubmissionEntity::getId).toList();
		Map<UUID, List<QuizAnswer>> quizAnswers = quizAnswerJpaRepository.findBySubmissionIdInOrderByIdAsc(ids)
				.stream()
				.collect(Collectors.groupingBy(QuizAnswerEntity::getSubmissionId,
						Collectors.mapping(mapper::toDomain, Collectors.toList())));
		Map<UUID, List<SurveyAnswer>> surveyAnswers = surveyAnswerJpaRepository.findBySubmissionIdInOrderByIdAsc(ids)
				.stream()
				.collect(Collectors.groupingBy(SurveyAnswerEntity::getSubmissionId,
						Collectors.mapping(mapper::toDomain, Collectors.toList())));
		return submissions.stream()
				.map(entity -> mapper.toDomain(entity, quizAnswers.getOrDefault(entity.getId(), List.of()),
						surveyAnswers.getOrDefault(entity.getId(), List.of())))
				.toList();
	}
}
