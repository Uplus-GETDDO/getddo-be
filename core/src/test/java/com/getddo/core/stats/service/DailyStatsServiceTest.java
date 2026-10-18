package com.getddo.core.stats.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.stats.domain.DailyStats;
import com.getddo.core.stats.domain.DailyStats.Day;
import com.getddo.core.stats.domain.DailyStats.EntryCounts;
import com.getddo.core.stats.exception.StatsErrorCode;
import com.getddo.core.stats.exception.StatsException;
import com.getddo.core.stats.repository.DailyStatsRepository;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyStatsServiceTest {

	private static final User ADMIN = user(UserRole.ADMIN, UserStatus.ACTIVE);
	private static final LocalDate FROM = LocalDate.of(2026, 10, 11);
	private static final LocalDate TO = LocalDate.of(2026, 10, 13);
	/** KST 2026-10-11 00:00. */
	private static final Instant START = Instant.parse("2026-10-10T15:00:00Z");
	/** KST 2026-10-14 00:00(끝 날짜 다음 날 자정). */
	private static final Instant END = Instant.parse("2026-10-13T15:00:00Z");

	@Mock
	private DailyStatsRepository repository;

	private static User user(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "사용자", role, status, null, null, null, null, null, null, null);
	}

	private DailyStatsService service() {
		return new DailyStatsService(repository, new TimeProvider(Clock.fixed(START, ZoneOffset.UTC)));
	}

	private static void assertError(Throwable thrown, StatsErrorCode expected) {
		assertThat(thrown).isInstanceOfSatisfying(StatsException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	@Test
	@DisplayName("관리자가 아니거나 활성이 아니면 STATS-002로 거절하고 저장소를 읽지 않는다")
	void rejectsNonAdmins() {
		for (User caller : new User[] {null, user(UserRole.USER, UserStatus.ACTIVE),
				user(UserRole.ADMIN, UserStatus.INACTIVE)}) {
			assertThatThrownBy(() -> service().daily(caller, FROM, TO))
					.satisfies(e -> assertError(e, StatsErrorCode.ADMIN_REQUIRED));
		}
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("시작·끝 날짜가 없거나 끝이 시작보다 앞서거나 32일째로 넘어가면 STATS-001이다")
	void rejectsInvalidPeriod() {
		LocalDate from = LocalDate.of(2026, 10, 1);
		assertThatThrownBy(() -> service().daily(ADMIN, null, TO))
				.satisfies(e -> assertError(e, StatsErrorCode.INVALID_PERIOD));
		assertThatThrownBy(() -> service().daily(ADMIN, FROM, null))
				.satisfies(e -> assertError(e, StatsErrorCode.INVALID_PERIOD));
		assertThatThrownBy(() -> service().daily(ADMIN, TO, FROM))
				.satisfies(e -> assertError(e, StatsErrorCode.INVALID_PERIOD));
		assertThatThrownBy(() -> service().daily(ADMIN, from, from.plusDays(31)))
				.satisfies(e -> assertError(e, StatsErrorCode.INVALID_PERIOD));
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("31일 기간은 허용하고 하루짜리 기간도 허용한다")
	void allowsBoundaryPeriods() {
		LocalDate from = LocalDate.of(2026, 10, 1);
		DailyStats month = service().daily(ADMIN, from, from.plusDays(30));
		DailyStats oneDay = service().daily(ADMIN, from, from);
		assertThat(month.days()).hasSize(31);
		assertThat(oneDay.days()).hasSize(1);
	}

	@Test
	@DisplayName("활동 없는 날도 0으로 채우고 지급 근거별 키를 모두 담으며 기간 경계는 KST 자정으로 넘긴다")
	void fillsEmptyDaysAndSources() {
		// given
		when(repository.countEntries(START, END))
				.thenReturn(Map.of(LocalDate.of(2026, 10, 12), new EntryCounts(2, 5)));
		when(repository.countActiveUsers(START, END, FROM, TO)).thenReturn(Map.of(LocalDate.of(2026, 10, 12), 7L));
		Map<GrantSourceType, Long> granted = new EnumMap<>(GrantSourceType.class);
		granted.put(GrantSourceType.MISSION, 3L);
		when(repository.countGrantedTickets(START, END)).thenReturn(Map.of(LocalDate.of(2026, 10, 12), granted));
		when(repository.countUsedTickets(START, END)).thenReturn(Map.of(LocalDate.of(2026, 10, 13), 9L));
		when(repository.countAbuseCases(START, END)).thenReturn(Map.of(LocalDate.of(2026, 10, 11), 1L));
		when(repository.countActiveRegularUsers()).thenReturn(120L);
		// when
		DailyStats stats = service().daily(ADMIN, FROM, TO);
		// then
		assertThat(stats.totalUserCount()).isEqualTo(120L);
		assertThat(stats.days()).extracting(Day::date)
				.containsExactly(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13));
		Day first = stats.days().get(0);
		assertThat(first.entrantCount()).isZero();
		assertThat(first.entryCount()).isZero();
		assertThat(first.activeUserCount()).isZero();
		assertThat(first.blockedCount()).isEqualTo(1L);
		assertThat(first.grantedTickets()).containsOnlyKeys(GrantSourceType.values())
				.containsValues(0L, 0L, 0L);
		Day second = stats.days().get(1);
		assertThat(second.entrantCount()).isEqualTo(2L);
		assertThat(second.entryCount()).isEqualTo(5L);
		assertThat(second.activeUserCount()).isEqualTo(7L);
		assertThat(second.grantedTickets()).containsEntry(GrantSourceType.MISSION, 3L)
				.containsEntry(GrantSourceType.ATTENDANCE, 0L).containsEntry(GrantSourceType.GAME, 0L);
		assertThat(stats.days().get(2).usedTickets()).isEqualTo(9L);
	}
}
