package com.getddo.db.attendance.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

import com.getddo.core.attendance.domain.DailyRewardPolicy;
import com.getddo.core.attendance.domain.StreakMilestone;
import com.getddo.core.attendance.domain.StreakPolicySet;
import com.getddo.core.attendance.repository.AttendancePolicyRepository;
import com.getddo.db.common.util.UuidBinary;

/**
 * 출석 보상 정책 조회 구현.
 *
 * <p>일일 보상 정책({@code reward_policies})은 보상 정책 설정 담당 영역의 테이블이라 Entity를 만들지 않고 읽기만 한다.
 * 연속 출석 정책 묶음도 이번 범위에서는 조회만 하므로 네이티브 SQL로 읽는다. 값은 모두 파라미터로 바인딩한다.</p>
 *
 * <p>{@code ...ForShare} 메서드는 같은 쿼리에 {@code FOR SHARE}를 붙여 읽은 정책 행을 공유 잠금으로 잡는다. MySQL은
 * {@code LIMIT}이 {@code FOR SHARE}보다 앞에 와야 해서 잠금 변형은 {@code LIMIT 1}을 쿼리에 직접 쓴다. 연속 정책 단계는
 * 묶음 행의 잠금을 얻은 뒤 같은 방식으로 잠그며 읽는다.</p>
 */
@Repository
@RequiredArgsConstructor
public class AttendancePolicyRepositoryImpl implements AttendancePolicyRepository {

	private static final String DAILY_POLICY_SQL = """
			select id, reward_ticket_count
			from reward_policies
			where reward_type = 'ATTENDANCE'
			  and effective_from <= :at
			  and (effective_until is null or effective_until > :at)
			order by effective_from desc
			""";
	private static final String STREAK_SET_BY_MONTH_SQL = """
			select id from attendance_streak_policy_sets
			where effective_month <= :month
			order by effective_month desc
			""";
	private static final String STREAK_SET_BY_ID_SQL = "select id from attendance_streak_policy_sets where id = :id";
	private static final String MILESTONES_SQL = """
			select id, milestone_days, reward_ticket_count
			from attendance_streak_policies
			where policy_set_id = :policySetId
			""";
	private static final String LIMIT_ONE_FOR_SHARE = " limit 1 for share";
	private static final String FOR_SHARE = " for share";

	private final EntityManager entityManager;

	@Override
	public Optional<DailyRewardPolicy> findDailyPolicy(Instant at) {
		return findDaily(tupleQuery(DAILY_POLICY_SQL).setMaxResults(1), at);
	}

	@Override
	public Optional<DailyRewardPolicy> findDailyPolicyForShare(Instant at) {
		return findDaily(tupleQuery(DAILY_POLICY_SQL + LIMIT_ONE_FOR_SHARE), at);
	}

	@Override
	public Optional<StreakPolicySet> findStreakPolicySet(LocalDate month) {
		return findStreakByMonth(tupleQuery(STREAK_SET_BY_MONTH_SQL).setMaxResults(1), month, false);
	}

	@Override
	public Optional<StreakPolicySet> findStreakPolicySetForShare(LocalDate month) {
		return findStreakByMonth(tupleQuery(STREAK_SET_BY_MONTH_SQL + LIMIT_ONE_FOR_SHARE), month, true);
	}

	@Override
	public Optional<StreakPolicySet> findStreakPolicySetById(UUID policySetId) {
		return findStreakById(tupleQuery(STREAK_SET_BY_ID_SQL), policySetId, false);
	}

	@Override
	public Optional<StreakPolicySet> findStreakPolicySetByIdForShare(UUID policySetId) {
		return findStreakById(tupleQuery(STREAK_SET_BY_ID_SQL + FOR_SHARE), policySetId, true);
	}

	private static Optional<DailyRewardPolicy> findDaily(NativeQuery<Tuple> query, Instant at) {
		List<Tuple> rows = query
				.addScalar("id", UUID.class)
				.addScalar("reward_ticket_count", Integer.class)
				.setParameter("at", at)
				.getResultList();
		return rows.stream().findFirst().map(row ->
				new DailyRewardPolicy(row.get("id", UUID.class), row.get("reward_ticket_count", Integer.class)));
	}

	private Optional<StreakPolicySet> findStreakByMonth(NativeQuery<Tuple> query, LocalDate month,
			boolean forShare) {
		List<Tuple> rows = query
				.addScalar("id", UUID.class)
				.setParameter("month", month)
				.getResultList();
		return rows.stream().findFirst().map(row -> withMilestones(row.get("id", UUID.class), forShare));
	}

	private Optional<StreakPolicySet> findStreakById(NativeQuery<Tuple> query, UUID policySetId,
			boolean forShare) {
		List<Tuple> rows = query
				.addScalar("id", UUID.class)
				.setParameter("id", UuidBinary.toBytes(policySetId))
				.getResultList();
		return rows.stream().findFirst().map(row -> withMilestones(row.get("id", UUID.class), forShare));
	}

	private StreakPolicySet withMilestones(UUID policySetId, boolean forShare) {
		List<StreakMilestone> milestones = tupleQuery(forShare ? MILESTONES_SQL + FOR_SHARE : MILESTONES_SQL)
				.addScalar("id", UUID.class)
				.addScalar("milestone_days", Integer.class)
				.addScalar("reward_ticket_count", Integer.class)
				.setParameter("policySetId", UuidBinary.toBytes(policySetId))
				.getResultList().stream()
				.map(row -> new StreakMilestone(row.get("id", UUID.class), row.get("milestone_days", Integer.class),
						row.get("reward_ticket_count", Integer.class)))
				.toList();
		return new StreakPolicySet(policySetId, milestones);
	}

	@SuppressWarnings("unchecked")
	private NativeQuery<Tuple> tupleQuery(String sql) {
		return entityManager.createNativeQuery(sql, Tuple.class).unwrap(NativeQuery.class);
	}
}
