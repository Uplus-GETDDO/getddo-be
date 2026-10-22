package com.getddo.core.ticket.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.ticket.domain.AdminTicketHistoryFilter;
import com.getddo.core.ticket.domain.TicketConsistencyResult;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.domain.TicketHolding;
import com.getddo.core.ticket.domain.TicketInconsistencyReport;
import com.getddo.core.ticket.domain.TicketInconsistencyView;
import com.getddo.core.ticket.domain.TicketRefundFilter;
import com.getddo.core.ticket.domain.TicketStatus;
import com.getddo.core.ticket.domain.TicketGrantFilter;
import com.getddo.core.ticket.domain.TicketGrantView;
import com.getddo.core.ticket.exception.TicketErrorCode;
import com.getddo.core.ticket.exception.TicketException;
import com.getddo.core.ticket.repository.AdminTicketQueryRepository;
import com.getddo.core.ticket.repository.TicketQueryRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserNameRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminTicketQueryServiceTest {

	private static final PageQuery PAGE = new PageQuery(1, 20);
	private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
	private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
	private static final UUID NO_SUCH_USER = UUID.randomUUID();
	private static final Instant T2 = Instant.parse("2026-10-02T00:00:00Z");

	@Mock
	private AdminTicketQueryRepository repository;
	@Mock
	private TicketQueryRepository queryRepository;
	@Mock
	private UserNameRepository userNames;

	private AdminTicketQueryService service;

	@BeforeEach
	void setUp() {
		service = new AdminTicketQueryService(repository, queryRepository,
				new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), userNames);
	}

	private static User user(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "사용자", role, status, null, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("활성 관리자는 조건과 페이지를 저장소에 그대로 전달해 결과를 받는다")
	void adminGetsRepositoryResult() {
		// given
		TicketGrantFilter filter = TicketGrantFilter.none();
		PageResult<TicketGrantView> expected = new PageResult<>(List.of(), 1, 20, 0);
		when(repository.findGrants(filter, PAGE)).thenReturn(expected);
		// when
		PageResult<TicketGrantView> result = service.getGrants(user(UserRole.ADMIN, UserStatus.ACTIVE), filter, PAGE);
		// then
		assertThat(result).isSameAs(expected);
		verify(repository).findGrants(filter, PAGE);
	}

	@ParameterizedTest(name = "{0} {1}은 거절한다")
	@CsvSource({"USER, ACTIVE", "ADMIN, INACTIVE", "USER, INACTIVE"})
	@DisplayName("활성 관리자가 아니면 조회하지 않고 TICKET-010으로 거절한다")
	void rejectsNonAdmin(UserRole role, UserStatus status) {
		// given
		User caller = user(role, status);
		// when
		// then
		assertThatThrownBy(() -> service.getGrants(caller, TicketGrantFilter.none(), PAGE))
				.isInstanceOf(TicketException.class)
				.extracting(failure -> ((TicketException) failure).getErrorCode())
				.isEqualTo(TicketErrorCode.TICKET_ADMIN_REQUIRED);
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("사용자 문맥이 없으면 조회하지 않고 TICKET-010으로 거절한다")
	void rejectsMissingUser() {
		// given
		// when
		// then
		assertThatThrownBy(() -> service.getGrants(null, TicketGrantFilter.none(), PAGE))
				.isInstanceOf(TicketException.class)
				.extracting(failure -> ((TicketException) failure).getErrorCode())
				.isEqualTo(TicketErrorCode.TICKET_ADMIN_REQUIRED);
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("시작 시각이 끝 시각보다 앞서지 않으면 조회하지 않고 조회 조건 오류로 거절한다")
	void rejectsInvertedOrEmptyPeriod() {
		// given
		TicketGrantFilter inverted = TicketGrantFilter.of(null, null, null, null, null, T2, T1);
		TicketGrantFilter empty = TicketGrantFilter.of(null, null, null, null, null, T1, T1);
		User admin = user(UserRole.ADMIN, UserStatus.ACTIVE);
		// when
		// then
		for (TicketGrantFilter invalid : List.of(inverted, empty)) {
			assertThatThrownBy(() -> service.getGrants(admin, invalid, PAGE))
					.isInstanceOf(TicketException.class)
					.extracting(failure -> ((TicketException) failure).getErrorCode())
					.isEqualTo(TicketErrorCode.TICKET_INVALID_HISTORY_QUERY);
		}
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("keyword의 앞뒤 공백은 지우고 공백뿐이면 조건을 쓰지 않는다")
	void normalizesKeyword() {
		// given
		TicketGrantFilter padded = TicketGrantFilter.of(null, "  홍길동 ", null, null, null, null, null);
		TicketGrantFilter blank = TicketGrantFilter.of(null, "   ", null, null, null, null, null);
		// when
		// then
		assertThat(padded.getKeyword()).isEqualTo("홍길동");
		assertThat(blank.getKeyword()).isNull();
	}

	@Test
	@DisplayName("처리 이력·반환 이력 조회도 관리자만 할 수 있고 기간이 뒤집히면 거절한다")
	void historiesAndRefundsGuardAdminAndPeriod() {
		// given
		User plain = user(UserRole.USER, UserStatus.ACTIVE);
		User admin = user(UserRole.ADMIN, UserStatus.ACTIVE);
		AdminTicketHistoryFilter inverted = AdminTicketHistoryFilter.of(null, null, null, null, T2, T1);
		TicketRefundFilter invertedRefund = TicketRefundFilter.of(null, null, T2, T1);
		// when
		// then
		assertThatThrownBy(() -> service.getHistories(plain, inverted, PAGE)).isInstanceOf(TicketException.class)
				.extracting(failure -> ((TicketException) failure).getErrorCode())
				.isEqualTo(TicketErrorCode.TICKET_ADMIN_REQUIRED);
		assertThatThrownBy(() -> service.getRefunds(plain, invertedRefund, PAGE)).isInstanceOf(TicketException.class)
				.extracting(failure -> ((TicketException) failure).getErrorCode())
				.isEqualTo(TicketErrorCode.TICKET_ADMIN_REQUIRED);
		assertThatThrownBy(() -> service.getHistories(admin, inverted, PAGE)).isInstanceOf(TicketException.class)
				.extracting(failure -> ((TicketException) failure).getErrorCode())
				.isEqualTo(TicketErrorCode.TICKET_INVALID_HISTORY_QUERY);
		assertThatThrownBy(() -> service.getRefunds(admin, invertedRefund, PAGE)).isInstanceOf(TicketException.class)
				.extracting(failure -> ((TicketException) failure).getErrorCode())
				.isEqualTo(TicketErrorCode.TICKET_INVALID_HISTORY_QUERY);
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("정합성 확인은 없는 사용자를 TICKET-011로 거절하고 확인 시각과 어긋난 응모권을 함께 돌려준다")
	void consistencyChecksUserAndReturnsMismatches() {
		// given
		UUID userId = UUID.randomUUID();
		User admin = user(UserRole.ADMIN, UserStatus.ACTIVE);
		TicketInconsistencyView mismatch = new TicketInconsistencyView(UUID.randomUUID(), userId, "홍길동",
				TicketGrade.GOLD, 3, 1, TicketStatus.SPENT, TicketStatus.AVAILABLE);
		when(repository.userExists(userId)).thenReturn(true);
		when(repository.userExists(NO_SUCH_USER)).thenReturn(false);
		when(userNames.findNamesByIds(Set.of(userId))).thenReturn(Map.of(userId, "홍길동"));
		when(queryRepository.findHoldings(userId, NOW)).thenReturn(List.of(
				new TicketHolding(TicketGrade.GOLD, NOW.plusSeconds(60), 2),
				new TicketHolding(TicketGrade.BRONZE, NOW.plusSeconds(60), 3)));
		when(repository.findInconsistenciesOf(userId)).thenReturn(List.of(mismatch));
		// when
		TicketConsistencyResult result = service.getConsistency(admin, userId);
		// then
		assertThat(result.getCheckedAt()).isEqualTo(NOW);
		assertThat(result.getUserName()).isEqualTo("홍길동");
		assertThat(result.getAvailableCount()).isEqualTo(5);
		assertThat(result.isConsistent()).isFalse();
		assertThat(result.getInconsistencies()).containsExactly(mismatch);
		assertThatThrownBy(() -> service.getConsistency(admin, NO_SUCH_USER)).isInstanceOf(TicketException.class)
				.extracting(failure -> ((TicketException) failure).getErrorCode())
				.isEqualTo(TicketErrorCode.TICKET_USER_NOT_FOUND);
	}

	@Test
	@DisplayName("어긋난 응모권이 없으면 일치이고 전체 조회는 관리자 확인 뒤 요청 시각과 함께 돌려준다")
	void consistentWhenNoMismatchAndReportCarriesCheckedAt() {
		// given
		UUID userId = UUID.randomUUID();
		User admin = user(UserRole.ADMIN, UserStatus.ACTIVE);
		PageResult<TicketInconsistencyView> empty = new PageResult<>(List.of(), 1, 20, 0);
		when(repository.userExists(userId)).thenReturn(true);
		when(queryRepository.findHoldings(userId, NOW)).thenReturn(List.of());
		when(repository.findInconsistenciesOf(userId)).thenReturn(List.of());
		when(repository.findInconsistencies(userId, PAGE)).thenReturn(empty);
		// when
		TicketConsistencyResult result = service.getConsistency(admin, userId);
		TicketInconsistencyReport report = service.getInconsistencies(admin, userId, PAGE);
		// then
		assertThat(result.isConsistent()).isTrue();
		assertThat(result.getAvailableCount()).isZero();
		assertThat(report.getPage()).isSameAs(empty);
		assertThat(report.getCheckedAt()).isEqualTo(NOW);
		assertThatThrownBy(() -> service.getInconsistencies(user(UserRole.USER, UserStatus.ACTIVE), userId, PAGE))
				.isInstanceOf(TicketException.class);
	}
}
