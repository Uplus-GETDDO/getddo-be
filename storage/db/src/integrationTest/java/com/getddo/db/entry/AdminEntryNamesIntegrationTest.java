package com.getddo.db.entry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.AdminEntryFilter;
import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.entry.domain.EntryStatus;
import com.getddo.core.entry.domain.EntrySummary;
import com.getddo.core.entry.service.AdminEntryQueryService;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.db.ticket.TicketGrantSeeds;
import com.getddo.core.user.domain.NamedValue;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 응모 내역(AO06)과 이벤트 응모자(AO07)가 사용자의 현재 이름을 함께 돌려주는지 실제 MySQL로 검증한다. */
class AdminEntryNamesIntegrationTest extends EntryIntegrationTestSupport {

	private static final PageQuery PAGE = new PageQuery(1, 20);

	@Autowired
	private AdminEntryQueryService adminService;

	private User admin() {
		return new User(UUID.randomUUID(), "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null,
				null, null);
	}

	private String nameOf(UUID user) {
		return jdbc.queryForObject("select name from users where id = ?", String.class, bytes(user));
	}

	@Test
	@DisplayName("응모 내역과 응모자 목록이 각 사용자의 현재 이름을 담고 이름을 바꾸면 다음 조회에 바뀐 이름이 나간다")
	void listsEntriesAndParticipantsWithCurrentUserNames() {
		// given
		UUID other = seeds.user();
		UUID event = weightedEvent(null);
		giveTickets(userId, TicketGrade.BRONZE, 1);
		giveTickets(other, TicketGrade.BRONZE, 1);
		entryService.enter(command(userId, event, UUID.randomUUID(), tickets(TicketGrade.BRONZE, 1L)));
		entryService.enter(command(other, event, UUID.randomUUID(), tickets(TicketGrade.BRONZE, 1L)));

		// when
		PageResult<NamedValue<EntrySummary>> entries = adminService.findEntries(admin(),
				new AdminEntryFilter(null, event, null, null, null), PAGE);
		PageResult<NamedValue<EntryParticipant>> participants = adminService.findParticipants(admin(), event, PAGE);

		// then: 같은 사용자의 ID와 이름이 짝을 이루고, 건수와 집계는 그대로다
		assertThat(entries.getTotalElements()).isEqualTo(2L);
		assertThat(entries.getItems()).extracting(named -> named.getValue().getEntry().getUserId())
				.containsExactlyInAnyOrder(userId, other);
		assertThat(entries.getItems()).allSatisfy(named -> assertThat(named.getName())
				.isEqualTo(nameOf(named.getValue().getEntry().getUserId())));
		assertThat(participants.getTotalElements()).isEqualTo(2L);
		assertThat(participants.getItems()).allSatisfy(named -> assertThat(named.getName())
				.isEqualTo(nameOf(named.getValue().getUserId())));

		// when: 사용자의 이름이 바뀐다
		jdbc.update("update users set name = ? where id = ?", "바뀐 이름", bytes(userId));
		List<NamedValue<EntrySummary>> renamed = adminService.findEntries(admin(),
				new AdminEntryFilter(userId, event, null, null, null), PAGE).getItems();

		// then: 응답의 ID는 같고 이름만 현재 이름이다
		assertThat(renamed).hasSize(1);
		assertThat(renamed.get(0).getValue().getEntry().getUserId()).isEqualTo(userId);
		assertThat(renamed.get(0).getName()).isEqualTo("바뀐 이름");
	}

	@Test
	@DisplayName("관리자 응모·참여자 최신순 조회 인덱스가 Flyway로 생성된다")
	void migrationCreatesOrderedListingIndexes() {
		// given / when / then: 이벤트 필터 뒤의 정렬을 지원하는 컬럼 순서까지 검사한다.
		assertIndexColumns("event_entries", "idx_event_entries_created", "created_at", "id");
		assertIndexColumns("event_entries", "idx_event_entries_event_created", "event_id", "created_at", "id");
		assertIndexColumns("event_participants", "idx_event_participants_event_created", "event_id", "created_at", "id");
	}

	@Test
	@DisplayName("같은 시각의 관리자 응모·참여자 목록도 ID 내림차순으로 페이지가 이어진다")
	void pagesEntriesAndParticipantsWithoutDuplicates() {
		// given
		UUID event = weightedEvent(null);
		for (int i = 0; i < 3; i++) {
			UUID user = seeds.user();
			giveTickets(user, TicketGrade.BRONZE, 1);
			entryService.enter(command(user, event, UUID.randomUUID(), tickets(TicketGrade.BRONZE, 1L)));
		}
		UUID otherEvent = weightedEvent(null);
		giveTickets(userId, TicketGrade.BRONZE, 1);
		entryService.enter(command(userId, otherEvent, UUID.randomUUID(), tickets(TicketGrade.BRONZE, 1L)));
		List<UUID> expectedEntries = jdbc.query(
				"select id from event_entries where event_id = ? order by created_at desc, id desc",
				(row, index) -> TicketGrantSeeds.uuid(row.getBytes("id")), bytes(event));
		List<UUID> expectedParticipants = jdbc.query(
				"select id from event_participants where event_id = ? order by created_at desc, id desc",
				(row, index) -> TicketGrantSeeds.uuid(row.getBytes("id")), bytes(event));
		List<UUID> expectedAll = jdbc.query(
				"select e.id from event_entries e join events ev on ev.id = e.event_id order by e.created_at desc, e.id desc",
				(row, index) -> TicketGrantSeeds.uuid(row.getBytes("id")));
		List<UUID> entries = new ArrayList<>();
		List<UUID> participants = new ArrayList<>();
		AdminEntryFilter filter = new AdminEntryFilter(null, event, EntryStatus.ACCEPTED, NOW, NOW.plusSeconds(1));

		// when
		for (int page = 1; page <= 3; page++) {
			PageResult<NamedValue<EntrySummary>> entryPage = adminService.findEntries(admin(), filter,
					new PageQuery(page, 2));
			PageResult<NamedValue<EntryParticipant>> participantPage = adminService.findParticipants(admin(), event,
					new PageQuery(page, 2));
			assertThat(entryPage.getTotalElements()).isEqualTo(3);
			assertThat(participantPage.getTotalElements()).isEqualTo(3);
			int expectedSize = Math.max(0, 3 - (page - 1) * 2);
			assertThat(entryPage.getItems()).hasSize(Math.min(2, expectedSize));
			assertThat(participantPage.getItems()).hasSize(Math.min(2, expectedSize));
			entryPage.getItems().forEach(named -> entries.add(named.getValue().getEntry().getId()));
			participantPage.getItems().forEach(named -> participants.add(named.getValue().getId()));
		}

		// then
		PageResult<NamedValue<EntrySummary>> all = adminService.findEntries(admin(),
				new AdminEntryFilter(null, null, null, null, null), new PageQuery(1, 2));
		assertThat(all.getTotalElements()).isEqualTo(expectedAll.size());
		assertThat(all.getItems()).extracting(named -> named.getValue().getEntry().getId())
				.containsExactlyElementsOf(expectedAll.subList(0, 2));
		assertThat(entries).containsExactlyElementsOf(expectedEntries).doesNotHaveDuplicates();
		assertThat(participants).containsExactlyElementsOf(expectedParticipants).doesNotHaveDuplicates();
		assertThat(adminService.findEntries(admin(),
				new AdminEntryFilter(null, event, null, NOW.minusSeconds(1), NOW), PAGE).getItems()).isEmpty();
	}

	/** 인덱스 이름뿐 아니라 탐색·정렬 컬럼의 순서를 확인한다. */
	private void assertIndexColumns(String table, String index, String... expected) {
		List<String> columns = jdbc.queryForList("""
				select COLUMN_NAME from information_schema.statistics
				where TABLE_SCHEMA = database() and TABLE_NAME = ? and INDEX_NAME = ?
				order by SEQ_IN_INDEX
				""", String.class, table, index);
		assertThat(columns).containsExactly(expected);
	}

}
