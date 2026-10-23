package com.getddo.api.drawing.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationType;
import com.getddo.core.drawing.domain.UserDrawResult;
import com.getddo.core.drawing.repository.DrawPublicationRepository;
import com.getddo.core.drawing.repository.UserDrawResultRepository;
import com.getddo.core.drawing.service.UserDrawResultService;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.repository.EventQueryRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserRepository;
import com.getddo.core.user.service.UserService;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserDrawResultControllerTest {
	private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
	private final UUID eventId = UUID.randomUUID();
	private final UUID actorId = UUID.randomUUID();
	private final UUID prizeId = UUID.randomUUID();
	private final UUID thirdPrizeId = UUID.randomUUID();
	private final DrawPublicationRepository publications = mock(DrawPublicationRepository.class);
	private final UserDrawResultRepository results = mock(UserDrawResultRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		EventQueryRepository events = mock(EventQueryRepository.class);
		RegisteredEvent event = new RegisteredEvent(eventId, "이벤트", "설명", null, null, EventType.NO_TICKET, false,
				null, MembershipRule.vip, NOW.minusSeconds(3600), NOW, EventStatus.DRAW_CONFIRMED, NOW, NOW,
				List.of(new RegisteredEvent.Prize(prizeId, 1, "경품", null, null, 2),
						new RegisteredEvent.Prize(thirdPrizeId, 3, "3등 경품", null, null, 1)));
		when(events.findById(eventId)).thenReturn(Optional.of(new EventView(event, EventStatus.DRAW_CONFIRMED, null)));
		actor(UserRole.USER, UserStatus.ACTIVE);
		mvc = MockMvcBuilders.standaloneSetup(new UserDrawResultController(new UserDrawResultService(events,
				publications, results, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)))))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(new UserService(users))).build();
	}

	@Test
	@DisplayName("공개 전 응답은 비공개 필드를 null로 유지하고 발표 예정 시각을 제외한다")
	void pendingResponseIncludesNullFieldsAndNoSchedule() throws Exception {
		// given
		when(results.hasEntered(eventId, actorId)).thenReturn(true);
		// when / then
		mvc.perform(get("/api/v1/events/{id}/results", eventId).header("X-User-ID", actorId).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.isPublished").value(false)).andExpect(jsonPath("$.data.prizes").isEmpty())
				.andExpect(jsonPath("$.data.publishedAt").value(nullValue())).andExpect(jsonPath("$.data.updatedAt").value(nullValue()))
				.andExpect(jsonPath("$.data.revision").value(nullValue())).andExpect(jsonPath("$.data.publicationScheduledAt").doesNotExist());
		mvc.perform(get("/api/v1/events/{id}/my-result", eventId).header("X-User-ID", actorId).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("PENDING"))
				.andExpect(jsonPath("$.data.prize").value(nullValue())).andExpect(jsonPath("$.data.revision").value(nullValue()));
	}

	@Test
	@DisplayName("공개 응답에 허용한 마스킹 필드만 포함한다")
	void publishedResponseWhitelistsMaskedFields() throws Exception {
		// given
		DrawPublication published = new DrawPublication(UUID.randomUUID(), eventId, UUID.randomUUID(), 1,
				DrawPublicationType.INITIAL, null, null, null, NOW, List.of());
		when(publications.findLatest(eventId)).thenReturn(Optional.of(published));
		when(results.findWinners(published.getId())).thenReturn(List.of(new UserDrawResult.MaskedWinner(prizeId, "김수", "01012345678")));
		// when / then
		mvc.perform(get("/api/v1/events/{id}/results", eventId).header("X-User-ID", actorId).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.prizes[0].rank").value(3))
				.andExpect(jsonPath("$.data.prizes[1].rank").value(1))
				.andExpect(jsonPath("$.data.prizes[1].winners[0].maskedName").value("김*"))
				.andExpect(jsonPath("$.data.prizes[1].winners[0].maskedPhoneNum").value("5678"))
				.andExpect(jsonPath("$.data.prizes[1].winners[0].userId").doesNotExist())
				.andExpect(jsonPath("$.data.prizes[1].winners[0].prizeId").doesNotExist())
				.andExpect(jsonPath("$.data.prizes[1].winners[0].email").doesNotExist())
				.andExpect(jsonPath("$.data.prizes[1].winners[0].name").doesNotExist())
				.andExpect(jsonPath("$.data.reason").doesNotExist());
	}

	@Test
	@DisplayName("공개된 본인 당첨 응답은 경품과 공개 버전을 반환한다")
	void returnsPublishedOwnPrize() throws Exception {
		// given
		DrawPublication published = new DrawPublication(UUID.randomUUID(), eventId, UUID.randomUUID(), 1,
				DrawPublicationType.INITIAL, null, null, null, NOW, List.of());
		when(publications.findLatest(eventId)).thenReturn(Optional.of(published));
		when(results.hasEntered(eventId, actorId)).thenReturn(true);
		when(results.findWonPrize(published.getId(), actorId)).thenReturn(Optional.of(prizeId));
		// when / then
		mvc.perform(get("/api/v1/events/{id}/my-result", eventId).header("X-User-ID", actorId).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("WON"))
				.andExpect(jsonPath("$.data.isPublished").value(true)).andExpect(jsonPath("$.data.revision").value(1))
				.andExpect(jsonPath("$.data.prize.id").value(prizeId.toString()))
				.andExpect(jsonPath("$.data.prize.rank").value(1)).andExpect(jsonPath("$.data.prize.name").value("경품"))
				.andExpect(jsonPath("$.data.prize.winnerCount").value(2))
				.andExpect(jsonPath("$.data.prize.description").value(nullValue()))
				.andExpect(jsonPath("$.data.prize.imageUrl").value(nullValue()));
	}

	@Test
	@DisplayName("사용자 헤더·역할·활성 상태·UUID 형식을 검증한다")
	void enforcesHeadersRolesStatusAndUuid() throws Exception {
		// given
		// when / then
		mvc.perform(get("/api/v1/events/{id}/results", eventId)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-003"));
		mvc.perform(get("/api/v1/events/{id}/results", eventId).header("X-User-ID", actorId).header("X-User-Role", "ADMIN"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-004"));
		actor(UserRole.ADMIN, UserStatus.ACTIVE);
		mvc.perform(get("/api/v1/events/{id}/results", eventId).header("X-User-ID", actorId).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/events/{id}/my-result", eventId).header("X-User-ID", actorId).header("X-User-Role", "ADMIN"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EVENT-010"))
				.andExpect(jsonPath("$.data").value(nullValue()));
		actor(UserRole.USER, UserStatus.INACTIVE);
		mvc.perform(get("/api/v1/events/{id}/results", eventId).header("X-User-ID", actorId).header("X-User-Role", "USER"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EVENT-010"));
		actor(UserRole.USER, UserStatus.ACTIVE);
		mvc.perform(get("/api/v1/events/invalid/results").header("X-User-ID", actorId).header("X-User-Role", "USER"))
				.andExpect(status().isBadRequest());
	}

	private void actor(UserRole role, UserStatus status) {
		when(users.findById(actorId)).thenReturn(Optional.of(new User(actorId, "김민수", role, status,
				null, null, null, null, null, NOW, NOW)));
	}
}
