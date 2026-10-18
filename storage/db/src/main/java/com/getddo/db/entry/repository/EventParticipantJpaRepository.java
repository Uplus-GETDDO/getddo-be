package com.getddo.db.entry.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.getddo.db.entry.entity.EventParticipantEntity;

public interface EventParticipantJpaRepository extends JpaRepository<EventParticipantEntity, UUID> {

	Optional<EventParticipantEntity> findByEventIdAndUserId(UUID eventId, UUID userId);

	/** 이벤트의 응모자 수와 누적 차감 수량 합계. 한 행을 돌려주며 응모자가 없으면 0, 0이다. */
	@Query("select count(p), coalesce(sum(p.usedTicketCount), 0) from EventParticipantEntity p where p.eventId = :eventId")
	List<Object[]> summarizeByEventId(@Param("eventId") UUID eventId);

	/** 응모자 ID만 잠그지 않고 읽는다. 없는 키를 범위로 잠그면 gap lock이 생기므로 ID로 PK 잠금을 따로 건다. */
	@Query("select p.id from EventParticipantEntity p where p.eventId = :eventId and p.userId = :userId")
	Optional<UUID> findIdByEventIdAndUserId(@Param("eventId") UUID eventId, @Param("userId") UUID userId);

	/** 이벤트의 응모자 한 페이지. 정렬은 호출자가 Pageable로 정한다. */
	Page<EventParticipantEntity> findByEventId(UUID eventId, Pageable pageable);
}
