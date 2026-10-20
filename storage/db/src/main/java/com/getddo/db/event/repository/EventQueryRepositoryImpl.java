package com.getddo.db.event.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.event.domain.EventQueryFilter;
import com.getddo.core.event.domain.EventSort;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.repository.EventQueryRepository;
import com.getddo.db.common.util.UuidBinary;

/**
 * 이벤트 조회 전용 JDBC 구현. BINARY(16)은 공통 UUID 변환을 사용하고 DATETIME(6)은 UTC로 해석한다.
 * 관리자 목록의 경품 상세는 페이지 단위로 일괄 조회하고, 사용자 목록에서는 인원 합계만 읽는다.
 * 추첨 영역의 발표 기록은 공개 상태 판정을 위해 읽기만 한다.
 */
@Repository
public class EventQueryRepositoryImpl implements EventQueryRepository {
	// 재추첨 중에도 기존 발표가 있으면 사용자 공개 상태는 PUBLISHED를 유지한다.
	private static final String PUBLIC_STATUS = """
			case when e.status = 'REDRAWING'
				then case when exists (select 1 from draw_publications p where p.event_id = e.id)
					then 'PUBLISHED' else 'DRAW_CONFIRMED' end
				else e.status end
			""";
	private static final String SELECT = """
			select e.id, e.title, e.description, e.image_key, e.event_type,
			       e.weighting_enabled, e.max_tickets_per_user, e.membership_rule,
			       e.starts_at, e.ends_at, e.status, e.created_at, e.updated_at,
			       e.canceled_at,
			""" + PUBLIC_STATUS + " as public_status, ";
	private static final String WINNER_COUNT = """
			(select coalesce(sum(p.winner_count), 0) from event_prizes p where p.event_id = e.id)
			""";
	private final NamedParameterJdbcTemplate jdbc;

	public EventQueryRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 동일 조건으로 건수·페이지를 조회하며 관리자 목록에만 경품을 일괄 연결한다. */
	@Override
	public PageResult<EventView> findAll(EventQueryFilter filter, PageQuery page, boolean publicView) {
		Map<String, Object> parameters = new HashMap<>();
		String where = where(filter, publicView, parameters);
		long total = jdbc.queryForObject("select count(*) from events e" + where, parameters, Long.class);
		parameters.put("limit", page.getSize());
		parameters.put("offset", page.offset());
		List<EventView> rows = jdbc.query(select(publicView) + where
				+ " order by " + orderBy(filter.getSort()) + " limit :limit offset :offset",
				parameters, (row, rowNumber) -> toView(row));
		return new PageResult<>(publicView ? rows : attachPrizes(rows),
				page.getPage(), page.getSize(), total);
	}

	/** 삭제되지 않은 이벤트와 경품을 읽는다. 실제 상태·공개 상태의 선택은 응답 변환에서 수행한다. */
	@Override
	public Optional<EventView> findById(UUID eventId) {
		List<EventView> rows = jdbc.query(select(false) + " where e.deleted_at is null and e.id = :id",
				Map.of("id", UuidBinary.toBytes(eventId)), (row, rowNumber) -> toView(row));
		return attachPrizes(rows).stream().findFirst();
	}

	/** 정상 접수된 참여자를 페이지 단위로 읽는다. 거절 이력이나 차감 장수로 판정하지 않는다. */
	@Override
	public Set<UUID> findEnteredEventIds(UUID userId, List<UUID> eventIds) {
		if (eventIds.isEmpty()) {
			return Set.of();
		}
		List<UUID> entered = jdbc.query("""
				select p.event_id from event_participants p
				where p.user_id = :userId and p.event_id in (:eventIds)
				""", Map.of("userId", UuidBinary.toBytes(userId), "eventIds",
						eventIds.stream().map(UuidBinary::toBytes).toList()),
				(row, rowNumber) -> UuidBinary.fromBytes(row.getBytes("event_id")));
		return new HashSet<>(entered);
	}

	/** 허용된 컬럼·방향만 사용하고, 같은 값이면 ID로 정렬해 페이지 순서를 안정적으로 유지한다. */
	private static String orderBy(EventSort sort) {
		return switch (sort) {
			case CREATED_AT_DESC -> "e.created_at desc, e.id desc";
			case CREATED_AT_ASC -> "e.created_at asc, e.id asc";
			case STARTS_AT_DESC -> "e.starts_at desc, e.id desc";
			case STARTS_AT_ASC -> "e.starts_at asc, e.id asc";
			case ENDS_AT_DESC -> "e.ends_at desc, e.id desc";
			case ENDS_AT_ASC -> "e.ends_at asc, e.id asc";
			case TITLE_DESC -> "e.title desc, e.id desc";
			case TITLE_ASC -> "e.title asc, e.id asc";
		};
	}

	/** 합계를 같은 SELECT에서 읽어 상세 조회나 이벤트별 추가 쿼리를 실행하지 않는다. */
	private static String select(boolean includeWinnerCount) {
		return SELECT + (includeWinnerCount ? WINNER_COUNT : "0") + " as winner_count from events e";
	}

	private static String where(EventQueryFilter filter, boolean publicView, Map<String, Object> parameters) {
		StringBuilder where = new StringBuilder(" where e.deleted_at is null");
		if (filter.getStatus() != null) {
			where.append(" and (").append(statusCondition(filter.getStatus(), publicView)).append(")");
			parameters.put("status", filter.getStatus().name());
		}
		if (filter.getEventType() != null) {
			where.append(" and e.event_type = :eventType");
			parameters.put("eventType", filter.getEventType().name());
		}
		if (filter.getWeightingEnabled() != null) {
			where.append(" and e.weighting_enabled = :weightingEnabled");
			parameters.put("weightingEnabled", filter.getWeightingEnabled());
		}
		if (filter.getMembershipRule() != null) {
			where.append(" and e.membership_rule = :membershipRule");
			parameters.put("membershipRule", filter.getMembershipRule().name());
		}
		if (filter.getKeyword() != null) {
			where.append(" and e.title like :keyword escape '!'");
			parameters.put("keyword", "%" + filter.getKeyword().replace("!", "!!")
					.replace("%", "!%").replace("_", "!_") + "%");
		}
		// 모집 구간 [starts_at, ends_at)과 검색 구간 [from, to)가 한 순간이라도 겹치는 경우.
		if (filter.getFrom() != null) {
			where.append(" and e.ends_at > :from");
			parameters.put("from", LocalDateTime.ofInstant(filter.getFrom(), ZoneOffset.UTC));
		}
		if (filter.getTo() != null) {
			where.append(" and e.starts_at < :to");
			parameters.put("to", LocalDateTime.ofInstant(filter.getTo(), ZoneOffset.UTC));
		}
		if (filter.getCreatedFrom() != null) {
			where.append(" and e.created_at >= :createdFrom");
			parameters.put("createdFrom", LocalDateTime.ofInstant(filter.getCreatedFrom(), ZoneOffset.UTC));
		}
		if (filter.getCreatedTo() != null) {
			where.append(" and e.created_at < :createdTo");
			parameters.put("createdTo", LocalDateTime.ofInstant(filter.getCreatedTo(), ZoneOffset.UTC));
		}
		return where.toString();
	}

	/** 상태 컬럼을 직접 비교하고 재추첨의 공개 상태만 발표 기록으로 판정한다. */
	private static String statusCondition(EventStatus status, boolean publicView) {
		if (!publicView) {
			return "e.status = :status";
		}
		return switch (status) {
			case PUBLISHED -> """
					e.status = :status or (e.status = 'REDRAWING' and exists (
						select 1 from draw_publications p where p.event_id = e.id))
					""";
			case DRAW_CONFIRMED -> """
					e.status = :status or (e.status = 'REDRAWING' and not exists (
						select 1 from draw_publications p where p.event_id = e.id))
					""";
			default -> "e.status = :status and e.status <> 'REDRAWING'";
		};
	}

	private List<EventView> attachPrizes(List<EventView> events) {
		Map<UUID, List<RegisteredEvent.Prize>> prizes = findPrizes(events);
		return events.stream().map(event -> withPrizes(event,
				prizes.getOrDefault(event.getDetails().getId(), List.of()))).toList();
	}

	/** 이벤트별 개별 쿼리를 피하기 위해 조회된 이벤트들의 경품을 한 번의 IN 쿼리로 가져온다. */
	private Map<UUID, List<RegisteredEvent.Prize>> findPrizes(List<EventView> events) {
		if (events.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<RegisteredEvent.Prize>> prizes = new HashMap<>();
		jdbc.query("""
				select p.id, p.event_id, p.prize_rank, p.name, p.description, p.image_key, p.winner_count
				from event_prizes p where p.event_id in (:eventIds) order by p.event_id, p.prize_rank
				""", Map.of("eventIds", events.stream()
						.map(event -> UuidBinary.toBytes(event.getDetails().getId())).toList()), row -> {
			prizes.computeIfAbsent(UuidBinary.fromBytes(row.getBytes("event_id")), ignored -> new ArrayList<>())
					.add(new RegisteredEvent.Prize(UuidBinary.fromBytes(row.getBytes("id")), row.getInt("prize_rank"),
							row.getString("name"), row.getString("description"),
							row.getString("image_key"), row.getInt("winner_count")));
		});
		return prizes;
	}

	private static EventView toView(ResultSet row) throws SQLException {
		RegisteredEvent details = new RegisteredEvent(UuidBinary.fromBytes(row.getBytes("id")),
				row.getString("title"),
				row.getString("description"), row.getString("image_key"),
				EventType.valueOf(row.getString("event_type")), row.getBoolean("weighting_enabled"),
				row.getObject("max_tickets_per_user", Integer.class), MembershipRule.valueOf(row.getString("membership_rule")),
				instant(row, "starts_at"), instant(row, "ends_at"), EventStatus.valueOf(row.getString("status")),
				instant(row, "created_at"), instant(row, "updated_at"), List.of());
		return new EventView(details, EventStatus.valueOf(row.getString("public_status")),
				instant(row, "canceled_at"), row.getLong("winner_count"));
	}

	private static EventView withPrizes(EventView view, List<RegisteredEvent.Prize> prizes) {
		RegisteredEvent event = view.getDetails();
		RegisteredEvent details = new RegisteredEvent(event.getId(), event.getTitle(),
				event.getDescription(), event.getImageKey(), event.getEventType(), event.isWeightingEnabled(),
				event.getMaxTicketsPerUser(), event.getMembershipRule(), event.getStartsAt(), event.getEndsAt(),
				event.getStatus(), event.getCreatedAt(), event.getUpdatedAt(), prizes);
		return new EventView(details, view.getPublicStatus(), view.getCanceledAt());
	}

	/** DATETIME에는 시간대가 없으므로 JDBC 기본 시간대에 의존하지 않고 UTC로 해석한다. */
	private static Instant instant(ResultSet row, String column) throws SQLException {
		LocalDateTime value = row.getObject(column, LocalDateTime.class);
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}
}
