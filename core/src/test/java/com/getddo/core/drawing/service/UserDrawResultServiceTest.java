package com.getddo.core.drawing.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationType;
import com.getddo.core.drawing.domain.DrawDisplayStatus;
import com.getddo.core.drawing.domain.MyDrawResultStatus;
import com.getddo.core.drawing.domain.UserDrawResult;
import com.getddo.core.drawing.repository.DrawPublicationRepository;
import com.getddo.core.drawing.repository.UserDrawResultRepository;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.repository.EventQueryRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class UserDrawResultServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
	private final UUID eventId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID prizeId = UUID.randomUUID();
	private final EventQueryRepository events = mock(EventQueryRepository.class);
	private final DrawPublicationRepository publications = mock(DrawPublicationRepository.class);
	private final UserDrawResultRepository results = mock(UserDrawResultRepository.class);
	private final UserDrawResultService service = new UserDrawResultService(events, publications, results,
			new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));

	@BeforeEach
	void setUp() { event(EventStatus.DRAW_CONFIRMED); }

	@Test
	@DisplayName("확정·재추첨·발표 상태만으로 미공개 결과를 노출하지 않는다")
	void confirmedAndRedrawingRemainHiddenWithoutPublication() {
		// given
		for (EventStatus status : List.of(EventStatus.DRAW_CONFIRMED, EventStatus.REDRAWING, EventStatus.PUBLISHED)) {
			event(status);
			UserDrawResult.PublicResults response = service.publicResults(user(UserRole.USER, UserStatus.ACTIVE), eventId);
			assertThat(response.isPublished()).isFalse();
			assertThat(response.getPrizes()).isEmpty();
			assertThat(response.getPublishedAt()).isNull();
			assertThat(response.getRevision()).isNull();
			assertThat(response.getUpdatedAt()).isNull();
			assertThat(response.getDisplayStatus()).isEqualTo(DrawDisplayStatus.WAITING);
		}
		verify(results, never()).findWinners(any());
	}

	@Test
	@DisplayName("최신 공개 버전과 등수 내림차순 경품을 조회한다")
	void readsPublishedVersionAndDescendingPrizes() {
		// given
		DrawPublication first = publication(1, NOW.minusSeconds(60));
		DrawPublication latest = publication(2, NOW);
		when(publications.findLatest(eventId)).thenReturn(Optional.of(latest));
		when(publications.findPrevious(eventId, 2)).thenReturn(Optional.of(first));
		when(results.findWinners(latest.getId())).thenReturn(List.of(new UserDrawResult.MaskedWinner(prizeId, "김민수", "01012345678")));
		UserDrawResult.PublicResults response = service.publicResults(user(UserRole.ADMIN, UserStatus.ACTIVE), eventId);
		// when / then
		assertThat(response.isPublished()).isTrue();
		assertThat(response.getRevision()).isEqualTo(2);
		assertThat(response.getPublishedAt()).isEqualTo(first.getPublishedAt());
		assertThat(response.getUpdatedAt()).isEqualTo(NOW);
		assertThat(response.getPrizes()).extracting(UserDrawResult.PublishedPrize::getRank).containsExactly(3, 1);
		assertThat(response.getPrizes().getLast().getUnfilledCount()).isEqualTo(1);
		assertThat(response.getPrizes().getLast().getWinners().getFirst().getMaskedPhoneNum()).isEqualTo("5678");
	}

	@Test
	@DisplayName("본인 응모·대기·당첨·낙첨·이벤트 취소 결과를 구분한다")
	void distinguishesOwnResultsWithoutExposingPendingPrize() {
		// given
		User user = user(UserRole.USER, UserStatus.ACTIVE);
		// when / then
		assertThat(service.myResult(user, eventId).getResult()).isEqualTo(MyDrawResultStatus.NOT_ENTERED);
		when(results.hasEntered(eventId, userId)).thenReturn(true);
		assertThat(service.myResult(user, eventId).getResult()).isEqualTo(MyDrawResultStatus.PENDING);
		assertThat(service.myResult(user, eventId).getPrize()).isNull();
		verify(results, never()).findWonPrize(any(), any());
		DrawPublication published = publication(1, NOW);
		when(publications.findLatest(eventId)).thenReturn(Optional.of(published));
		assertThat(service.myResult(user, eventId).getResult()).isEqualTo(MyDrawResultStatus.LOST);
		when(results.findWonPrize(published.getId(), userId)).thenReturn(Optional.of(prizeId));
		assertThat(service.myResult(user, eventId).getResult()).isEqualTo(MyDrawResultStatus.WON);
		assertThat(service.myResult(user, eventId).getPrize().getId()).isEqualTo(prizeId);
		event(EventStatus.CANCELED);
		assertThat(service.myResult(user, eventId).getResult()).isEqualTo(MyDrawResultStatus.CANCELED);
		assertThat(service.myResult(user, eventId).getPrize()).isNull();
	}

	@Test
	@DisplayName("공개 전 이벤트 취소와 응모자 없음 종료를 구분한다")
	void canceledAndNoEntrantsAreDistinctAndUnpublished() {
		// given
		event(EventStatus.CANCELED);
		// when / then
		assertThat(service.publicResults(user(UserRole.USER, UserStatus.ACTIVE), eventId).getDisplayStatus()).isEqualTo(DrawDisplayStatus.CANCELED);
		assertThat(service.myResult(user(UserRole.USER, UserStatus.ACTIVE), eventId).getResult()).isEqualTo(MyDrawResultStatus.NOT_ENTERED);
		event(EventStatus.CLOSED);
		when(results.hasNoEntriesRun(eventId)).thenReturn(true);
		assertThat(service.publicResults(user(UserRole.USER, UserStatus.ACTIVE), eventId).getDisplayStatus()).isEqualTo(DrawDisplayStatus.NO_ENTRANTS);
		assertThat(service.myResult(user(UserRole.USER, UserStatus.ACTIVE), eventId).getResult()).isEqualTo(MyDrawResultStatus.NOT_ENTERED);
	}

	@Test
	@DisplayName("역할·활성 상태·이벤트 존재 여부를 검증한다")
	void enforcesRoleStatusAndMissingEvent() {
		// given
		// when / then
		assertThatThrownBy(() -> service.myResult(user(UserRole.ADMIN, UserStatus.ACTIVE), eventId))
				.isInstanceOfSatisfying(BusinessException.class, error ->
						assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.ACCESS_DENIED));
		assertThatThrownBy(() -> service.publicResults(user(UserRole.USER, UserStatus.INACTIVE), eventId))
				.isInstanceOfSatisfying(BusinessException.class, error ->
						assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.ACCESS_DENIED));
		assertThatThrownBy(() -> service.publicResults(null, eventId))
				.isInstanceOfSatisfying(BusinessException.class, error ->
						assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.USER_CONTEXT_REQUIRED));
		when(events.findById(eventId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.publicResults(user(UserRole.USER, UserStatus.ACTIVE), eventId))
				.isInstanceOfSatisfying(BusinessException.class, error ->
						assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.EVENT_NOT_FOUND));
	}

	@Test
	@DisplayName("본인 결과 모델은 필수 값과 당첨·경품 조합을 검증한다")
	void rejectsInvalidOwnResultModels() {
		// given
		RegisteredEvent.Prize prize = new RegisteredEvent.Prize(prizeId, 1, "경품", null, null, 1);
		// when / then
		assertThatThrownBy(() -> new UserDrawResult.MyResult(null, MyDrawResultStatus.PENDING, null, false, null, NOW))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, null, null, false, null, NOW))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, MyDrawResultStatus.PENDING, null, false, null, null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, MyDrawResultStatus.WON, null, true, 1, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		for (MyDrawResultStatus result : MyDrawResultStatus.values()) {
			if (result != MyDrawResultStatus.WON) {
				assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, result, prize, true, 1, NOW))
						.isInstanceOf(IllegalArgumentException.class);
			}
		}
	}

	@Test
	@DisplayName("본인 결과 모델은 공개 여부와 버전·결과 상태가 다른 조합을 거절한다")
	void rejectsPublicationStateMismatch() {
		// given
		RegisteredEvent.Prize prize = new RegisteredEvent.Prize(prizeId, 1, "경품", null, null, 1);
		// when / then
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, MyDrawResultStatus.WON, prize, false, null, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, MyDrawResultStatus.LOST, null, false, null, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, MyDrawResultStatus.PENDING, null, true, 1, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, MyDrawResultStatus.NOT_ENTERED, null, false, 1, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, MyDrawResultStatus.CANCELED, null, true, null, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.MyResult(eventId, MyDrawResultStatus.WON, prize, true, 0, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("공개 결과 모델은 비공개 데이터와 모순된 공개 메타데이터를 거절한다")
	void rejectsInvalidPublicResultModels() {
		// given
		UserDrawResult.PublishedPrize prize = new UserDrawResult.PublishedPrize(prizeId, 1, "경품", 1, 1, List.of());
		// when / then
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, false, NOW, null, null, NOW, DrawDisplayStatus.WAITING, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, false, null, 1, null, NOW, DrawDisplayStatus.WAITING, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, false, null, null, NOW, NOW, DrawDisplayStatus.WAITING, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, false, null, null, null, NOW, DrawDisplayStatus.WAITING, List.of(prize)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, true, null, 1, NOW, NOW, DrawDisplayStatus.PUBLISHED, List.of(prize)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, true, NOW, null, NOW, NOW, DrawDisplayStatus.PUBLISHED, List.of(prize)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, true, NOW, 1, null, NOW, DrawDisplayStatus.PUBLISHED, List.of(prize)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, true, NOW, 0, NOW, NOW, DrawDisplayStatus.PUBLISHED, List.of(prize)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, true, NOW, 1, NOW, NOW, DrawDisplayStatus.WAITING, List.of(prize)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, true, NOW, 1, NOW, NOW, DrawDisplayStatus.NO_ENTRANTS, List.of(prize)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublicResults(eventId, false, null, null, null, NOW, DrawDisplayStatus.PUBLISHED, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("공개 경품 모델은 인원 수량과 다른 경품의 당첨자를 거절한다")
	void rejectsInvalidPublishedPrizeModels() {
		// given
		UserDrawResult.MaskedWinner other = new UserDrawResult.MaskedWinner(UUID.randomUUID(), "김민수", null);
		// when / then
		assertThatThrownBy(() -> new UserDrawResult.PublishedPrize(prizeId, 0, "경품", 1, 1, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublishedPrize(prizeId, 1, "경품", 0, 0, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublishedPrize(prizeId, 1, "경품", 1, -1, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublishedPrize(prizeId, 1, "경품", 1, 2, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublishedPrize(prizeId, 1, "경품", 2, 0, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UserDrawResult.PublishedPrize(prizeId, 1, "경품", 1, 0, List.of(other)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@CsvSource({"김수,김*", "김민수,김*수", "남궁민수,남**수", "김,*", "😀민수,😀*수", "😀,*", "'',''"})
	@DisplayName("이름을 글자 수에 맞게 가리고 미등록 전화번호는 null로 유지한다")
	void masksName(String name, String masked) {
		// given
		UserDrawResult.MaskedWinner winner = new UserDrawResult.MaskedWinner(prizeId, name, null);
		// when / then
		assertThat(winner.getMaskedName()).isEqualTo(masked);
		assertThat(winner.getMaskedPhoneNum()).isNull();
	}

	private void event(EventStatus status) {
		RegisteredEvent event = new RegisteredEvent(eventId, "이벤트", "설명", null, null, EventType.NO_TICKET, false,
				null, MembershipRule.vip, NOW.minusSeconds(3600), NOW.minusSeconds(300), status, NOW, NOW,
				List.of(new RegisteredEvent.Prize(prizeId, 1, "경품", null, null, 2),
						new RegisteredEvent.Prize(UUID.randomUUID(), 3, "경품3", null, null, 1)));
		when(events.findById(eventId)).thenReturn(Optional.of(new EventView(event, status, null)));
	}

	private User user(UserRole role, UserStatus status) {
		return new User(userId, "김민수", role, status, null, null, null, null, null, NOW, NOW);
	}

	private DrawPublication publication(int revision, Instant publishedAt) {
		return new DrawPublication(UUID.randomUUID(), eventId, UUID.randomUUID(), revision,
				revision == 1 ? DrawPublicationType.INITIAL : DrawPublicationType.UPDATE,
				revision == 1 ? null : "관리자 사유", revision == 1 ? null : userId,
				revision == 1 ? null : NOW, publishedAt, List.of());
	}
}
