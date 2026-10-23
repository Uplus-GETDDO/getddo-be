package com.getddo.core.event.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventModelTest {
	private static final UUID ID = UUID.randomUUID();

	@Test
	@DisplayName("등록 입력은 원본 경품 목록의 변경에 영향받지 않고 같은 값이면 동일하다")
	void registrationKeepsAnImmutableValueSnapshot() {
		// given
		List<EventRegistration.Prize> prizes = new ArrayList<>(List.of(new EventRegistration.Prize(1, "경품", null, null, 2)));
		EventRegistration registration = registration(prizes);
		EventRegistration sameValue = registration(List.of(new EventRegistration.Prize(1, "경품", null, null, 2)));

		// when
		prizes.clear();

		// then
		assertThat(registration.getPrizes()).hasSize(1);
		assertThat(registration).isEqualTo(sameValue).hasSameHashCodeAs(sameValue);
		assertThatThrownBy(() -> registration.getPrizes().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("잘못된 등록 입력의 null 목록과 null 경품은 서비스가 검증할 수 있도록 보존한다")
	void invalidRegistrationInputDoesNotFailDuringConstruction() {
		// given / when / then
		assertThat(registration(null).getPrizes()).isNull();
		assertThat(registration(Arrays.asList((EventRegistration.Prize) null)).getPrizes())
				.containsExactly((EventRegistration.Prize) null);
	}

	@Test
	@DisplayName("저장 결과와 조회 모델은 경품 목록의 변경을 막고 값 동등성을 유지한다")
	void registeredEventAndViewKeepAnImmutableValueSnapshot() {
		// given
		List<RegisteredEvent.Prize> prizes = new ArrayList<>(List.of(new RegisteredEvent.Prize(ID, 1, "경품", null, null, 2)));
		RegisteredEvent event = registered(prizes);
		RegisteredEvent sameValue = registered(List.of(new RegisteredEvent.Prize(ID, 1, "경품", null, null, 2)));
		EventView view = new EventView(event, EventStatus.SCHEDULED, null);

		// when
		prizes.clear();

		// then
		assertThat(event).isEqualTo(sameValue).hasSameHashCodeAs(sameValue);
		assertThat(view).isEqualTo(new EventView(sameValue, EventStatus.SCHEDULED, null));
		assertThat(view.getDetails().getPrizes()).hasSize(1);
		assertThatThrownBy(() -> view.getDetails().getPrizes().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("조회 모델은 상세와 공개 상태가 없으면 생성을 거절하고 취소 시각은 선택 값으로 둔다")
	void viewRequiresDetailsAndPublicStatus() {
		// given
		RegisteredEvent event = registered(List.of());
		// when / then
		assertThatThrownBy(() -> new EventView(null, EventStatus.SCHEDULED, null))
				.isInstanceOf(NullPointerException.class).hasMessage("details");
		assertThatThrownBy(() -> new EventView(event, null, null))
				.isInstanceOf(NullPointerException.class).hasMessage("publicStatus");
		assertThat(new EventView(event, EventStatus.SCHEDULED, null).getCanceledAt()).isNull();
	}

	@Test
	@DisplayName("경품 인원은 long으로 합산하고 목록의 참여 여부를 연결해도 DB 합계를 유지한다")
	void winnerCountDoesNotOverflowOrDisappearWhenAttachingParticipation() {
		// given
		RegisteredEvent event = registered(List.of(
				new RegisteredEvent.Prize(ID, 1, "첫 경품", null, null, Integer.MAX_VALUE),
				new RegisteredEvent.Prize(UUID.randomUUID(), 2, "둘째 경품", null, null, Integer.MAX_VALUE)));

		// when / then
		assertThat(new EventView(event, EventStatus.SCHEDULED, null).getWinnerCount()).isEqualTo(4_294_967_294L);
		EventView summary = new EventView(registered(List.of()), EventStatus.SCHEDULED, null, 9);
		assertThat(summary.withMyEntered(true).getWinnerCount()).isEqualTo(9);
		assertThat(summary.withMyEntered(true).isMyEntered()).isTrue();
		assertThat(new EventView(registered(List.of()), EventStatus.SCHEDULED, null).getWinnerCount()).isZero();
		assertThatThrownBy(() -> new EventView(event, EventStatus.SCHEDULED, null, -1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private EventRegistration registration(List<EventRegistration.Prize> prizes) {
		return new EventRegistration(ID, "이벤트", "설명", null, null, EventType.NO_TICKET, false, null,
				MembershipRule.excellent, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), prizes);
	}

	private RegisteredEvent registered(List<RegisteredEvent.Prize> prizes) {
		return new RegisteredEvent(ID, "이벤트", "설명", null, null, EventType.NO_TICKET, false, null,
				MembershipRule.excellent, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), EventStatus.SCHEDULED,
				Instant.EPOCH, Instant.EPOCH, prizes);
	}
}
