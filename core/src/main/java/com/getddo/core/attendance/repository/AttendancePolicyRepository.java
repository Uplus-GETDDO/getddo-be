package com.getddo.core.attendance.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.getddo.core.attendance.domain.DailyRewardPolicy;
import com.getddo.core.attendance.domain.StreakPolicySet;

/**
 * 출석 보상 정책 조회. 정책은 읽기만 하며 등록·변경은 관리자 정책 기능이 맡는다.
 *
 * <p>{@code ...ForShare} 메서드는 읽은 정책 행을 공유 잠금({@code FOR SHARE})으로 잡는다. 출석 기록처럼 읽은 정책으로
 * 보상을 확정하는 쓰기 경로가 호출자 트랜잭션 안에서 쓴다. 관리자의 예약 정책 수정이 같은 행을 쓰기 잠금으로 잡으므로,
 * 수정이 끝날 때까지 기다린 뒤 수정된 값을 읽고 수정은 출석 처리가 끝날 때까지 기다린다. 현황 조회처럼 읽기만 하는 경로는 잠금
 * 없는 메서드를 쓴다.</p>
 */
public interface AttendancePolicyRepository {

	/**
	 * 주어진 시각에 적용 중인 일일 출석 보상 정책을 조회한다.
	 *
	 * @param at 출석 시각
	 * @return {@code effective_from <= at < effective_until}인 ATTENDANCE 정책. 여럿이면 가장 늦게 시작한 정책
	 */
	Optional<DailyRewardPolicy> findDailyPolicy(Instant at);

	/**
	 * 해당 월에 적용할 연속 출석 정책 묶음을 조회한다.
	 *
	 * @param month KST 기준월의 1일
	 * @return 적용월이 {@code month} 이하인 묶음 중 가장 최근 묶음과 그 단계. 없으면 빈 값
	 */
	Optional<StreakPolicySet> findStreakPolicySet(LocalDate month);

	/**
	 * ID로 연속 출석 정책 묶음을 조회한다. 월 중에는 현황에 기록한 묶음을 계속 쓰기 위해 사용한다.
	 *
	 * @param policySetId 정책 묶음 ID
	 * @return 묶음과 그 단계. 없으면 빈 값
	 */
	Optional<StreakPolicySet> findStreakPolicySetById(UUID policySetId);

	/** {@link #findDailyPolicy}와 같되 읽은 정책 행을 공유 잠금으로 잡는다. 호출자 트랜잭션 안에서 호출한다. */
	Optional<DailyRewardPolicy> findDailyPolicyForShare(Instant at);

	/**
	 * {@link #findStreakPolicySet}과 같되 읽은 묶음 행을 공유 잠금으로 잡는다. 단계는 묶음 잠금을 얻은 뒤에 읽어 관리자
	 * 수정이 끝난 값을 본다. 호출자 트랜잭션 안에서 호출한다.
	 */
	Optional<StreakPolicySet> findStreakPolicySetForShare(LocalDate month);

	/** {@link #findStreakPolicySetById}와 같되 읽은 묶음 행을 공유 잠금으로 잡는다. 호출자 트랜잭션 안에서 호출한다. */
	Optional<StreakPolicySet> findStreakPolicySetByIdForShare(UUID policySetId);
}
