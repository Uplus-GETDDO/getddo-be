package com.getddo.db.entry.repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.AdminEntryFilter;
import com.getddo.core.entry.domain.Entry;
import com.getddo.core.entry.domain.EntryHistoryQuery;
import com.getddo.core.entry.domain.EntryStatus;
import com.getddo.core.entry.domain.EntrySummary;
import com.getddo.core.entry.repository.EntryRepository;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.db.common.pagination.PageOffsets;
import com.getddo.db.common.util.UuidBinary;
import com.getddo.db.entry.mapper.EntryMapper;

/**
 * 응모 결과 저장소 구현. 이력 조회는 이벤트 제목을 함께 읽어야 하지만 이벤트 테이블은 이벤트 도메인이 소유하므로, 이벤트
 * Entity 대신 네이티브 SQL로 필요한 컬럼만 읽는다. SQL 문장은 고정이고 값은 모두 파라미터로 바인딩한다.
 */
@Repository
@RequiredArgsConstructor
public class EntryRepositoryImpl implements EntryRepository {

	private static final String HISTORY_SELECT = """
			select e.id, e.event_id, ev.title, e.participant_id, e.user_id, e.status, e.rejection_code,
			       e.rejection_reason, e.requested_ticket_count, e.deducted_ticket_count, e.requested_bronze_count,
			       e.requested_silver_count, e.requested_gold_count, e.requested_platinum_count, e.created_at
			from event_entries e join events ev on ev.id = e.event_id
			""";
	private static final String HISTORY_COUNT =
			"select count(*) from event_entries e join events ev on ev.id = e.event_id";
	private static final String HISTORY_ORDER = " order by e.created_at desc, e.id desc";

	private final EventEntryJpaRepository jpaRepository;
	private final EntryMapper mapper;
	private final EntityManager entityManager;

	@Override
	public Optional<Entry> findById(UUID id) {
		return jpaRepository.findById(id).map(mapper::toDomain);
	}

	@Override
	public Entry save(Entry entry) {
		jpaRepository.saveAndFlush(mapper.toEntity(entry));
		return entry;
	}

	@Override
	public PageResult<EntrySummary> findByUser(UUID userId, EntryHistoryQuery query, PageQuery page) {
		return search(userId, query.getEventId(), query.getStatus(), query.getFrom(), query.getTo(), page);
	}

	@Override
	public PageResult<EntrySummary> findAll(AdminEntryFilter filter, PageQuery page) {
		return search(filter.getUserId(), filter.getEventId(), filter.getStatus(), filter.getFrom(), filter.getTo(),
				page);
	}

	private PageResult<EntrySummary> search(UUID userId, UUID eventId, EntryStatus status, Instant from, Instant to,
			PageQuery page) {
		Map<String, Object> parameters = new HashMap<>();
		String where = where(userId, eventId, status, from, to, parameters);
		Query count = entityManager.createNativeQuery(HISTORY_COUNT + where);
		parameters.forEach(count::setParameter);
		long total = ((Number) count.getSingleResult()).longValue();
		// 시작 위치가 전체 건수를 넘으면 항목이 없다. JPA가 int로 바꿀 수 없는 위치도 같은 경우다.
		if (page.offset() >= total || PageOffsets.exceedsIntRange(page)) {
			return new PageResult<>(List.of(), page.getPage(), page.getSize(), total);
		}
		@SuppressWarnings("unchecked")
		NativeQuery<Tuple> select = entityManager.createNativeQuery(HISTORY_SELECT + where + HISTORY_ORDER, Tuple.class)
				.unwrap(NativeQuery.class)
				.addScalar("id", UUID.class)
				.addScalar("event_id", UUID.class)
				.addScalar("title", String.class)
				.addScalar("participant_id", UUID.class)
				.addScalar("user_id", UUID.class)
				.addScalar("status", String.class)
				.addScalar("rejection_code", String.class)
				.addScalar("rejection_reason", String.class)
				.addScalar("requested_ticket_count", Integer.class)
				.addScalar("deducted_ticket_count", Integer.class)
				.addScalar("requested_bronze_count", Integer.class)
				.addScalar("requested_silver_count", Integer.class)
				.addScalar("requested_gold_count", Integer.class)
				.addScalar("requested_platinum_count", Integer.class)
				.addScalar("created_at", Instant.class);
		parameters.forEach(select::setParameter);
		select.setFirstResult((int) page.offset());
		select.setMaxResults(page.getSize());
		List<EntrySummary> items = select.getResultList().stream().map(EntryRepositoryImpl::toSummary).toList();
		return new PageResult<>(items, page.getPage(), page.getSize(), total);
	}

	/** 조회 조건의 WHERE 절을 만들고 바인딩할 값을 채운다. 값이 없는 조건은 넣지 않는다. */
	private static String where(UUID userId, UUID eventId, EntryStatus status, Instant from, Instant to,
			Map<String, Object> parameters) {
		StringBuilder where = new StringBuilder(" where 1 = 1");
		if (userId != null) {
			where.append(" and e.user_id = :userId");
			parameters.put("userId", UuidBinary.toBytes(userId));
		}
		if (eventId != null) {
			where.append(" and e.event_id = :eventId");
			parameters.put("eventId", UuidBinary.toBytes(eventId));
		}
		if (status != null) {
			where.append(" and e.status = :status");
			parameters.put("status", status.name());
		}
		if (from != null) {
			where.append(" and e.created_at >= :from");
			parameters.put("from", from);
		}
		if (to != null) {
			where.append(" and e.created_at < :to");
			parameters.put("to", to);
		}
		return where.toString();
	}

	private static EntrySummary toSummary(Tuple row) {
		Map<TicketGrade, Long> requested = Map.of(
				TicketGrade.BRONZE, row.get("requested_bronze_count", Integer.class).longValue(),
				TicketGrade.SILVER, row.get("requested_silver_count", Integer.class).longValue(),
				TicketGrade.GOLD, row.get("requested_gold_count", Integer.class).longValue(),
				TicketGrade.PLATINUM, row.get("requested_platinum_count", Integer.class).longValue());
		Entry entry = new Entry(row.get("id", UUID.class), row.get("participant_id", UUID.class),
				row.get("event_id", UUID.class), row.get("user_id", UUID.class),
				EntryStatus.valueOf(row.get("status", String.class)), row.get("requested_ticket_count", Integer.class),
				requested, row.get("deducted_ticket_count", Integer.class), row.get("rejection_code", String.class),
				row.get("rejection_reason", String.class), row.get("created_at", Instant.class));
		return new EntrySummary(entry, row.get("title", String.class));
	}
}
