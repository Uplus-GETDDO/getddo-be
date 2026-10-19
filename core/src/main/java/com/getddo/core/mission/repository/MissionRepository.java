package com.getddo.core.mission.repository;

import java.util.Optional;
import java.util.UUID;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionSearch;

/** 미션과 문항·선택지 저장소. 미션 등록과 사용자·관리자 조회에 쓴다. */
public interface MissionRepository {

	/**
	 * 미션을 문항·선택지와 함께 한 번에 저장한다. 서버가 모든 ID를 만든다. 여러 행을 쓰므로 호출하는 서비스의 트랜잭션
	 * 안에서 호출해야 중간에 실패해도 일부만 남지 않는다.
	 *
	 * @param detail ID가 없는 미션과 문항
	 * @return ID가 채워진 미션과 문항
	 */
	MissionDetail insert(MissionDetail detail);

	/**
	 * 미션 한 건을 문항 없이 조회한다.
	 *
	 * @param missionId 미션 ID
	 * @return 없으면 비어 있다
	 */
	Optional<Mission> findById(UUID missionId);

	/**
	 * 미션 한 건을 문항·선택지와 함께 조회한다. 문항과 선택지는 표시 순서 오름차순이다.
	 *
	 * @param missionId 미션 ID
	 * @return 없으면 비어 있다
	 */
	Optional<MissionDetail> findDetailById(UUID missionId);

	/**
	 * 조건에 맞는 미션을 운영 시작 시각 내림차순(같으면 ID 내림차순)으로 조회한다.
	 *
	 * @param search 조회 조건
	 * @param page   페이지 조건
	 * @return 문항 없는 미션 목록
	 */
	PageResult<Mission> search(MissionSearch search, PageQuery page);
}
