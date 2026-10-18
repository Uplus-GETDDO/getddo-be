package com.getddo.db.mission.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.getddo.core.mission.domain.MissionStatus;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.db.mission.entity.MissionEntity;

public interface MissionJpaRepository extends JpaRepository<MissionEntity, UUID> {

	/** 값이 null인 조건은 적용하지 않는다. {@code titlePattern}은 소문자로 바꾼 LIKE 패턴이며 {@code \}가 이스케이프 문자다. */
	@Query("""
			select m from MissionEntity m
			where (:missionType is null or m.missionType = :missionType)
			  and (:status is null or m.status = :status)
			  and (:titlePattern is null or lower(m.title) like :titlePattern escape '\\')
			  and (:startsFrom is null or m.startsAt >= :startsFrom)
			  and (:startsBefore is null or m.startsAt < :startsBefore)
			""")
	Page<MissionEntity> search(@Param("missionType") MissionType missionType, @Param("status") MissionStatus status,
			@Param("titlePattern") String titlePattern, @Param("startsFrom") Instant startsFrom,
			@Param("startsBefore") Instant startsBefore, Pageable pageable);
}
