package com.getddo.core.entry.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.AdminEntryFilter;
import com.getddo.core.entry.domain.Entry;
import com.getddo.core.entry.domain.EntryEvent;
import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.entry.domain.EntrySummary;
import com.getddo.core.entry.exception.EntryErrorCode;
import com.getddo.core.entry.exception.EntryException;
import com.getddo.core.entry.repository.EntryEventReader;
import com.getddo.core.entry.repository.EntryParticipantRepository;
import com.getddo.core.entry.repository.EntryRepository;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.user.domain.NamedValue;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserNameRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminEntryQueryServiceTest {

	private static final UUID EVENT_ID = UUID.randomUUID();
	private static final PageQuery FIRST_PAGE = new PageQuery(1, 20);
	private static final User ADMIN = user(UserRole.ADMIN, UserStatus.ACTIVE);

	@Mock
	private EntryRepository entryRepository;
	@Mock
	private EntryParticipantRepository participantRepository;
	@Mock
	private EntryEventReader eventReader;
	@Mock
	private UserNameRepository userNames;

	private static User user(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "사용자", role, status, null, null, null, null, null, null, null);
	}

	private AdminEntryQueryService service() {
		return new AdminEntryQueryService(entryRepository, participantRepository, eventReader, userNames);
	}

	private static AdminEntryFilter anyFilter() {
		return new AdminEntryFilter(null, null, null, null, null);
	}

	@Test
	@DisplayName("관리자의 응모 내역 조회는 조건을 저장소에 그대로 넘기고 응모한 사용자의 현재 이름을 함께 돌려준다")
	void findsEntriesForAdmin() {
		// given
		UUID userId = UUID.randomUUID();
		UUID strangerId = UUID.randomUUID();
		AdminEntryFilter filter = new AdminEntryFilter(userId, EVENT_ID, null, Instant.EPOCH,
				Instant.EPOCH.plusSeconds(1));
		EntrySummary mine = summaryOf(userId);
		EntrySummary unknown = summaryOf(strangerId);
		when(entryRepository.findAll(filter, FIRST_PAGE))
				.thenReturn(new PageResult<>(List.of(mine, unknown), 1, 20, 2));
		when(userNames.findNamesByIds(Set.of(userId, strangerId))).thenReturn(Map.of(userId, "홍길동"));
		// when
		PageResult<NamedValue<EntrySummary>> result = service().findEntries(ADMIN, filter, FIRST_PAGE);
		// then
		assertThat(result.getTotalElements()).isEqualTo(2);
		assertThat(result.getItems()).extracting(NamedValue::getValue).containsExactly(mine, unknown);
		assertThat(result.getItems()).extracting(NamedValue::getName).containsExactly("홍길동", null);
	}

	private static EntrySummary summaryOf(UUID userId) {
		EntryParticipant participant = new EntryParticipant(UUID.randomUUID(), EVENT_ID, userId, 1,
				Instant.EPOCH);
		return new EntrySummary(Entry.accepted(UUID.randomUUID(), participant, Map.of(TicketGrade.BRONZE, 1L),
				Instant.EPOCH), "이벤트");
	}

	@Test
	@DisplayName("관리자가 아니거나 활성이 아니면 ENTRY-010으로 거절하고 저장소를 읽지 않는다")
	void rejectsNonAdmins() {
		for (User caller : new User[] {null, user(UserRole.USER, UserStatus.ACTIVE),
				user(UserRole.ADMIN, UserStatus.INACTIVE)}) {
			assertThatThrownBy(() -> service().findEntries(caller, anyFilter(), FIRST_PAGE))
					.isInstanceOfSatisfying(EntryException.class,
							e -> assertThat(e.getErrorCode()).isEqualTo(EntryErrorCode.ADMIN_REQUIRED));
			assertThatThrownBy(() -> service().findParticipants(caller, EVENT_ID, FIRST_PAGE))
					.isInstanceOfSatisfying(EntryException.class,
							e -> assertThat(e.getErrorCode()).isEqualTo(EntryErrorCode.ADMIN_REQUIRED));
		}
		verifyNoInteractions(entryRepository, participantRepository, eventReader);
	}

	@Test
	@DisplayName("기간의 시작이 끝보다 늦거나 같으면 조건을 만들 수 없다")
	void filterRejectsInvalidPeriod() {
		assertThatThrownBy(() -> new AdminEntryFilter(null, null, null, Instant.EPOCH, Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("이벤트 응모자 조회는 이벤트가 있으면 응모자 페이지를 응모자 이름과 함께 돌려준다")
	void findsParticipants() {
		// given
		UUID userId = UUID.randomUUID();
		EntryParticipant participant = new EntryParticipant(UUID.randomUUID(), EVENT_ID, userId, 2, Instant.EPOCH);
		when(eventReader.find(EVENT_ID)).thenReturn(Optional.of(mock(EntryEvent.class)));
		when(participantRepository.findByEvent(EVENT_ID, FIRST_PAGE))
				.thenReturn(new PageResult<>(List.of(participant), 1, 20, 1));
		when(userNames.findNamesByIds(Set.of(userId))).thenReturn(Map.of(userId, "홍길동"));
		// when
		PageResult<NamedValue<EntryParticipant>> result = service().findParticipants(ADMIN, EVENT_ID, FIRST_PAGE);
		// then
		assertThat(result.getItems()).hasSize(1);
		assertThat(result.getItems().get(0).getValue()).isSameAs(participant);
		assertThat(result.getItems().get(0).getName()).isEqualTo("홍길동");
	}

	@Test
	@DisplayName("없는 이벤트의 응모자 조회는 ENTRY-004이고 저장소를 읽지 않는다")
	void missingEventIsNotFound() {
		// given
		when(eventReader.find(EVENT_ID)).thenReturn(Optional.empty());
		// when
		// then
		assertThatThrownBy(() -> service().findParticipants(ADMIN, EVENT_ID, FIRST_PAGE))
				.isInstanceOfSatisfying(EntryException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(EntryErrorCode.EVENT_NOT_FOUND));
		verify(eventReader).find(EVENT_ID);
		verifyNoInteractions(participantRepository);
	}
}
