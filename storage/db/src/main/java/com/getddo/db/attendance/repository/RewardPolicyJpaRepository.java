package com.getddo.db.attendance.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.getddo.db.attendance.entity.RewardPolicyEntity;

public interface RewardPolicyJpaRepository extends JpaRepository<RewardPolicyEntity, UUID> {

	Page<RewardPolicyEntity> findByRewardType(String rewardType, Pageable pageable);

	/** 종료 시각이 없는 출석 정책을 잠가 읽는다. 열린 출석 정책은 DB 제약상 최대 하나다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from RewardPolicyEntity p where p.rewardType = 'ATTENDANCE' and p.effectiveUntil is null")
	Optional<RewardPolicyEntity> findOpenAttendanceForUpdate();

	/** 정책의 종료 시각을 즉시 반영한다. 새 열린 정책을 저장하기 전에 호출해 열린 정책이 둘이 되지 않게 한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RewardPolicyEntity p set p.effectiveUntil = :until where p.id = :id")
	int closeAt(@Param("id") UUID id, @Param("until") Instant until);

	/** 출석 정책 한 건을 잠가 읽는다. 수정 중 같은 정책을 동시에 고치지 못하게 한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from RewardPolicyEntity p where p.id = :id and p.rewardType = 'ATTENDANCE'")
	Optional<RewardPolicyEntity> findAttendanceByIdForUpdate(@Param("id") UUID id);

	/** 출석 정책의 보상 수량을 즉시 반영한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RewardPolicyEntity p set p.rewardTicketCount = :count where p.id = :id and p.rewardType = 'ATTENDANCE'")
	int updateRewardTicketCount(@Param("id") UUID id, @Param("count") int count);
}
