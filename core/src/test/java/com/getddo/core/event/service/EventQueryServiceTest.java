package com.getddo.core.event.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.AdminEventQuery;
import com.getddo.core.event.domain.EventQueryFilter;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.EventSort;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventQueryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventQueryServiceTest {
	private static final UUID ACTOR_ID = UUID.randomUUID();
	private static final EventQueryFilter EMPTY = new EventQueryFilter(null, null, null, null, null, null);
	private static final AdminEventQuery EMPTY_ADMIN = new AdminEventQuery(null, null, null, null, null);
	private User user;
	private EventQueryRepository events;
	private EventQueryService service;

	@BeforeEach
	void setUp() {
		user = user(UserRole.USER, UserStatus.ACTIVE, Membership.VIP);
		events = mock(EventQueryRepository.class);
		service = new EventQueryService(events, new TimeProvider(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
	}

	@Test
	@DisplayName("일반 사용자는 DB 멤버십으로 조회하며 관리자 목록에는 접근할 수 없다")
	void regularUserCanBrowseButCannotUseAdminQuery() {
		// given
		when(events.findAll(eq(EMPTY), any(), eq(true))).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when / then
		assertThat(service.findUserEvents(user, 1, 20, EMPTY).getItems()).isEmpty();
		verify(events).findAll(EMPTY, new PageQuery(1, 20), true);
		assertError(() -> service.findAdminEvents(user, 1, 20, EMPTY_ADMIN), EventErrorCode.ACCESS_DENIED);
	}

	@Test
	@DisplayName("사용자 문맥이 없거나 비활성 상태이면 이벤트 조회 전에 거절한다")
	void rejectsInvalidUserContextBeforeRepositoryQuery() {
		// given / when / then
		assertError(() -> service.findUserEvents(null, 1, 20, EMPTY), EventErrorCode.USER_CONTEXT_REQUIRED);
		assertError(() -> service.findUserEvent(null, UUID.randomUUID()), EventErrorCode.USER_CONTEXT_REQUIRED);
		user = user(UserRole.ADMIN, UserStatus.INACTIVE, null);
		assertError(() -> service.findUserEvents(user, 1, 20, EMPTY), EventErrorCode.ACCESS_DENIED);
		assertError(() -> service.findAdminEvent(user, UUID.randomUUID()), EventErrorCode.ACCESS_DENIED);
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("멤버십 없는 관리자는 사용자·관리자 목록을 모두 조회할 수 있다")
	void adminDoesNotRequireMembership() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		when(events.findAll(eq(EMPTY), any(), eq(true))).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		when(events.findAll(eq(EMPTY), any(), eq(false))).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when / then
		assertThat(service.findUserEvents(user, 1, 20, EMPTY).getTotalElements()).isZero();
		assertThat(service.findAdminEvents(user, 1, 20, EMPTY_ADMIN).getTotalElements()).isZero();
	}

	@ParameterizedTest
	@CsvSource({"0,20", "1,0", "1,101", "-1,1"})
	@DisplayName("페이지와 크기 범위를 벗어나면 DB 조회 없이 400 오류를 낸다")
	void rejectsInvalidPagination(int page, int size) {
		// given / when / then
		assertError(() -> service.findUserEvents(user, page, size, EMPTY), EventErrorCode.INVALID_QUERY);
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		assertError(() -> service.findAdminEvents(user, page, size, EMPTY_ADMIN), EventErrorCode.INVALID_QUERY);
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("검색 종료 날짜가 시작 날짜보다 이르면 DB 조회 전에 거절한다")
	void rejectsReversedDates() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		LocalDate from = LocalDate.parse("2026-10-10");
		// when / then
		assertError(() -> service.findAdminEvents(user, 1, 20,
				new AdminEventQuery(EventStatus.OPEN, null, null, from, from.minusDays(1))), EventErrorCode.INVALID_QUERY);
		verifyNoInteractions(events);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0999-12-31", "+10000-10-01", "+999999999-10-01"})
	@DisplayName("KST 날짜의 UTC 경계가 지원 범위 밖이면 단일·양쪽 조건 모두 DB 조회 전에 거절한다")
	void rejectsUnsupportedSearchDates(String value) {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		LocalDate invalid = LocalDate.parse(value);
		LocalDate normal = LocalDate.parse("2099-10-10");
		List<AdminEventQuery> queries = List.of(
				new AdminEventQuery(null, null, null, invalid, null),
				new AdminEventQuery(null, null, null, null, invalid),
				new AdminEventQuery(null, null, null,
						invalid.isBefore(normal) ? invalid : normal, invalid.isBefore(normal) ? normal : invalid));
		// when / then
		for (AdminEventQuery query : queries) {
			assertError(() -> service.findAdminEvents(user, 1, 20, query), EventErrorCode.INVALID_QUERY);
		}
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("날짜 최솟값·최댓값과 종료일 다음 날 계산 초과도 DB 조회 없이 업무 오류로 처리한다")
	void rejectsExtremeDates() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		// when / then
		for (LocalDate value : List.of(LocalDate.MIN, LocalDate.MAX)) {
			assertError(() -> service.findAdminEvents(user, 1, 20,
					new AdminEventQuery(null, null, null, value, null)), EventErrorCode.INVALID_QUERY);
			assertError(() -> service.findAdminEvents(user, 1, 20,
					new AdminEventQuery(null, null, null, null, value)), EventErrorCode.INVALID_QUERY);
		}
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("검색 종료일의 다음 날 계산이 넘치면 업무 오류에 원인 예외를 보존한다")
	void preservesDateCalculationFailureCause() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		AdminEventQuery query = new AdminEventQuery(null, null, null, null, LocalDate.MAX);
		// when / then
		assertThatThrownBy(() -> service.findAdminEvents(user, 1, 20, query))
				.isInstanceOfSatisfying(EventException.class, exception -> {
					assertThat(exception.getErrorCode()).isEqualTo(EventErrorCode.INVALID_QUERY);
					assertThat(exception.getCause()).isInstanceOf(DateTimeException.class);
				});
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("지원 범위의 날짜와 단일 조건·조건 없는 검색을 서비스에서 UTC 조건으로 변환한다")
	void acceptsSupportedBoundariesAndOptionalBounds() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		LocalDate from = LocalDate.parse("1000-01-02");
		LocalDate to = LocalDate.parse("9999-12-31");
		when(events.findAll(any(), any(), eq(false))).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when / then
		for (AdminEventQuery query : List.of(EMPTY_ADMIN,
				new AdminEventQuery(null, null, null, from, null),
				new AdminEventQuery(null, null, null, null, to),
				new AdminEventQuery(null, null, null, from, to))) {
			assertThat(service.findAdminEvents(user, 1, 20, query).getTotalElements()).isZero();
		}
		ArgumentCaptor<EventQueryFilter> filters = ArgumentCaptor.forClass(EventQueryFilter.class);
		verify(events, times(4)).findAll(filters.capture(), eq(new PageQuery(1, 20)), eq(false));
		assertThat(filters.getAllValues().getFirst()).isEqualTo(EMPTY);
		assertThat(filters.getAllValues().get(1).getTo()).isNull();
		assertThat(filters.getAllValues().get(2).getFrom()).isNull();
		assertThat(filters.getValue().getTo()).isEqualTo(Instant.parse("9999-12-31T15:00:00Z"));
	}

	@Test
	@DisplayName("서비스는 KST 양끝 날짜 포함 정책과 상태·유형·제목을 UTC 검색 조건에 적용한다")
	void convertsInclusiveDatesAndPreservesFilters() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		AdminEventQuery query = new AdminEventQuery(EventStatus.OPEN, EventType.TICKET, "  이벤트  ",
				LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-23"));
		when(events.findAll(any(), any(), eq(false))).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when
		service.findAdminEvents(user, 1, 20, query);
		// then
		verify(events).findAll(new EventQueryFilter(EventStatus.OPEN, EventType.TICKET, null, "이벤트",
				Instant.parse("2026-10-09T15:00:00Z"), Instant.parse("2026-10-23T15:00:00Z")),
				new PageQuery(1, 20), false);
	}

	@ParameterizedTest
	@CsvSource({
			"2028-02-29,2028-02-28T15:00:00Z,2028-02-29T15:00:00Z",
			"2026-09-30,2026-09-29T15:00:00Z,2026-09-30T15:00:00Z",
			"2026-12-31,2026-12-30T15:00:00Z,2026-12-31T15:00:00Z"
	})
	@DisplayName("같은 날짜는 윤일·월말·연말에도 KST 하루 전체 검색으로 변환한다")
	void sameDateIncludesWholeDay(String date, String from, String to) {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		when(events.findAll(any(), any(), eq(false))).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when
		service.findAdminEvents(user, 1, 20, new AdminEventQuery(null, null, null,
				LocalDate.parse(date), LocalDate.parse(date)));
		// then
		verify(events).findAll(new EventQueryFilter(null, null, null, null, Instant.parse(from), Instant.parse(to)),
				new PageQuery(1, 20), false);
	}

	@Test
	@DisplayName("관리자 검색 입력 자체가 없으면 DB 조회 없이 업무 오류로 처리한다")
	void rejectsNullAdminQuery() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		// when / then
		assertError(() -> service.findAdminEvents(user, 1, 20, null), EventErrorCode.INVALID_QUERY);
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("하한 날짜도 UTC 시작 경계와 다음 날 종료 경계의 지원 여부를 각각 검사한다")
	void lowerBoundaryDependsOnConvertedSearchEdge() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		LocalDate date = LocalDate.of(1000, 1, 1);
		// when / then: 시작 경계는 UTC 0999년이지만 종료 경계는 UTC 1000년이다.
		assertError(() -> service.findAdminEvents(user, 1, 20,
				new AdminEventQuery(null, null, null, date, null)), EventErrorCode.INVALID_QUERY);
		verifyNoInteractions(events);
		when(events.findAll(any(), any(), eq(false))).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		assertThat(service.findAdminEvents(user, 1, 20,
				new AdminEventQuery(null, null, null, null, date)).getItems()).isEmpty();
		ArgumentCaptor<EventQueryFilter> filter = ArgumentCaptor.forClass(EventQueryFilter.class);
		verify(events).findAll(filter.capture(), eq(new PageQuery(1, 20)), eq(false));
		assertThat(filter.getValue().getFrom()).isNull();
		assertThat(EventTimeRange.contains(filter.getValue().getTo())).isTrue();
	}

	@Test
	@DisplayName("상세 조회에서 없는 이벤트는 404 업무 오류로 변환한다")
	void missingEventIsNotFound() {
		// given
		UUID id = UUID.randomUUID();
		when(events.findById(id)).thenReturn(Optional.empty());
		// when / then
		assertError(() -> service.findUserEvent(user, id), EventErrorCode.EVENT_NOT_FOUND);
	}

	@Test
	@DisplayName("사용자 목록은 현재 페이지의 정상 참여 여부를 한 번에 연결하고 페이지 정보를 보존한다")
	void attachesParticipationInOneBatch() {
		// given
		UUID entered = UUID.randomUUID();
		UUID notEntered = UUID.randomUUID();
		when(events.findAll(eq(EMPTY), any(), eq(true)))
				.thenReturn(new PageResult<>(List.of(view(entered), view(notEntered)), 2, 2, 7));
		when(events.findEnteredEventIds(ACTOR_ID, List.of(entered, notEntered))).thenReturn(Set.of(entered));
		// when
		PageResult<EventView> result = service.findUserEvents(user, 2, 2, EMPTY);
		// then
		assertThat(result.getItems()).extracting(EventView::isMyEntered).containsExactly(true, false);
		assertThat(result.getPage()).isEqualTo(2);
		assertThat(result.getSize()).isEqualTo(2);
		assertThat(result.getTotalElements()).isEqualTo(7);
		verify(events).findEnteredEventIds(ACTOR_ID, List.of(entered, notEntered));
	}

	@Test
	@DisplayName("관리자는 사용자 목록에서도 응모 여부를 조회하지 않고 false를 반환한다")
	void adminParticipationIsAlwaysFalse() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		when(events.findAll(eq(EMPTY), any(), eq(true)))
				.thenReturn(new PageResult<>(List.of(view(UUID.randomUUID()).withMyEntered(true)), 1, 20, 1));
		// when / then
		assertThat(service.findUserEvents(user, 1, 20, EMPTY).getItems().getFirst().isMyEntered()).isFalse();
		verify(events, never()).findEnteredEventIds(any(), any());
	}

	@Test
	@DisplayName("등록일 조건도 KST 양끝 포함 UTC 범위로 변환하고 가중치 false와 정렬을 보존한다")
	void convertsCreatedDatesAndKeepsNewConditions() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		LocalDate day = LocalDate.parse("2026-10-17");
		when(events.findAll(any(), any(), eq(false))).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when
		service.findAdminEvents(user, 1, 20, new AdminEventQuery(null, null, null,
				null, null, false, day, day, "title,asc"));
		// then
		ArgumentCaptor<EventQueryFilter> filters = ArgumentCaptor.forClass(EventQueryFilter.class);
		verify(events).findAll(filters.capture(), any(), eq(false));
		assertThat(filters.getValue().getWeightingEnabled()).isFalse();
		assertThat(filters.getValue().getSort()).isEqualTo(EventSort.TITLE_ASC);
		assertThat(filters.getValue().getCreatedFrom()).isEqualTo(Instant.parse("2026-10-16T15:00:00Z"));
		assertThat(filters.getValue().getCreatedTo()).isEqualTo(Instant.parse("2026-10-17T15:00:00Z"));
	}

	@Test
	@DisplayName("허용되지 않은 정렬과 역전·지원 범위 밖 등록일은 저장소 호출 전에 거절한다")
	void rejectsInvalidNewConditions() {
		// given
		user = user(UserRole.ADMIN, UserStatus.ACTIVE, null);
		LocalDate day = LocalDate.parse("2026-10-17");
		// when / then
		for (String sort : List.of("", "id,asc", "title,invalid", "title,asc;delete from events")) {
			assertError(() -> service.findAdminEvents(user, 1, 20,
					new AdminEventQuery(null, null, null, null, null, null, null, null, sort)), EventErrorCode.INVALID_QUERY);
		}
		for (AdminEventQuery query : List.of(
				new AdminEventQuery(null, null, null, null, null, null, day, day.minusDays(1), null),
				new AdminEventQuery(null, null, null, null, null, null, LocalDate.of(999, 1, 1), null, null),
				new AdminEventQuery(null, null, null, null, null, null, null, LocalDate.MAX, null))) {
			assertError(() -> service.findAdminEvents(user, 1, 20, query), EventErrorCode.INVALID_QUERY);
		}
		verifyNoInteractions(events);
	}

	private EventView view(UUID id) {
		RegisteredEvent event = new RegisteredEvent(id, "이벤트", "설명", null, null, EventType.NO_TICKET,
				false, null, MembershipRule.excellent, Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
				EventStatus.OPEN, Instant.EPOCH, Instant.EPOCH, List.of());
		return new EventView(event, EventStatus.OPEN, null);
	}

	private User user(UserRole role, UserStatus status, Membership membership) {
		return new User(ACTOR_ID, "조회 사용자", role, status, membership,
				null, null, null, null, Instant.EPOCH, Instant.EPOCH);
	}

	private void assertError(Runnable action, EventErrorCode error) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(EventException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
	}
}
