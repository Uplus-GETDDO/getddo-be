package com.getddo.db.entry;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.AdminEntryFilter;
import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.entry.domain.EntrySummary;
import com.getddo.core.entry.service.AdminEntryQueryService;
import com.getddo.core.ticket.domain.TicketGrade;
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
}
