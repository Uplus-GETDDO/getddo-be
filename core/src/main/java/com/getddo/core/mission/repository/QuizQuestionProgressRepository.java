package com.getddo.core.mission.repository;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import com.getddo.core.mission.domain.QuizQuestionProgress;

/** 퀴즈 문항의 정답 완료 기록 저장소. 사용자·문항당 한 건만 저장할 수 있다. */
public interface QuizQuestionProgressRepository {

	/**
	 * 정답으로 완료한 문항을 기록한다. 사용자·문항 단위로 한 번만 저장할 수 있다.
	 *
	 * @param progress ID가 없는 진행 기록
	 * @return ID가 채워진 진행 기록
	 */
	QuizQuestionProgress insert(QuizQuestionProgress progress);

	/**
	 * 주어진 문항 중 사용자가 이미 정답으로 완료한 문항의 ID를 조회한다.
	 *
	 * @param userId      사용자 ID
	 * @param questionIds 확인할 퀴즈 문항 ID
	 * @return 정답으로 완료한 문항 ID. 없으면 빈 집합
	 */
	Set<UUID> findCorrectQuestionIds(UUID userId, Collection<UUID> questionIds);
}
