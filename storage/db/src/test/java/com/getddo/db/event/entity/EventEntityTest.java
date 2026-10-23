package com.getddo.db.event.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.getddo.core.event.domain.EventRegistration;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.MembershipRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEntityTest {
	@Test
	@DisplayName("이미 삭제된 이벤트는 같은 시각·다른 시각의 재삭제를 모두 거절하고 최초 삭제 시각을 보존한다")
	void preservesOriginalDeletionTimeWhenCalledAgain() {
		// given
		Instant deletedAt = Instant.parse("2026-10-14T00:00:00Z");
		Instant start = deletedAt.plusSeconds(3600);
		EventEntity event = new EventEntity(new EventRegistration(UUID.randomUUID(), "이벤트", "설명", null, null,
				EventType.NO_TICKET, false, null, MembershipRule.vip, start, start.plusSeconds(3600),
				List.of(new EventRegistration.Prize(1, "경품", null, null, 1))), EventStatus.SCHEDULED);
		event.markDeleted(deletedAt);
		// when / then
		for (Instant repeatedAt : List.of(deletedAt, deletedAt.plusSeconds(60))) {
			assertThatThrownBy(() -> event.markDeleted(repeatedAt))
					.isInstanceOf(IllegalStateException.class)
					.hasMessage("이미 삭제된 이벤트의 삭제 시각은 변경할 수 없습니다.");
			assertThat(event.getDeletedAt()).isEqualTo(deletedAt);
		}
	}

	@Test
	@DisplayName("임시 등수가 0·음수이면 기존 경품 등수를 변경하지 않고 거절한다")
	void rejectsNonPositiveTemporaryRanksWithoutChangingPrize() {
		// given
		Instant start = Instant.parse("2099-10-12T00:00:00Z");
		EventEntity event = new EventEntity(new EventRegistration(UUID.randomUUID(), "이벤트", "설명", null, null,
				EventType.NO_TICKET, false, null, MembershipRule.vip, start, start.plusSeconds(3600),
				List.of(new EventRegistration.Prize(2, "경품", null, null, 1))), EventStatus.SCHEDULED);
		EventPrizeEntity prize = event.getPrizes().getFirst();
		// when / then
		for (int invalidRank : new int[]{0, -1}) {
			assertThatThrownBy(() -> prize.moveToTemporaryRank(invalidRank))
					.isInstanceOf(IllegalArgumentException.class);
			assertThat(prize.getRank()).isEqualTo(2);
		}
	}

	@Test
	@DisplayName("경품 소유권 호출 계약이 깨지면 목록 제거·등수 변경 전에 중단한다")
	void rejectsUnknownPrizeIdBeforeChangingCollection() {
		// given
		Instant start = Instant.parse("2099-10-12T00:00:00Z");
		EventEntity event = new EventEntity(new EventRegistration(UUID.randomUUID(), "이벤트", "설명", null, null,
				EventType.NO_TICKET, false, null, MembershipRule.vip, start, start.plusSeconds(3600), List.of(
						new EventRegistration.Prize(1, "첫 경품", null, null, 1),
						new EventRegistration.Prize(2, "둘째 경품", null, null, 1))), EventStatus.SCHEDULED);
		EventPrizeEntity first = event.getPrizes().getFirst();
		EventPrizeEntity second = event.getPrizes().getLast();
		UUID firstId = UUID.randomUUID();
		// 영속화한 Entity의 식별자 상태를 재현한다.
		ReflectionTestUtils.setField(first, "id", firstId);
		ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
		List<EventRegistration.Prize> replacement = List.of(
				new EventRegistration.Prize(firstId, 2, "첫 수정", null, null, 1),
				new EventRegistration.Prize(UUID.randomUUID(), 1, "타 이벤트 경품", null, null, 1));
		// when / then
		assertThatThrownBy(() -> event.preparePrizeReplacement(replacement))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("기존 경품 ID는 해당 이벤트의 경품이어야 합니다.");
		assertThat(event.getPrizes()).containsExactly(first, second);
		assertThat(first.getRank()).isEqualTo(1);
		assertThat(second.getRank()).isEqualTo(2);
	}
}
