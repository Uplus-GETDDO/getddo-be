package com.getddo.api.banner;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.core.audit.repository.AuditLogRepository;
import com.getddo.core.banner.domain.Banner;
import com.getddo.core.banner.domain.BannerSettings;
import com.getddo.core.banner.exception.BannerErrorCode;
import com.getddo.core.banner.service.BannerService;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.db.common.util.UuidBinary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MySQL·MapStruct·사용자 문맥·감사 기록·제약을 연결한다. 실행 결과가 아닌 검증 코드다. */
@ApiIntegrationTest
@TestPropertySource(properties = "getddo.images.base-url=https://cdn.example.test/images/")
class BannerApiIntegrationTest {
	private static final String ADMIN_PATH = "/api/v1/admin/banners";
	private static final Instant AT = Instant.parse("2026-10-16T00:00:00Z");
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private BannerService banners;
	@Autowired private TimeProvider time;
	@MockitoSpyBean private AuditLogRepository audit;
	private UUID admin;
	private UUID user;
	private UUID inactive;
	private UUID event;
	private final List<UUID> extraEvents = new ArrayList<>();

	@BeforeEach
	void seed() {
		admin = UUID.randomUUID(); user = UUID.randomUUID(); inactive = UUID.randomUUID(); event = UUID.randomUUID();
		insertUser(admin, "ADMIN", "ACTIVE");
		insertUser(user, "USER", "ACTIVE");
		insertUser(inactive, "ADMIN", "INACTIVE");
		jdbc.update("""
				insert into events (id,title,description,event_type,weighting_enabled,starts_at,ends_at,status,
				membership_rule,created_at,updated_at)
				values (?,'배너 테스트','설명','NO_TICKET',false,'2026-10-01','2026-12-01','OPEN','vip',?,?)
				""", bytes(event), LocalDateTime.ofInstant(AT, ZoneOffset.UTC), LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
	}

	@AfterEach
	void clean() {
		reset(audit);
		// 이 테스트가 만든 사용자·이벤트·배너만 제거한다. 다른 API 테스트의 공유 컨텍스트 자료를 지우지 않는다.
		jdbc.update("delete from audit_logs where actor_id = ?", bytes(admin));
		jdbc.update("delete from banners where event_id = ? or image_key like 'gd209/%'", bytes(event));
		for (UUID id : extraEvents) jdbc.update("delete from events where id = ?", bytes(id));
		jdbc.update("delete from events where id = ?", bytes(event));
		for (UUID id : List.of(admin, user, inactive)) jdbc.update("delete from users where id = ?", bytes(id));
	}

	@Test
	@DisplayName("등록 201·사용자 4필드·관리자 7필드·공개 이미지 주소·UUIDv7을 검증한다")
	void createsAndReturnsExactShapes() throws Exception {
		// given / when
		mvc.perform(as(admin, "ADMIN", post(ADMIN_PATH)).contentType(MediaType.APPLICATION_JSON).content(write(1)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data", aMapWithSize(7)))
				.andExpect(jsonPath("$.data.eventId").value(event.toString()))
				.andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.test/images/gd209/banner.png"))
				.andExpect(jsonPath("$.data.displayOrder").value(1));
		// then
		mvc.perform(as(user, "USER", get("/api/v1/banners"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0]", aMapWithSize(4)))
				.andExpect(jsonPath("$.data[0].imageKey").doesNotExist());
		assertThat(banners.listForAdmin(actor()).getFirst().getId().version()).isEqualTo(7);
		assertThat(countAudits()).isEqualTo(1);
	}

	@Test
	@DisplayName("다섯 자리 모두 찬 상태에서 역순 교환·이동·삭제 후 당김이 제약을 지킨다")
	void reordersFullCatalogAndClosesGap() throws Exception {
		// given
		List<Banner> created = new ArrayList<>();
		for (int i = 1; i <= 5; i++) created.add(banners.create(actor(), settings(i)));
		List<UUID> reverse = new ArrayList<>(created.stream().map(Banner::getId).toList());
		Collections.reverse(reverse);
		// when
		mvc.perform(as(admin, "ADMIN", put(ADMIN_PATH + "/order")).contentType(MediaType.APPLICATION_JSON)
				.content(order(reverse))).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(5)));
		// then
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactlyElementsOf(reverse);
		for (int i = 0; i < 5; i++) {
			Banner actual = banners.listForAdmin(actor()).get(i);
			assertThat(actual.getDisplayOrder()).isEqualTo(i + 1);
			assertThat(actual.getCreatedAt()).isEqualTo(created.get(4 - i).getCreatedAt());
		}
		UUID moved = reverse.getFirst();
		mvc.perform(as(admin, "ADMIN", put(ADMIN_PATH + "/" + moved)).contentType(MediaType.APPLICATION_JSON)
				.content(write(5))).andExpect(status().isOk()).andExpect(jsonPath("$.data.displayOrder").value(5));
		mvc.perform(as(admin, "ADMIN", delete(ADMIN_PATH + "/" + reverse.get(1))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data", nullValue()));
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getDisplayOrder).containsExactly(1,2,3,4);
	}

	@Test
	@DisplayName("삭제·취소된 연결 이벤트와 미연결 이전 배너는 사용자에게 숨기고 관리자에게 남긴다")
	void hidesIneligibleEventWithoutDeletingBanner() throws Exception {
		// given
		Banner linked = banners.create(actor(), settings(1));
		UUID legacy = UUID.randomUUID();
		jdbc.update("""
				insert into banners(id,image_key,display_order,created_at,updated_at,event_url)
				values (?,'gd209/legacy.png',2,?,?,'/old-event-url')
				""", bytes(legacy), LocalDateTime.ofInstant(AT, ZoneOffset.UTC), LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
		// when / then
		for (String change : List.of("status='CANCELED'", "status='OPEN', deleted_at='2026-10-16'")) {
			jdbc.update("update events set " + change + " where id = ?", bytes(event));
			mvc.perform(as(user, "USER", get("/api/v1/banners"))).andExpect(status().isOk())
					.andExpect(jsonPath("$.data", hasSize(0)));
			mvc.perform(as(admin, "ADMIN", get(ADMIN_PATH))).andExpect(status().isOk())
					.andExpect(jsonPath("$.data", hasSize(2)))
					.andExpect(jsonPath("$.data[1].eventId", nullValue()));
		}
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactly(linked.getId(), legacy);
		assertThat(jdbc.queryForObject("select event_url from banners where id=?", String.class, bytes(legacy)))
				.isEqualTo("/old-event-url");
	}

	@Test
	@DisplayName("이전 URL·생성시각·ID를 순서 교환과 연결 수정 뒤에도 보존한다")
	void preservesLegacyDataDuringReplacement() {
		// given
		UUID old = UUID.randomUUID();
		jdbc.update("insert into banners(id,image_key,display_order,created_at,updated_at,event_url) values(?,'gd209/old.png',4,?,?,?)",
				bytes(old), LocalDateTime.ofInstant(AT, ZoneOffset.UTC), LocalDateTime.ofInstant(AT, ZoneOffset.UTC), "/events/legacy?keep=true");
		// when: 순서에 구멍이 있는 이전 행도 빈 자리에 등록한 뒤 1~N으로 정리한다.
		Banner added = banners.create(actor(), settings(1));
		banners.update(actor(), old, settings(1));
		// then
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactly(old, added.getId());
		assertThat(jdbc.queryForObject("select event_url from banners where id=?", String.class, bytes(old)))
				.isEqualTo("/events/legacy?keep=true");
		assertThat(banners.listForAdmin(actor()).getFirst().getCreatedAt()).isEqualTo(AT);
		assertThat(banners.list(actor())).hasSize(2);
	}

	@Test
	@DisplayName("권한·필수 입력·UUID 해석·업무 오류를 기존 봉투로 구분한다")
	void validatesAuthorizationAndInput() throws Exception {
		// given / when / then
		mvc.perform(get("/api/v1/banners")).andExpect(status().isUnauthorized());
		mvc.perform(as(user, "USER", get(ADMIN_PATH))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("BANNER-002"));
		mvc.perform(as(inactive, "ADMIN", get("/api/v1/banners"))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("BANNER-008"));
		mvc.perform(as(admin, "ADMIN", post(ADMIN_PATH)).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(as(admin, "ADMIN", put(ADMIN_PATH + "/bad-id")).contentType(MediaType.APPLICATION_JSON).content(write(1)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		mvc.perform(as(admin, "ADMIN", post(ADMIN_PATH)).contentType(MediaType.APPLICATION_JSON).content(write(0)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BANNER-001"));
		assertThat(banners.listForAdmin(actor())).isEmpty();
	}

	@Test
	@DisplayName("전체 순서 요청의 중복·누락은 저장하지 않고 BANNER-005를 반환한다")
	void rejectsIncompleteReorder() throws Exception {
		// given
		Banner one = banners.create(actor(), settings(1));
		Banner two = banners.create(actor(), settings(2));
		// when / then
		for (List<UUID> ids : List.of(List.of(one.getId()), List.of(one.getId(),one.getId()))) {
			mvc.perform(as(admin, "ADMIN", put(ADMIN_PATH + "/order")).contentType(MediaType.APPLICATION_JSON).content(order(ids)))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BANNER-005"));
		}
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactly(one.getId(),two.getId());
	}

	@Test
	@DisplayName("감사 저장 실패는 등록·재배치·기존 URL을 모두 원래 상태로 롤백한다")
	void rollsBackCatalogWhenAuditFails() {
		// given
		Banner one = banners.create(actor(), settings(1));
		doThrow(new IllegalStateException("test audit failure")).when(audit).append(any());
		// when / then
		assertThatThrownBy(() -> banners.create(actor(), settings(1))).isInstanceOf(DataAccessException.class)
				.hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("test audit failure");
		reset(audit);
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactly(one.getId());
		assertThat(banners.listForAdmin(actor()).getFirst().getDisplayOrder()).isEqualTo(1);
		assertThat(countAudits()).isEqualTo(1);
	}

	@Test
	@DisplayName("감사 실패 시 기존 다섯 배너의 순서 교환도 원자적으로 롤백한다")
	void rollsBackFullReorderWhenAuditFails() {
		// given
		for (int i = 1; i <= 5; i++) banners.create(actor(), settings(i));
		List<UUID> original = banners.listForAdmin(actor()).stream().map(Banner::getId).toList();
		List<UUID> reversed = new ArrayList<>(original); Collections.reverse(reversed);
		doThrow(new IllegalStateException("test audit failure")).when(audit).append(any());
		// when / then
		assertThatThrownBy(() -> banners.reorder(actor(), reversed)).isInstanceOf(DataAccessException.class)
				.hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("test audit failure");
		reset(audit);
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactlyElementsOf(original);
	}

	@Test
	@DisplayName("빈 목록의 동시 등록도 다섯 개까지만 성공하고 순서는 1~5를 유지한다")
	void serializesConcurrentCreates() throws Exception {
		// given: 서로 다른 이벤트를 사용해 이벤트 행 잠금이 아니라 카탈로그 잠금을 검증한다.
		for (int i = 0; i < 8; i++) {
			UUID id = UUID.randomUUID();
			extraEvents.add(id);
			jdbc.update("""
					insert into events(id,title,description,event_type,weighting_enabled,starts_at,ends_at,status,
					membership_rule,created_at,updated_at)
					select ?,title,description,event_type,weighting_enabled,starts_at,ends_at,status,
					membership_rule,created_at,updated_at from events where id=?
					""", bytes(id), bytes(event));
		}
		CountDownLatch ready = new CountDownLatch(8);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (UUID connectedEvent : extraEvents) futures.add(pool.submit(() -> {
				ready.countDown();
				if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
				try { banners.create(actor(), new BannerSettings(connectedEvent, "gd209/concurrent.png", 1)); return true; }
				catch (BusinessException failure) {
					assertThat(failure.getErrorCode()).isEqualTo(BannerErrorCode.LIMIT_EXCEEDED);
					return false;
				}
			}));
			// when
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
			int succeeded = 0;
			for (Future<Boolean> future : futures) if (future.get(30,TimeUnit.SECONDS)) succeeded++;
			// then
			assertThat(succeeded).isEqualTo(5);
			assertThat(banners.listForAdmin(actor())).extracting(Banner::getDisplayOrder).containsExactly(1,2,3,4,5);
			assertThat(countAudits()).isEqualTo(5);
		} finally {
			start.countDown(); pool.shutdownNow();
			assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	@DisplayName("반복 순서 교환에도 생성 시각은 유지되고 수정 시각은 실제 UTC 범위 안에 있다")
	void preservesUtcInstantsAcrossRepeatedReorder() {
		// given
		Banner first = banners.create(actor(), settings(1));
		Banner second = banners.create(actor(), settings(2));
		java.util.Map<UUID, Instant> created = java.util.Map.of(first.getId(), first.getCreatedAt(),
				second.getId(), second.getCreatedAt());
		List<UUID> forward = List.of(first.getId(), second.getId());
		List<UUID> reverse = List.of(second.getId(), first.getId());
		for (List<UUID> next : List.of(reverse, forward, reverse, forward)) {
			Instant startedAt = time.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
			// when
			List<Banner> reordered = banners.reorder(actor(), next);
			Instant finishedAt = time.now().plusNanos(1000);
			// then: JVM/JDBC 기본 시간대와 관계없이 원래 Instant와 같아야 한다.
			for (Banner banner : reordered) {
				assertThat(banner.getCreatedAt()).isEqualTo(created.get(banner.getId()));
				assertThat(banner.getUpdatedAt()).isBetween(startedAt, finishedAt);
			}
		}
	}

	@Test
	@DisplayName("이벤트 연결 부재·취소는 약속된 오류이며 기존 배너를 수정하지 않는다")
	void rejectsUnavailableEventConnections() throws Exception {
		// given
		Banner existing = banners.create(actor(), settings(1));
		// when / then
		String missing = write(1).replace(event.toString(), UUID.randomUUID().toString());
		mvc.perform(as(admin, "ADMIN", post(ADMIN_PATH)).contentType(MediaType.APPLICATION_JSON).content(missing))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BANNER-006"));
		jdbc.update("update events set status='CANCELED' where id=?", bytes(event));
		mvc.perform(as(admin, "ADMIN", put(ADMIN_PATH + "/" + existing.getId()))
				.contentType(MediaType.APPLICATION_JSON).content(write(1)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BANNER-007"));
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactly(existing.getId());
		assertThat(countAudits()).isEqualTo(1);
	}

	@Test
	@DisplayName("감사 실패 시 수정과 삭제도 이전 설정·순서로 롤백한다")
	void rollsBackUpdateAndDelete() {
		// given
		Banner first = banners.create(actor(), settings(1));
		Banner second = banners.create(actor(), settings(2));
		doThrow(new IllegalStateException("test audit failure")).when(audit).append(any());
		// when / then
		assertThatThrownBy(() -> banners.update(actor(), first.getId(), new BannerSettings(event, "gd209/changed.png", 2)))
				.isInstanceOf(DataAccessException.class)
				.hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("test audit failure");
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactly(first.getId(), second.getId());
		assertThat(banners.listForAdmin(actor()).getFirst().getImageKey()).isEqualTo("gd209/banner.png");
		assertThatThrownBy(() -> banners.delete(actor(), first.getId())).isInstanceOf(DataAccessException.class)
				.hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("test audit failure");
		reset(audit);
		assertThat(banners.listForAdmin(actor())).extracting(Banner::getId).containsExactly(first.getId(), second.getId());
		assertThat(countAudits()).isEqualTo(2);
	}

	private void insertUser(UUID id, String role, String status) {
		jdbc.update("insert into users(id,name,role,status,membership,created_at,updated_at) values(?,'GD209',?,?,'VIP',?,?)",
				bytes(id),role,status,LocalDateTime.ofInstant(AT, ZoneOffset.UTC),LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
	}
	private User actor() { return new User(admin,"GD209",UserRole.ADMIN,UserStatus.ACTIVE,null,null,null,null,null,AT,AT); }
	private BannerSettings settings(int order) { return new BannerSettings(event,"gd209/banner.png",order); }
	private String write(int order) { return "{\"eventId\":\""+event+"\",\"imageKey\":\"gd209/banner.png\",\"displayOrder\":"+order+"}"; }
	private static String order(List<UUID> ids) { return "{\"bannerIds\":["+ids.stream().map(id -> "\""+id+"\"").collect(java.util.stream.Collectors.joining(","))+"]}"; }
	private static MockHttpServletRequestBuilder as(UUID id,String role,MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID",id).header("X-User-Role",role);
	}
	private long countAudits() { return jdbc.queryForObject("select count(*) from audit_logs where actor_id=?",Long.class,bytes(admin)); }
	private static byte[] bytes(UUID id) { return UuidBinary.toBytes(id); }
}
