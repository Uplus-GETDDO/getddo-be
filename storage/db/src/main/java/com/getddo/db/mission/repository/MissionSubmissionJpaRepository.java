package com.getddo.db.mission.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.getddo.db.mission.entity.MissionSubmissionEntity;
import com.getddo.db.user.entity.UserEntity;

public interface MissionSubmissionJpaRepository extends JpaRepository<MissionSubmissionEntity, UUID> {

	Optional<MissionSubmissionEntity> findFirstByUserIdAndMissionIdAndCompletedTrueOrderByCreatedAtAscIdAsc(UUID userId,
			UUID missionId);

	Page<MissionSubmissionEntity> findByUserIdAndMissionId(UUID userId, UUID missionId, Pageable pageable);

	@Query("""
			select distinct s.missionId from MissionSubmissionEntity s
			where s.userId = :userId and s.completed = true and s.missionId in :missionIds
			""")
	List<UUID> findCompletedMissionIds(@Param("userId") UUID userId, @Param("missionIds") Collection<UUID> missionIds);

	/** 사용자 행을 {@code SELECT ... FOR UPDATE}로 잠근다. 같은 사용자의 퀴즈 제출을 직렬화하는 데만 쓴다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UserEntity u where u.id = :userId")
	Optional<UserEntity> lockUser(@Param("userId") UUID userId);
}
