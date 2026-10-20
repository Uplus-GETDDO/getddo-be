package com.getddo.core.attendance.repository;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.getddo.core.attendance.domain.AttendanceRewardClaim;
import com.getddo.core.attendance.domain.AttendanceRewardType;

/** 출석 보상 청구 저장소. 일일 보상과 연속 출석 단계 보상을 구분해 저장하며, 청구는 응모권 지급의 근거가 된다. */
public interface AttendanceRewardClaimRepository {

	/**
	 * 출석 한 건으로 확정된 청구를 조회한다.
	 *
	 * @param attendanceId 출석 ID
	 * @return 일일 보상이 먼저, 단계 보상은 단계 일수 오름차순
	 */
	List<AttendanceRewardClaim> findByAttendanceId(UUID attendanceId);

	/**
	 * 사용자가 해당 월에 받은 연속 출석 단계 보상 청구를 조회한다.
	 *
	 * @param userId 사용자 ID
	 * @param month  청구 기준일이 속한 KST 월
	 * @return 단계 일수 오름차순. 없으면 빈 목록
	 */
	List<AttendanceRewardClaim> findStreakClaims(UUID userId, YearMonth month);

	/**
	 * 같은 중복 방지 키의 청구가 이미 있는지 확인한다. 같은 달 같은 단계 보상을 다시 청구하지 않으려고 쓴다.
	 *
	 * @param userId     사용자 ID
	 * @param rewardType 보상 종류
	 * @param sourceKey  중복 방지 키
	 * @return 이미 있으면 true
	 */
	boolean exists(UUID userId, AttendanceRewardType rewardType, String sourceKey);

	/**
	 * 새 청구를 저장한다. 응모권 지급 전에 호출하며, 지급 쪽이 같은 트랜잭션에서 이 청구를 읽는다.
	 *
	 * @param claim ID가 없는 청구
	 * @return ID가 채워진 청구
	 */
	AttendanceRewardClaim insert(AttendanceRewardClaim claim);
}
