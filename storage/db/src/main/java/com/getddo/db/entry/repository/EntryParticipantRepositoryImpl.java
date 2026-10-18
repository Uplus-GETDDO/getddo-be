package com.getddo.db.entry.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.entry.domain.EntryParticipationTotals;
import com.getddo.core.entry.repository.EntryParticipantRepository;
import com.getddo.db.common.pagination.PageOffsets;
import com.getddo.db.entry.entity.EventParticipantEntity;
import com.getddo.db.entry.mapper.EntryMapper;

@Repository
@RequiredArgsConstructor
public class EntryParticipantRepositoryImpl implements EntryParticipantRepository {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final EventParticipantJpaRepository jpaRepository;
	private final EntryMapper mapper;
	private final EntityManager entityManager;

	@Override
	public Optional<EntryParticipant> find(UUID eventId, UUID userId) {
		return jpaRepository.findByEventIdAndUserId(eventId, userId).map(mapper::toDomain);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>ID를 잠그지 않고 찾은 뒤 그 행을 PK로 잠그고 영속성 컨텍스트의 값을 DB 최신 값으로 덮어쓴다. 호출자가 같은
	 * 트랜잭션에서 이미 읽어 둔 값으로 누적 상한을 판정하지 않기 위해서다.</p>
	 */
	@Override
	public Optional<EntryParticipant> findForUpdate(UUID eventId, UUID userId) {
		return jpaRepository.findIdByEventIdAndUserId(eventId, userId)
				.flatMap(this::lockAndRefresh)
				.map(mapper::toDomain);
	}

	@Override
	public EntryParticipant create(EntryParticipant participant) {
		return mapper.toDomain(jpaRepository.saveAndFlush(mapper.toEntity(participant)));
	}

	@Override
	public EntryParticipationTotals summarize(UUID eventId) {
		Object[] row = jpaRepository.summarizeByEventId(eventId).getFirst();
		return new EntryParticipationTotals(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
	}

	@Override
	public PageResult<EntryParticipant> findByEvent(UUID eventId, PageQuery page) {
		// JPA는 오프셋을 int로 바꾸므로 범위를 넘는 페이지는 조회하지 않고 전체 건수만 확인한다.
		boolean beyondIntOffset = PageOffsets.exceedsIntRange(page);
		Page<EventParticipantEntity> found = jpaRepository.findByEventId(eventId,
				PageOffsets.toPageRequest(page, NEWEST_FIRST));
		List<EntryParticipant> items = beyondIntOffset ? List.of()
				: found.getContent().stream().map(mapper::toDomain).toList();
		return new PageResult<>(items, page.getPage(), page.getSize(), found.getTotalElements());
	}

	/** 이미 잠가 읽었거나 방금 저장한 영속성 컨텍스트의 Entity에 더하며, 커밋 때 UPDATE로 나간다. */
	@Override
	public void addUsedTicketCount(UUID participantId, long amount) {
		EventParticipantEntity entity = jpaRepository.findById(participantId)
				.orElseThrow(() -> new IllegalStateException("누적 수량을 늘릴 응모자가 없다."));
		entity.addUsedTicketCount(amount);
	}

	private Optional<EventParticipantEntity> lockAndRefresh(UUID id) {
		entityManager.flush();
		EventParticipantEntity entity = entityManager.getReference(EventParticipantEntity.class, id);
		try {
			entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
		} catch (EntityNotFoundException e) {
			return Optional.empty();
		}
		return Optional.of(entity);
	}
}
