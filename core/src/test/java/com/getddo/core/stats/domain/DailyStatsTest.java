package com.getddo.core.stats.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.stats.domain.DailyStats.Day;
import com.getddo.core.ticket.domain.GrantSourceType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyStatsTest {

	private static final LocalDate DATE = LocalDate.of(2026, 10, 11);

	private static Map<GrantSourceType, Long> granted() {
		Map<GrantSourceType, Long> map = new EnumMap<>(GrantSourceType.class);
		for (GrantSourceType type : GrantSourceType.values()) {
			map.put(type, 1L);
		}
		return map;
	}

	@Test
	@DisplayName("지급 근거 키가 빠졌거나 음수 집계값이 있으면 만들 수 없다")
	void rejectsInvalidDay() {
		Map<GrantSourceType, Long> missing = granted();
		missing.remove(GrantSourceType.GAME);
		assertThatThrownBy(() -> new Day(DATE, 0, 0, 0, missing, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Day(DATE, 0, 0, 0, null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Day(null, 0, 0, 0, granted(), 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Day(DATE, -1, 0, 0, granted(), 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Day(DATE, 0, 0, 0, granted(), 0, -1)).isInstanceOf(IllegalArgumentException.class);
		// 응모자 수는 응모 건수를 넘을 수 없다(같으면 허용)
		assertThatThrownBy(() -> new Day(DATE, 2, 1, 5, granted(), 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new Day(DATE, 1, 1, 1, granted(), 0, 0).entrantCount()).isEqualTo(1);
		Map<GrantSourceType, Long> negative = granted();
		negative.put(GrantSourceType.MISSION, -1L);
		assertThatThrownBy(() -> new Day(DATE, 0, 0, 0, negative, 0, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("호출자가 넘긴 컬렉션을 바꿔도 값이 변하지 않고, 꺼낸 컬렉션은 수정할 수 없다")
	void copiesCollections() {
		Map<GrantSourceType, Long> source = granted();
		List<Day> days = new ArrayList<>(List.of(new Day(DATE, 1, 2, 3, source, 4, 5)));
		DailyStats stats = new DailyStats(days, 10);
		source.put(GrantSourceType.MISSION, 99L);
		days.clear();
		assertThat(stats.days()).hasSize(1);
		assertThat(stats.days().get(0).grantedTickets()).containsEntry(GrantSourceType.MISSION, 1L);
		assertThatThrownBy(() -> stats.days().add(stats.days().get(0))).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> stats.days().get(0).grantedTickets().put(GrantSourceType.GAME, 7L))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("활성 일반 사용자 수가 음수면 만들 수 없다")
	void rejectsNegativeTotalUserCount() {
		assertThatThrownBy(() -> new DailyStats(List.of(), -1)).isInstanceOf(IllegalArgumentException.class);
	}
}
