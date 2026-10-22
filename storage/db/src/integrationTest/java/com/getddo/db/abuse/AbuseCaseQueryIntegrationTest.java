package com.getddo.db.abuse;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import com.getddo.core.abuse.domain.AbuseCaseFilter;
import com.getddo.core.abuse.domain.AbuseCaseSummary;
import com.getddo.core.abuse.domain.AbuseSourceType;
import com.getddo.core.abuse.service.AbuseCaseQueryService;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.db.support.MySqlTestConfiguration;
import com.getddo.db.ticket.TicketGrantSeeds;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V030이 적용된 MySQL에서 실제 JPQL·Entity·MapStruct·조회 Service를 연결한다. */
@DataJpaTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
		"spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AbusePersistenceTestConfiguration.class)
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AbuseCaseQueryIntegrationTest {

	private static final Instant FROM = Instant.parse("2026-10-21T06:00:00.123456Z");
	private static final Instant TO = FROM.plusSeconds(60);

	@Autowired
	private AbuseCaseQueryService service;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("만료 이력과 이벤트 없는 행을 보존하고 닉네임·제목 및 UTC 마이크로초를 읽는다")
	void preservesNullableEventExpiredRowsAndJoinedNames() {
		// given
		UUID user = user();
		UUID other = user();
		UUID event = new TicketGrantSeeds(jdbc).event("NO_TICKET", false, null, "excellent",
				FROM.minusSeconds(3600), TO.plusSeconds(3600), "OPEN", null);
		UUID linked = id(1);
		UUID unlinked = id(2);
		insert(linked, user, event, FROM);
		insert(unlinked, other, null, FROM.plusSeconds(1));
		jdbc.update("update users set name = '현재 닉네임' where id = ?", bytes(user));
		jdbc.update("update events set title = '현재 제목' where id = ?", bytes(event));

		// when
		PageResult<AbuseCaseSummary> result = service.findAll(admin(),
				new AbuseCaseFilter(null, null, null, FROM, TO), new PageQuery(1, 20));

		// then
		assertThat(result.getTotalElements()).isEqualTo(2);
		assertThat(result.getItems()).extracting(AbuseCaseSummary::getId).containsExactly(unlinked, linked);
		assertThat(result.getItems().getFirst().getEventId()).isNull();
		assertThat(result.getItems().getFirst().getEventTitle()).isNull();
		AbuseCaseSummary row = result.getItems().get(1);
		assertThat(row.getUserId()).isEqualTo(user);
		assertThat(row.getUserNickname()).isEqualTo("현재 닉네임");
		assertThat(row.getEventId()).isEqualTo(event);
		assertThat(row.getEventTitle()).isEqualTo("현재 제목");
		assertThat(row.getSourceType()).isEqualTo("ENTRY");
		assertThat(row.getDetectionType()).isEqualTo("REPEATED_RATE_LIMIT_EXCEEDED");
		assertThat(row.getBlockedAt()).isEqualTo(FROM);
		assertThat(row.getBlockedUntil()).isEqualTo(FROM.plusSeconds(600));
	}

	@Test
	@DisplayName("시작 경계는 포함하고 끝 경계는 제외하며 동시각은 ID 내림차순으로 페이지를 나눈다")
	void appliesHalfOpenWindowAndStablePagination() {
		// given
		UUID user = user();
		UUID other = user();
		insert(id(1), user, null, FROM.minusSeconds(1));
		insert(id(2), user, null, FROM);
		insert(id(3), other, null, FROM);
		insert(id(4), user, null, TO);
		insert(id(5), user, null, TO.minusSeconds(1));
		AbuseCaseFilter filter = new AbuseCaseFilter(null, null, null, FROM, TO);
		User admin = admin();

		// when
		PageResult<AbuseCaseSummary> all = service.findAll(admin, filter, new PageQuery(1, 20));
		PageResult<AbuseCaseSummary> second = service.findAll(admin, filter, new PageQuery(2, 1));
		PageResult<AbuseCaseSummary> third = service.findAll(admin, filter, new PageQuery(3, 1));
		PageResult<AbuseCaseSummary> beyond = service.findAll(admin, filter, new PageQuery(4, 1));

		// then
		assertThat(all.getTotalElements()).isEqualTo(3);
		assertThat(all.getItems()).extracting(AbuseCaseSummary::getId).containsExactly(id(5), id(3), id(2));
		assertThat(second.getItems()).extracting(AbuseCaseSummary::getId).containsExactly(id(3));
		assertThat(third.getItems()).extracting(AbuseCaseSummary::getId).containsExactly(id(2));
		assertThat(beyond.getItems()).isEmpty();
		assertThat(second.getTotalElements()).isEqualTo(3);
		assertThat(beyond.getTotalElements()).isEqualTo(3);
	}

	@Test
	@DisplayName("사용자·이벤트·기능 필터를 함께 적용하고 다른 기능의 기록을 제외한다")
	void combinesFiltersAndExcludesOtherSources() {
		// given
		UUID user = user();
		UUID other = user();
		UUID event = new TicketGrantSeeds(jdbc).event("NO_TICKET", false, null, "excellent",
				FROM.minusSeconds(3600), TO.plusSeconds(3600), "OPEN", null);
		insert(id(1), user, event, FROM);
		insert(id(2), other, event, FROM);
		insert(id(3), user, null, FROM.plusSeconds(1));
		insert(id(4), user, event, FROM.plusSeconds(2));
		// 신규 ENTRY 이력으로 해석하면 안 되는 별도 기능 행을 재현한다.
		jdbc.update("update abuse_cases set source_type = 'OTHER', detection_type = 'OTHER' where id = ?", bytes(id(4)));

		// when
		PageResult<AbuseCaseSummary> result = service.findAll(admin(),
				new AbuseCaseFilter(user, event, AbuseSourceType.ENTRY, null, null), new PageQuery(1, 20));

		// then
		assertThat(result.getTotalElements()).isEqualTo(1);
		assertThat(result.getItems()).extracting(AbuseCaseSummary::getId).containsExactly(id(1));
	}

	@Test
	@DisplayName("V030은 같은 차단의 중복·역전된 차단 시각·ENTRY의 다른 탐지 값을 DB에서 거절한다")
	void enforcesUniqueAndEntryChecks() {
		// given
		UUID user = user();
		insert(id(1), user, null, FROM);

		// when / then
		assertThatThrownBy(() -> insert(id(2), user, null, FROM))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update abuse_cases set blocked_until = blocked_at where id = ?", bytes(id(1))))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update abuse_cases set detection_type = 'OTHER' where id = ?", bytes(id(1))))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update abuse_cases set source_type = null where id = ?", bytes(id(1))))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update abuse_cases set evidence = null where id = ?", bytes(id(1))))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject("select count(*) from abuse_cases where user_id = ?", Long.class, bytes(user)))
				.isEqualTo(1);
	}

	/** 현재 트랜잭션 안의 테스트 사용자만 생성하므로 종료 후 함께 롤백된다. */
	private UUID user() {
		return new TicketGrantSeeds(jdbc).user();
	}

	/** DB에 저장한 역할과 일치하는 관리자 모델을 전달한다. 헤더 검증은 Controller 단위 테스트 범위다. */
	private User admin() {
		UUID id = user();
		jdbc.update("update users set role = 'ADMIN' where id = ?", bytes(id));
		return new User(id, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null, null, null);
	}

	private void insert(UUID id, UUID user, UUID event, Instant at) {
		jdbc.update("""
				insert into abuse_cases (id, user_id, event_id, source_type, detection_type, detection_reason,
				  evidence, occurred_at, detected_at, blocked_at, blocked_until)
				values (?, ?, ?, 'ENTRY', 'REPEATED_RATE_LIMIT_EXCEEDED',
				  '응모 요청 한도 초과가 반복되어 일시 차단되었습니다.',
				  JSON_OBJECT('ruleVersion', 'v1', 'triggerScope', 'USER',
				    'windowStartedAt', ?, 'windowEndsAt', ?, 'requestLimit', 20,
				    'rejectedCount', 20, 'rejectionThreshold', 20), ?, ?, ?, ?)
				""", bytes(id), bytes(user), event == null ? null : bytes(event),
				at.minusSeconds(30).toString(), at.plusSeconds(30).toString(),
				utc(at), utc(at), utc(at), utc(at.plusSeconds(600)));
	}

	private static UUID id(int suffix) {
		return UUID.fromString("0199564e-45e0-7000-8000-%012d".formatted(suffix));
	}

	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
