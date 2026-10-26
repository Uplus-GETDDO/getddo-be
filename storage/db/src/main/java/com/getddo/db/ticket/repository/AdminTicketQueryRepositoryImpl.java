package com.getddo.db.ticket.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.ticket.domain.AdminTicketHistoryFilter;
import com.getddo.core.ticket.domain.AdminTicketHistoryView;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.domain.TicketGrantFilter;
import com.getddo.core.ticket.domain.TicketGrantView;
import com.getddo.core.ticket.domain.TicketInconsistencyView;
import com.getddo.core.ticket.domain.TicketHistoryView;
import com.getddo.core.ticket.domain.TicketOperationType;
import com.getddo.core.ticket.domain.TicketRefundFilter;
import com.getddo.core.ticket.domain.TicketRefundView;
import com.getddo.core.ticket.domain.TicketStatus;
import com.getddo.core.ticket.repository.AdminTicketQueryRepository;
import com.getddo.db.common.util.UuidBinary;

/**
 * 관리자 화면용 응모권 조회 구현.
 *
 * <p>사용자 이름, 이벤트·미션 제목과 지급 근거(출석·미션·게임 청구)는 다른 도메인이 소유한 테이블이라 Entity를 만들지 않고
 * 네이티브 SQL의 JOIN으로 읽는다. SQL 문장은 고정 조각만 이어 붙이고 값은 모두 파라미터로 바인딩한다.</p>
 */
@Repository
@RequiredArgsConstructor
public class AdminTicketQueryRepositoryImpl implements AdminTicketQueryRepository {

	private static final String GRANT_SELECT = """
			select h.id as history_id, h.ticket_id, t.user_id, u.name as user_name, t.grade,
			       case when t.attendance_reward_claim_id is not null then 'ATTENDANCE'
			            when t.mission_reward_claim_id is not null then 'MISSION'
			            else 'GAME' end as source_type,
			       coalesce(ac.attendance_id, mc.mission_submission_id, gc.game_play_id) as source_id,
			       mc.mission_id, h.created_at as granted_at, h.expires_at
			""";
	private static final String GRANT_FROM = """
			 from ticket_histories h
			 join tickets t on t.id = h.ticket_id
			 join users u on u.id = t.user_id
			 left join attendance_reward_claims ac on ac.id = t.attendance_reward_claim_id
			 left join mission_reward_claims mc on mc.id = t.mission_reward_claim_id
			 left join game_reward_claims gc on gc.id = t.game_reward_claim_id
			 where h.operation_type = 'GRANT'
			""";
	private static final String GRANT_ORDER = " order by h.created_at desc, h.id desc";

	/** 반환 이력의 이벤트는 되돌린 원본 사용 이력의 응모에서 얻는다. 처리 이력에서는 사용 이력 자신의 응모와 둘 중 하나다. */
	private static final String HISTORY_SELECT = """
			select h.id, h.ticket_id, h.operation_type, t.grade, h.status, h.expires_at, h.reason, h.created_at,
			       coalesce(ee.event_id, oee.event_id) as event_id, h.event_entry_id, mc.mission_id, gc.game_id,
			       a.attendance_date, h.original_use_history_id, h.corrected_history_id,
			       t.user_id, u.name as user_name, ev.title as event_title, ms.title as mission_title
			""";
	private static final String HISTORY_FROM = """
			 from ticket_histories h
			 join tickets t on t.id = h.ticket_id
			 join users u on u.id = t.user_id
			 left join mission_reward_claims mc on mc.id = t.mission_reward_claim_id
			 left join missions ms on ms.id = mc.mission_id
			 left join game_reward_claims gc on gc.id = t.game_reward_claim_id
			 left join attendance_reward_claims ac on ac.id = t.attendance_reward_claim_id
			 left join attendances a on a.id = ac.attendance_id
			 left join event_entries ee on ee.id = h.event_entry_id
			 left join ticket_histories oh on oh.id = h.original_use_history_id
			 left join event_entries oee on oee.id = oh.event_entry_id
			 left join events ev on ev.id = coalesce(ee.event_id, oee.event_id)
			 where 1 = 1
			""";
	private static final String HISTORY_ORDER = " order by h.created_at desc, h.id desc";

	private static final String REFUND_SELECT = """
			select h.id, h.original_use_history_id as spend_history_id, h.ticket_id, t.grade,
			       oee.event_id, ev.title as event_title, t.user_id, u.name as user_name,
			       h.created_at as refunded_at, h.expires_at as target_expires_at
			""";
	private static final String REFUND_FROM = """
			 from ticket_histories h
			 join tickets t on t.id = h.ticket_id
			 join users u on u.id = t.user_id
			 join ticket_histories oh on oh.id = h.original_use_history_id
			 join event_entries oee on oee.id = oh.event_entry_id
			 join events ev on ev.id = oee.event_id
			 where h.operation_type = 'REFUND'
			""";
	private static final String REFUND_ORDER = " order by h.created_at desc, h.id desc";

	/**
	 * 현재 버전·상태가 마지막 이력의 버전·상태와 다르거나 이력이 하나도 없는 응모권.
	 *
	 * <p>모든 응모권을 훑는 판정은 (응모권, 버전) 유일 키로 현재 버전의 이력을 찾아 상태를 비교하고, 더 큰 버전의 이력이 있는지만
	 * 확인한다. 현재 버전의 이력이 없으면 이력이 없거나 마지막 이력이 현재 버전보다 낮은 것이다. 사용자 이름과 마지막 이력은 어긋난
	 * 행에만 붙도록 SELECT 목록의 하위 조회로 읽는다.</p>
	 */
	private static final String INCONSISTENCY_SELECT = """
			select t.id as ticket_id, t.user_id,
			       (select u.name from users u where u.id = t.user_id) as user_name, t.grade,
			       t.version as ticket_version,
			       coalesce((select max(x.ticket_version) from ticket_histories x where x.ticket_id = t.id), 0)
			         as latest_version,
			       t.status as ticket_status,
			       (select x.status from ticket_histories x where x.ticket_id = t.id
			         order by x.ticket_version desc limit 1) as latest_status
			""";
	private static final String INCONSISTENCY_FROM = """
			 from tickets t
			 left join ticket_histories cur on cur.ticket_id = t.id and cur.ticket_version = t.version
			 where (cur.id is null or cur.status <> t.status
			   or exists (select 1 from ticket_histories n where n.ticket_id = t.id and n.ticket_version > t.version))
			""";
	private static final String INCONSISTENCY_ORDER = " order by t.updated_at desc, t.id desc";
	/** 한 사용자의 어긋난 응모권을 쪽 나누기 없이 읽을 때의 상한 크기. */
	private static final int ALL_OF_USER = Integer.MAX_VALUE;

	private final EntityManager entityManager;

	@Override
	public PageResult<TicketGrantView> findGrants(TicketGrantFilter filter, PageQuery page) {
		Map<String, Object> parameters = new HashMap<>();
		StringBuilder where = new StringBuilder();
		where(where, parameters, "t.user_id", "userId", filter.getUserId());
		if (filter.getKeyword() != null) {
			where.append(" and lower(u.name) like :keyword escape '!'");
			parameters.put("keyword", "%" + escapeLike(filter.getKeyword().toLowerCase(Locale.ROOT)) + "%");
		}
		where(where, parameters, "mc.mission_id", "missionId", filter.getMissionId());
		if (filter.getSourceType() != null) {
			where.append(sourceCondition(filter.getSourceType()));
		}
		if (filter.getGrade() != null) {
			where.append(" and t.grade = :grade");
			parameters.put("grade", filter.getGrade().name());
		}
		period(where, parameters, "h.created_at", filter.getFrom(), filter.getTo());
		return page(GRANT_SELECT, GRANT_FROM + where, grantCountFrom(filter) + where, parameters, GRANT_ORDER, page,
				query -> query.addScalar("history_id", UUID.class).addScalar("ticket_id", UUID.class)
						.addScalar("user_id", UUID.class).addScalar("user_name", String.class)
						.addScalar("grade", String.class).addScalar("source_type", String.class)
						.addScalar("source_id", UUID.class).addScalar("mission_id", UUID.class)
						.addScalar("granted_at", Instant.class).addScalar("expires_at", Instant.class),
				AdminTicketQueryRepositoryImpl::toGrant);
	}

	@Override
	public PageResult<AdminTicketHistoryView> findHistories(AdminTicketHistoryFilter filter, PageQuery page) {
		Map<String, Object> parameters = new HashMap<>();
		StringBuilder where = new StringBuilder();
		where(where, parameters, "t.user_id", "userId", filter.getUserId());
		where(where, parameters, "coalesce(ee.event_id, oee.event_id)", "eventId", filter.getEventId());
		where(where, parameters, "mc.mission_id", "missionId", filter.getMissionId());
		if (filter.getOperationType() != null) {
			where.append(" and h.operation_type = :operationType");
			parameters.put("operationType", filter.getOperationType().name());
		}
		period(where, parameters, "h.created_at", filter.getFrom(), filter.getTo());
		return page(HISTORY_SELECT, HISTORY_FROM + where, historyCountFrom(filter) + where, parameters, HISTORY_ORDER,
				page,
				query -> query.addScalar("id", UUID.class).addScalar("ticket_id", UUID.class)
						.addScalar("operation_type", String.class).addScalar("grade", String.class)
						.addScalar("status", String.class).addScalar("expires_at", Instant.class)
						.addScalar("reason", String.class).addScalar("created_at", Instant.class)
						.addScalar("event_id", UUID.class).addScalar("event_entry_id", UUID.class)
						.addScalar("mission_id", UUID.class).addScalar("game_id", UUID.class)
						.addScalar("attendance_date", LocalDate.class)
						.addScalar("original_use_history_id", UUID.class)
						.addScalar("corrected_history_id", UUID.class).addScalar("user_id", UUID.class)
						.addScalar("user_name", String.class).addScalar("event_title", String.class)
						.addScalar("mission_title", String.class),
				AdminTicketQueryRepositoryImpl::toHistory);
	}

	@Override
	public PageResult<TicketRefundView> findRefunds(TicketRefundFilter filter, PageQuery page) {
		Map<String, Object> parameters = new HashMap<>();
		StringBuilder where = new StringBuilder();
		where(where, parameters, "oee.event_id", "eventId", filter.getEventId());
		where(where, parameters, "t.user_id", "userId", filter.getUserId());
		period(where, parameters, "h.created_at", filter.getFrom(), filter.getTo());
		return page(REFUND_SELECT, REFUND_FROM + where, parameters, REFUND_ORDER, page,
				query -> query.addScalar("id", UUID.class).addScalar("spend_history_id", UUID.class)
						.addScalar("ticket_id", UUID.class).addScalar("grade", String.class)
						.addScalar("event_id", UUID.class).addScalar("event_title", String.class)
						.addScalar("user_id", UUID.class).addScalar("user_name", String.class)
						.addScalar("refunded_at", Instant.class).addScalar("target_expires_at", Instant.class),
				AdminTicketQueryRepositoryImpl::toRefund);
	}

	@Override
	public boolean userExists(UUID userId) {
		Query query = entityManager.createNativeQuery("select count(*) from users where id = :id");
		query.setParameter("id", UuidBinary.toBytes(userId));
		return ((Number) query.getSingleResult()).longValue() > 0;
	}

	@Override
	public List<TicketInconsistencyView> findInconsistenciesOf(UUID userId) {
		return findInconsistencies(userId, new PageQuery(1, ALL_OF_USER)).getItems();
	}

	@Override
	public PageResult<TicketInconsistencyView> findInconsistencies(UUID userId, PageQuery page) {
		Map<String, Object> parameters = new HashMap<>();
		StringBuilder where = new StringBuilder();
		where(where, parameters, "t.user_id", "userId", userId);
		return page(INCONSISTENCY_SELECT, INCONSISTENCY_FROM + where, parameters, INCONSISTENCY_ORDER, page,
				query -> query.addScalar("ticket_id", UUID.class).addScalar("user_id", UUID.class)
						.addScalar("user_name", String.class).addScalar("grade", String.class)
						.addScalar("ticket_version", Long.class).addScalar("latest_version", Long.class)
						.addScalar("ticket_status", String.class).addScalar("latest_status", String.class),
				AdminTicketQueryRepositoryImpl::toInconsistency);
	}

	/**
	 * 지급 건수를 세는 FROM 절. 조건에 쓰이는 테이블만 잇는다. 응모권은 이력의 필수 외래 키, 사용자는 응모권의 필수 외래 키라
	 * 조건이 없으면 조인해도 건수가 같으므로 생략한다.
	 */
	private static String grantCountFrom(TicketGrantFilter filter) {
		boolean mission = filter.getMissionId() != null;
		boolean user = filter.getKeyword() != null;
		boolean ticket = user || mission || filter.getUserId() != null || filter.getSourceType() != null
				|| filter.getGrade() != null;
		StringBuilder from = new StringBuilder(" from ticket_histories h");
		if (ticket) {
			from.append(" join tickets t on t.id = h.ticket_id");
		}
		if (user) {
			from.append(" join users u on u.id = t.user_id");
		}
		if (mission) {
			from.append(" left join mission_reward_claims mc on mc.id = t.mission_reward_claim_id");
		}
		return from.append(" where h.operation_type = 'GRANT'").toString();
	}

	/** 처리 이력 건수를 세는 FROM 절. 이벤트·미션·사용자 조건에 필요한 조인만 잇는다. */
	private static String historyCountFrom(AdminTicketHistoryFilter filter) {
		boolean mission = filter.getMissionId() != null;
		boolean event = filter.getEventId() != null;
		boolean ticket = mission || filter.getUserId() != null;
		StringBuilder from = new StringBuilder(" from ticket_histories h");
		if (ticket) {
			from.append(" join tickets t on t.id = h.ticket_id");
		}
		if (mission) {
			from.append(" left join mission_reward_claims mc on mc.id = t.mission_reward_claim_id");
		}
		if (event) {
			from.append(" left join event_entries ee on ee.id = h.event_entry_id")
					.append(" left join ticket_histories oh on oh.id = h.original_use_history_id")
					.append(" left join event_entries oee on oee.id = oh.event_entry_id");
		}
		return from.append(" where 1 = 1").toString();
	}

	private <T> PageResult<T> page(String select, String fromAndWhere, Map<String, Object> parameters, String order,
			PageQuery page, Consumer<NativeQuery<Tuple>> scalars, Function<Tuple, T> mapper) {
		return page(select, fromAndWhere, fromAndWhere, parameters, order, page, scalars, mapper);
	}

	/**
	 * 건수를 먼저 세고, 범위 안이면 한 페이지를 읽는다. 건수는 {@code countFromAndWhere}로 센다. 결과 컬럼은 Entity와 같은
	 * Hibernate 타입으로 읽는다.
	 */
	private <T> PageResult<T> page(String select, String fromAndWhere, String countFromAndWhere,
			Map<String, Object> parameters, String order, PageQuery page, Consumer<NativeQuery<Tuple>> scalars,
			Function<Tuple, T> mapper) {
		long offset = page.offset();
		// JPA가 int로 바꿀 수 없는 시작 위치는 항목이 없는 것으로 보고 건수만 센다.
		List<T> items = offset > Integer.MAX_VALUE ? List.of()
				: readPage(select + fromAndWhere + order, parameters, page, scalars, mapper);
		// 한 쪽을 다 채우지 못했거나 첫 쪽이 비었으면 전체 건수를 이미 알아서 따로 세지 않는다. 전체를 훑는 조회의 비용을 한 번으로 줄인다.
		boolean totalKnown = items.isEmpty() ? offset == 0 : items.size() < page.getSize();
		long total = totalKnown ? offset + items.size() : count(countFromAndWhere, parameters);
		return new PageResult<>(items, page.getPage(), page.getSize(), total);
	}

	private long count(String countFromAndWhere, Map<String, Object> parameters) {
		Query count = entityManager.createNativeQuery("select count(*)" + countFromAndWhere);
		parameters.forEach(count::setParameter);
		return ((Number) count.getSingleResult()).longValue();
	}

	private <T> List<T> readPage(String sql, Map<String, Object> parameters, PageQuery page,
			Consumer<NativeQuery<Tuple>> scalars, Function<Tuple, T> mapper) {
		@SuppressWarnings("unchecked")
		NativeQuery<Tuple> query = entityManager.createNativeQuery(sql, Tuple.class).unwrap(NativeQuery.class);
		scalars.accept(query);
		parameters.forEach(query::setParameter);
		query.setFirstResult(Math.toIntExact(page.offset()));
		query.setMaxResults(page.getSize());
		return query.getResultList().stream().map(mapper).toList();
	}

	private static void where(StringBuilder where, Map<String, Object> parameters, String column, String name,
			UUID value) {
		if (value != null) {
			where.append(" and ").append(column).append(" = :").append(name);
			parameters.put(name, UuidBinary.toBytes(value));
		}
	}

	/** 기간 조건을 {@code [from, to)}로 이어 붙인다. */
	private static void period(StringBuilder where, Map<String, Object> parameters, String column, Instant from,
			Instant to) {
		if (from != null) {
			where.append(" and ").append(column).append(" >= :from");
			parameters.put("from", from);
		}
		if (to != null) {
			where.append(" and ").append(column).append(" < :to");
			parameters.put("to", to);
		}
	}

	private static String sourceCondition(GrantSourceType type) {
		return switch (type) {
			case ATTENDANCE -> " and t.attendance_reward_claim_id is not null";
			case MISSION -> " and t.mission_reward_claim_id is not null";
			case GAME -> " and t.game_reward_claim_id is not null";
		};
	}

	/** LIKE의 와일드카드와 이스케이프 문자를 문자 그대로 검색하도록 이스케이프한다. */
	private static String escapeLike(String keyword) {
		StringBuilder escaped = new StringBuilder();
		for (char c : keyword.toCharArray()) {
			if (c == '%' || c == '_' || c == '!') {
				escaped.append('!');
			}
			escaped.append(c);
		}
		return escaped.toString();
	}

	private static TicketGrantView toGrant(Tuple row) {
		return new TicketGrantView(
				row.get("history_id", UUID.class),
				row.get("ticket_id", UUID.class),
				row.get("user_id", UUID.class),
				row.get("user_name", String.class),
				TicketGrade.valueOf(row.get("grade", String.class)),
				GrantSourceType.valueOf(row.get("source_type", String.class)),
				row.get("source_id", UUID.class),
				row.get("mission_id", UUID.class),
				row.get("granted_at", Instant.class),
				row.get("expires_at", Instant.class));
	}

	private static AdminTicketHistoryView toHistory(Tuple row) {
		TicketHistoryView history = new TicketHistoryView(
				row.get("id", UUID.class),
				row.get("ticket_id", UUID.class),
				TicketOperationType.valueOf(row.get("operation_type", String.class)),
				TicketGrade.valueOf(row.get("grade", String.class)),
				TicketStatus.valueOf(row.get("status", String.class)),
				row.get("expires_at", Instant.class),
				row.get("reason", String.class),
				row.get("created_at", Instant.class),
				row.get("event_id", UUID.class),
				row.get("event_entry_id", UUID.class),
				row.get("mission_id", UUID.class),
				row.get("game_id", UUID.class),
				row.get("attendance_date", LocalDate.class),
				row.get("original_use_history_id", UUID.class),
				row.get("corrected_history_id", UUID.class));
		return new AdminTicketHistoryView(history, row.get("user_id", UUID.class), row.get("user_name", String.class),
				row.get("event_title", String.class), row.get("mission_title", String.class));
	}

	private static TicketRefundView toRefund(Tuple row) {
		return new TicketRefundView(
				row.get("id", UUID.class),
				row.get("spend_history_id", UUID.class),
				row.get("ticket_id", UUID.class),
				TicketGrade.valueOf(row.get("grade", String.class)),
				row.get("event_id", UUID.class),
				row.get("event_title", String.class),
				row.get("user_id", UUID.class),
				row.get("user_name", String.class),
				row.get("refunded_at", Instant.class),
				row.get("target_expires_at", Instant.class));
	}

	private static TicketInconsistencyView toInconsistency(Tuple row) {
		String latestStatus = row.get("latest_status", String.class);
		return new TicketInconsistencyView(
				row.get("ticket_id", UUID.class),
				row.get("user_id", UUID.class),
				row.get("user_name", String.class),
				TicketGrade.valueOf(row.get("grade", String.class)),
				row.get("ticket_version", Long.class),
				row.get("latest_version", Long.class),
				TicketStatus.valueOf(row.get("ticket_status", String.class)),
				latestStatus == null ? null : TicketStatus.valueOf(latestStatus));
	}
}
