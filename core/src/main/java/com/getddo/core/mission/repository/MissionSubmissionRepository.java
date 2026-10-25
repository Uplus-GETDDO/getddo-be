package com.getddo.core.mission.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.MissionSubmission;

/** 미션 제출과 답변 저장소. 오답 재도전을 허용하므로 사용자·미션당 제출이 여러 건일 수 있다. */
public interface MissionSubmissionRepository {

	/**
	 * 제출과 답변을 함께 저장한다. 서버가 제출과 답변의 ID를 만든다. 여러 행을 쓰므로 호출하는 서비스의 트랜잭션 안에서
	 * 호출해야 중간에 실패해도 일부만 남지 않는다.
	 *
	 * @param submission ID가 없는 제출
	 * @return ID가 채워진 제출. 답변도 ID를 가진다
	 */
	MissionSubmission insert(MissionSubmission submission);

	/**
	 * 제출 한 건을 답변과 함께 조회한다.
	 *
	 * @param submissionId 제출 ID
	 * @return 없으면 비어 있다
	 */
	Optional<MissionSubmission> findById(UUID submissionId);

	/**
	 * 사용자가 해당 미션을 완료한 제출을 조회한다. 완료는 한 번만 인정하지만 저장소는 완료 제출의 중복을 막지 않으므로(보상
	 * 청구만 사용자·미션 유일), 여러 건이면 가장 먼저 저장된 한 건을 돌려준다. 중복 완료 방지는 제출을 처리하는 서비스의 몫이다.
	 *
	 * @param userId    사용자 ID
	 * @param missionId 미션 ID
	 * @return 완료한 제출이 없으면 비어 있다
	 */
	Optional<MissionSubmission> findCompleted(UUID userId, UUID missionId);

	/**
	 * 사용자의 제출을 최신순(같으면 ID 내림차순)으로 조회한다.
	 *
	 * @param userId    사용자 ID
	 * @param missionId 미션 ID
	 * @param page      페이지 조건
	 * @return 답변을 포함한 제출 목록
	 */
	PageResult<MissionSubmission> findByUser(UUID userId, UUID missionId, PageQuery page);

	/**
	 * 주어진 미션 중 사용자가 완료한 미션의 ID를 조회한다. 목록 화면의 완료 표시에 쓴다.
	 *
	 * @param userId     사용자 ID
	 * @param missionIds 확인할 미션 ID
	 * @return 완료한 미션 ID. 없으면 빈 집합
	 */
	Set<UUID> findCompletedMissionIds(UUID userId, Collection<UUID> missionIds);

	/**
	 * 사용자 행을 잠가({@code FOR UPDATE}) 같은 사용자의 퀴즈 제출 처리를 직렬화한다. 잠금은 호출한 트랜잭션이 끝날 때 풀리므로
	 * 호출하는 서비스의 트랜잭션 안에서 호출해야 한다. 먼저 잠근 요청이 커밋한 정답 진행을 뒤 요청이 읽을 수 있어, 문항별로 나뉘어
	 * 동시에 온 제출도 완료를 놓치지 않는다. 잠그기 위해 사용자 행을 조회하지만 그 값을 업무에 쓰거나 바꾸지는 않는다.
	 *
	 * @param userId 사용자 ID
	 */
	void lockUser(UUID userId);
}
