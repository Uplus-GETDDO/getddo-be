package com.getddo.db.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.stats.domain.DailyStats;
import com.getddo.core.stats.domain.DailyStats.Day;
import com.getddo.core.stats.service.DailyStatsService;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.db.mission.MissionIntegrationTestSupport;
import com.getddo.db.ticket.TicketGrantSeeds;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL에서 관리자 일별 집계(R1)의 KST 날짜 경계, 사용자 중복 제거, 지급 근거 구분을 검증한다. 다른 테스트가 남긴 행과 겹치지
 * 않도록 멀리 떨어진 2031년 날짜를 쓴다.
 */
class DailyStatsIntegrationTest extends MissionIntegrationTestSupport {

	private static final LocalDate DAY_1 = LocalDate.of(2031, 3, 10);
	private static final LocalDate DAY_2 = LocalDate.of(2031, 3, 11);
	private static final LocalDate DAY_3 = LocalDate.of(2031, 3, 12);
	/** KST 2031-03-10 23:59:59. */
	private static final Instant DAY_1_LAST_SECOND = Instant.parse("2031-03-10T14:59:59Z");
	/** KST 2031-03-11 00:00:00. */
	private static final Instant DAY_2_FIRST_SECOND = Instant.parse("2031-03-10T15:00:00Z");
	/** KST 2031-03-11 12:00:00. */
	private static final Instant DAY_2_NOON = Instant.parse("2031-03-11T03:00:00Z");
	/** KST 2031-03-12 00:00:00. */
	private static final Instant DAY_3_FIRST_SECOND = Instant.parse("2031-03-11T15:00:00Z");
	/** KST 2031-03-13 00:00:00. 조회 기간 밖이다. */
	private static final Instant AFTER_PERIOD = Instant.parse("2031-03-12T15:00:00Z");

	@Autowired
	private DailyStatsService statsService;

	private final List<UUID> abuseUsers = new ArrayList<>();

	@AfterEach
	void cleanUpAbuseCases() {
		for (UUID user : abuseUsers) {
			jdbc.update("delete from abuse_cases where user_id = ?", bytes(user));
		}
		abuseUsers.clear();
	}

	private User admin() {
		return new User(adminId, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null, null, null);
	}

	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private UUID entryAt(UUID user, Instant at) {
		UUID entry = seeds.eventEntry(user);
		jdbc.update("update event_entries set created_at = ? where id = ?", utc(at), bytes(entry));
		return entry;
	}

	/** 거절된 응모는 접수 건과 같은 테이블에 저장된다. 응모자·차감이 없다. */
	private void rejectedEntryAt(UUID user, Instant at) {
		UUID event = seeds.event("TICKET", false, null, "excellent", Instant.parse("2031-01-01T00:00:00Z"),
				Instant.parse("2031-12-31T00:00:00Z"), "OPEN", null);
		jdbc.update("""
				insert into event_entries (id, event_id, participant_id, user_id, status, rejection_code,
				  rejection_reason, requested_ticket_count, deducted_ticket_count, created_at)
				values (?, ?, null, ?, 'REJECTED', 'ENTRY-005', '집계 테스트 거절', 1, 0, ?)
				""", bytes(TicketGrantSeeds.uuidV7()), bytes(event), bytes(user), utc(at));
	}

	private void missionSubmissionAt(UUID user, Instant at) {
		TicketGrantSeeds.MissionParents parents = seeds.missionParents(user);
		jdbc.update("update mission_submissions set created_at = ? where id = ?", utc(at), bytes(parents.submissionId()));
	}

	private void attendanceOn(UUID user, LocalDate date) {
		TicketGrantSeeds.AttendanceParents parents = seeds.attendanceParents(user);
		jdbc.update("update attendances set attendance_date = ? where id = ?", date, bytes(parents.attendanceId()));
	}

	private void gamePlayAt(UUID user, Instant at) {
		TicketGrantSeeds.GameParents parents = seeds.gameParents(user);
		jdbc.update("update game_plays set created_at = ? where id = ?", utc(at), bytes(parents.playId()));
	}

	/** 미션 근거 응모권 한 장의 최초 지급 시각을 옮긴다. */
	private UUID missionTicketAt(UUID user, Instant at) {
		UUID ticket = seeds.ticket(user, "BRONZE", Instant.parse("2032-01-01T00:00:00Z"));
		jdbc.update("update tickets set created_at = ?, updated_at = ? where id = ?", utc(at), utc(at), bytes(ticket));
		return ticket;
	}

	private void attendanceTicketAt(UUID user, Instant at) {
		TicketGrantSeeds.AttendanceParents parents = seeds.attendanceParents(user);
		UUID claim = seeds.attendanceClaim(user, parents, 1);
		jdbc.update("""
				insert into tickets (id, user_id, attendance_reward_claim_id, grade, status, expires_at, version,
				  created_at, updated_at)
				values (?, ?, ?, 'BRONZE', 'AVAILABLE', ?, 1, ?, ?)
				""", bytes(UUID.randomUUID()), bytes(user), bytes(claim), utc(Instant.parse("2032-01-01T00:00:00Z")),
				utc(at), utc(at));
	}

	private void gameTicketAt(UUID user, Instant at) {
		TicketGrantSeeds.GameParents parents = seeds.gameParents(user);
		UUID claim = seeds.gameClaim(user, parents, 1);
		jdbc.update("""
				insert into tickets (id, user_id, game_reward_claim_id, grade, status, expires_at, version,
				  created_at, updated_at)
				values (?, ?, ?, 'SILVER', 'AVAILABLE', ?, 1, ?, ?)
				""", bytes(UUID.randomUUID()), bytes(user), bytes(claim), utc(Instant.parse("2032-01-01T00:00:00Z")),
				utc(at), utc(at));
	}

	private void useHistoryAt(UUID ticket, UUID user, Instant at) {
		UUID entry = seeds.eventEntry(user);
		jdbc.update("""
				insert into ticket_histories (id, ticket_id, event_entry_id, operation_type, ticket_version, status,
				  expires_at, reason, created_at)
				values (?, ?, ?, 'USE', 2, 'SPENT', ?, '집계 테스트', ?)
				""", bytes(UUID.randomUUID()), bytes(ticket), bytes(entry), utc(Instant.parse("2032-01-01T00:00:00Z")),
				utc(at));
	}

	private void abuseCaseAt(UUID user, Instant at) {
		abuseUsers.add(user);
		jdbc.update("""
				insert into abuse_cases (id, user_id, source_type, detection_type, detection_reason,
				  evidence, occurred_at, detected_at, blocked_at, blocked_until)
				values (?, ?, 'ENTRY', 'REPEATED_RATE_LIMIT_EXCEEDED',
				  '응모 요청 한도 초과가 반복되어 일시 차단되었습니다.',
				  JSON_OBJECT('ruleVersion', 'v1', 'triggerScope', 'USER',
				    'windowStartedAt', ?, 'windowEndsAt', ?, 'requestLimit', 20,
				    'rejectedCount', 20, 'rejectionThreshold', 20), ?, ?, ?, ?)
				""", bytes(UUID.randomUUID()), bytes(user), at.minusSeconds(30).toString(), at.plusSeconds(30).toString(),
				utc(at), utc(at), utc(at), utc(at.plusSeconds(600)));
	}

	@Test
	@DisplayName("KST 자정 경계로 날짜를 나누고 사용자·활동을 중복 없이 센다")
	void aggregatesByKstDayWithBoundaries() {
		// given
		UUID a = seeds.user();
		UUID b = seeds.user();
		UUID c = seeds.user();
		UUID d = seeds.user();
		UUID e = seeds.user();
		// 응모: 1일 마지막 초에 a, 2일 첫 초에 a 두 건과 b 한 건, 기간 밖(13일)은 세지 않는다
		entryAt(a, DAY_1_LAST_SECOND);
		entryAt(a, DAY_2_FIRST_SECOND);
		entryAt(a, DAY_2_NOON);
		entryAt(b, DAY_2_FIRST_SECOND);
		entryAt(b, AFTER_PERIOD);
		// 거절된 응모는 응모자·응모 건수·참여 사용자에 세지 않는다(e는 2일에 다른 활동이 없다)
		rejectedEntryAt(e, DAY_2_NOON);
		// 참여: 1일 e(미션 제출), 2일 c(출석)·d(미션 제출)·a(게임, 이미 응모로 센 사용자)
		missionSubmissionAt(e, DAY_1_LAST_SECOND);
		attendanceOn(c, DAY_2);
		missionSubmissionAt(d, DAY_2_NOON);
		gamePlayAt(a, DAY_2_NOON);
		// 관리자 계정의 활동은 참여 사용자에 세지 않는다
		UUID staff = seeds.user();
		jdbc.update("update users set role = 'ADMIN' where id = ?", bytes(staff));
		missionSubmissionAt(staff, DAY_2_NOON);
		attendanceOn(staff, DAY_1);
		// 지급: 2일 미션 2장·출석 1장·게임 1장, 3일 첫 초 미션 1장
		UUID t1 = missionTicketAt(a, DAY_2_FIRST_SECOND);
		UUID t2 = missionTicketAt(b, DAY_2_NOON);
		attendanceTicketAt(c, DAY_2_NOON);
		gameTicketAt(d, DAY_2_NOON);
		UUID t3 = missionTicketAt(e, DAY_3_FIRST_SECOND);
		// 사용: 2일 두 장, 3일 한 장
		useHistoryAt(t1, a, DAY_2_NOON);
		useHistoryAt(t2, b, DAY_2_NOON);
		useHistoryAt(t3, e, DAY_3_FIRST_SECOND);
		// 어뷰징: 1일 한 건, 2일 두 건
		abuseCaseAt(a, DAY_1_LAST_SECOND);
		abuseCaseAt(a, DAY_2_FIRST_SECOND);
		abuseCaseAt(b, DAY_2_NOON);
		// when
		DailyStats stats = statsService.daily(admin(), DAY_1, DAY_3);
		// then
		assertThat(stats.days()).extracting(Day::date).containsExactly(DAY_1, DAY_2, DAY_3);
		Day first = stats.days().get(0);
		Day second = stats.days().get(1);
		Day third = stats.days().get(2);
		assertThat(first.entrantCount()).isEqualTo(1);
		assertThat(first.entryCount()).isEqualTo(1);
		assertThat(second.entrantCount()).isEqualTo(2);
		assertThat(second.entryCount()).isEqualTo(3);
		assertThat(third.entrantCount()).isZero();
		assertThat(third.entryCount()).isZero();
		assertThat(first.activeUserCount()).isEqualTo(2);
		assertThat(second.activeUserCount()).isEqualTo(4);
		assertThat(third.activeUserCount()).isZero();
		assertThat(first.grantedTickets()).containsEntry(GrantSourceType.MISSION, 0L);
		assertThat(second.grantedTickets()).containsEntry(GrantSourceType.MISSION, 2L)
				.containsEntry(GrantSourceType.ATTENDANCE, 1L).containsEntry(GrantSourceType.GAME, 1L);
		assertThat(third.grantedTickets()).containsEntry(GrantSourceType.MISSION, 1L)
				.containsEntry(GrantSourceType.ATTENDANCE, 0L).containsEntry(GrantSourceType.GAME, 0L);
		assertThat(first.usedTickets()).isZero();
		assertThat(second.usedTickets()).isEqualTo(2);
		assertThat(third.usedTickets()).isEqualTo(1);
		assertThat(first.blockedCount()).isEqualTo(1);
		assertThat(second.blockedCount()).isEqualTo(2);
		assertThat(third.blockedCount()).isZero();
	}

	@Test
	@DisplayName("활성 일반 사용자 수는 USER이면서 ACTIVE인 사용자만 센다")
	void countsOnlyActiveRegularUsers() {
		// given
		long before = count("select count(*) from users where role = 'USER' and status = 'ACTIVE'");
		UUID inactive = seeds.user();
		jdbc.update("update users set status = 'INACTIVE' where id = ?", bytes(inactive));
		seeds.user();
		// when
		DailyStats stats = statsService.daily(admin(), DAY_1, DAY_1);
		// then
		assertThat(stats.totalUserCount()).isEqualTo(before + 1);
		assertThat(stats.totalUserCount())
				.isEqualTo(count("select count(*) from users where role = 'USER' and status = 'ACTIVE'"));
	}
}
