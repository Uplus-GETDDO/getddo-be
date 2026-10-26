package com.getddo.db.ticket;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.domain.TicketGrantFilter;
import com.getddo.core.ticket.domain.TicketGrantView;
import com.getddo.core.ticket.repository.AdminTicketQueryRepository;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 응모권 지급 이력 조회 SQL을 Flyway로 만든 실제 MySQL 스키마에서 검증한다. */
class AdminTicketQueryRepositoryIntegrationTest extends TicketIntegrationTestSupport {

	private static final Instant BASE = Instant.parse("2026-09-15T03:00:00Z");
	private static final PageQuery FIRST_PAGE = new PageQuery(1, 20);

	@Autowired
	private AdminTicketQueryRepository adminRepository;

	private PageResult<TicketGrantView> grants(TicketGrantFilter filter, PageQuery page) {
		return transaction.execute(status -> adminRepository.findGrants(filter, page));
	}

	private PageResult<TicketGrantView> grantsOf(UUID user) {
		return grants(filter(user, null, null, null, null, null, null), FIRST_PAGE);
	}

	private static TicketGrantFilter filter(UUID user, String keyword, UUID missionId, GrantSourceType type,
			TicketGrade grade, Instant from, Instant to) {
		return TicketGrantFilter.of(user, keyword, missionId, type, grade, from, to);
	}

	private void renameUser(UUID user, String name) {
		jdbc.update("update users set name = ? where id = ?", name, bytes(user));
	}

	/** 출석·미션·게임 지급을 서로 다른 시각에 하나씩 만든다. 반환값은 근거 원본 ID 묶음이다. */
	private Sources grantAllSources(UUID user) {
		TicketGrantSeeds.AttendanceParents attendance = seeds.attendanceParents(user);
		TicketGrantSeeds.MissionParents mission = seeds.missionParents(user);
		TicketGrantSeeds.GameParents game = seeds.gameParents(user);
		clock.set(BASE);
		transaction.executeWithoutResult(status -> grantService.grant(command(user, GrantSourceType.ATTENDANCE,
				seeds.attendanceClaim(user, attendance, 2), 2)));
		clock.set(BASE.plusSeconds(60));
		transaction.executeWithoutResult(status -> grantService.grant(command(user, GrantSourceType.MISSION,
				seeds.missionClaim(user, mission, 1), 1)));
		clock.set(BASE.plusSeconds(120));
		transaction.executeWithoutResult(status -> grantService.grant(command(user, GrantSourceType.GAME,
				seeds.gameClaim(user, game, 1), 1)));
		return new Sources(attendance.attendanceId(), mission.submissionId(), mission.missionId(), game.playId());
	}

	private record Sources(UUID attendanceId, UUID submissionId, UUID missionId, UUID playId) {
	}

	@Test
	@DisplayName("지급 이력을 최신순으로 한 장당 한 행씩 주고 근거 종류·원본 ID·미션 ID·사용자 이름을 채운다")
	void listsGrantsNewestFirstWithSources() {
		// given
		renameUser(userId, "김응모");
		Sources sources = grantAllSources(userId);
		// when
		PageResult<TicketGrantView> page = grantsOf(userId);
		// then
		assertThat(page.getTotalElements()).isEqualTo(4);
		assertThat(page.getItems()).extracting(TicketGrantView::getSourceType)
				.containsExactly(GrantSourceType.GAME, GrantSourceType.MISSION, GrantSourceType.ATTENDANCE,
						GrantSourceType.ATTENDANCE);
		TicketGrantView game = page.getItems().get(0);
		assertThat(game.getSourceId()).isEqualTo(sources.playId());
		assertThat(game.getMissionId()).isNull();
		assertThat(game.getUserName()).isEqualTo("김응모");
		assertThat(game.getUserId()).isEqualTo(userId);
		assertThat(game.getGrantedAt()).isEqualTo(BASE.plusSeconds(120));
		TicketGrantView mission = page.getItems().get(1);
		assertThat(mission.getSourceId()).isEqualTo(sources.submissionId());
		assertThat(mission.getMissionId()).isEqualTo(sources.missionId());
		assertThat(page.getItems().get(2).getSourceId()).isEqualTo(sources.attendanceId());
		assertThat(page.getItems().get(2).getGrade()).isEqualTo(TicketGrade.BRONZE);
		assertThat(page.getItems().get(2).getMissionId()).isNull();
	}

	@Test
	@DisplayName("sourceType·grade·missionId 조건으로 좁히고 missionId를 쓰면 미션 지급만 나온다")
	void filtersBySourceGradeAndMission() {
		// given
		Sources sources = grantAllSources(userId);
		// when
		PageResult<TicketGrantView> attendance = grants(
				filter(userId, null, null, GrantSourceType.ATTENDANCE, null, null, null), FIRST_PAGE);
		PageResult<TicketGrantView> bronze = grants(
				filter(userId, null, null, null, TicketGrade.BRONZE, null, null), FIRST_PAGE);
		PageResult<TicketGrantView> byMission = grants(
				filter(userId, null, sources.missionId(), null, null, null, null), FIRST_PAGE);
		PageResult<TicketGrantView> missionWithGameType = grants(
				filter(userId, null, sources.missionId(), GrantSourceType.GAME, null, null, null), FIRST_PAGE);
		PageResult<TicketGrantView> otherMission = grants(
				filter(userId, null, UUID.randomUUID(), null, null, null, null), FIRST_PAGE);
		// then
		assertThat(attendance.getTotalElements()).isEqualTo(2);
		assertThat(attendance.getItems()).allMatch(view -> view.getSourceType() == GrantSourceType.ATTENDANCE);
		assertThat(bronze.getItems()).allMatch(view -> view.getGrade() == TicketGrade.BRONZE);
		assertThat(bronze.getTotalElements()).isGreaterThanOrEqualTo(2);
		assertThat(byMission.getItems()).singleElement()
				.satisfies(view -> assertThat(view.getSourceType()).isEqualTo(GrantSourceType.MISSION));
		assertThat(missionWithGameType.getTotalElements()).isZero();
		assertThat(otherMission.getTotalElements()).isZero();
	}

	@Test
	@DisplayName("keyword는 이름 부분 일치를 대소문자 없이 찾고 %·_를 문자 그대로 검색한다")
	void keywordMatchesNamePartiallyAndLiterally() {
		// given
		renameUser(userId, "Hong Gil-Dong");
		UUID other = seeds.user();
		renameUser(other, "Hongxgil_dong");
		grantNewMissionClaim(userId, 1);
		grantNewMissionClaim(other, 1);
		// when
		PageResult<TicketGrantView> partial = grants(filter(null, "gil-d", null, null, null, null, null), FIRST_PAGE);
		PageResult<TicketGrantView> upper = grants(filter(null, "HONG GIL", null, null, null, null, null), FIRST_PAGE);
		PageResult<TicketGrantView> underscore = grants(filter(null, "gil_d", null, null, null, null, null), FIRST_PAGE);
		PageResult<TicketGrantView> percent = grants(filter(null, "%", null, null, null, null, null), FIRST_PAGE);
		// then
		assertThat(partial.getItems()).extracting(TicketGrantView::getUserId).containsExactly(userId);
		assertThat(upper.getItems()).extracting(TicketGrantView::getUserId).containsExactly(userId);
		assertThat(underscore.getItems()).extracting(TicketGrantView::getUserId).containsExactly(other);
		assertThat(percent.getTotalElements()).isZero();
	}

	@Test
	@DisplayName("userId로 한 사용자의 지급만 보고 기간은 시작 포함·끝 제외로 적용한다")
	void filtersByUserAndHalfOpenPeriod() {
		// given
		grantAllSources(userId);
		UUID other = seeds.user();
		grantNewMissionClaim(other, 1);
		Instant firstGrant = BASE;
		Instant secondGrant = BASE.plusSeconds(60);
		// when
		PageResult<TicketGrantView> fromInclusive = grants(
				filter(userId, null, null, null, null, secondGrant, null), FIRST_PAGE);
		PageResult<TicketGrantView> toExclusive = grants(
				filter(userId, null, null, null, null, null, secondGrant), FIRST_PAGE);
		PageResult<TicketGrantView> window = grants(
				filter(userId, null, null, null, null, firstGrant, secondGrant), FIRST_PAGE);
		// then
		assertThat(grantsOf(userId).getItems()).allMatch(view -> view.getUserId().equals(userId));
		assertThat(fromInclusive.getTotalElements()).isEqualTo(2);
		assertThat(toExclusive.getTotalElements()).isEqualTo(2);
		assertThat(window.getTotalElements()).isEqualTo(2);
		assertThat(window.getItems()).allMatch(view -> view.getSourceType() == GrantSourceType.ATTENDANCE);
	}

	@Test
	@DisplayName("페이지 번호와 크기로 나누고 전체 건수는 그대로이며 범위를 넘으면 빈 페이지다")
	void pagesResults() {
		// given
		grantAllSources(userId);
		// when
		PageResult<TicketGrantView> first = grants(filter(userId, null, null, null, null, null, null),
				new PageQuery(1, 3));
		PageResult<TicketGrantView> second = grants(filter(userId, null, null, null, null, null, null),
				new PageQuery(2, 3));
		PageResult<TicketGrantView> beyond = grants(filter(userId, null, null, null, null, null, null),
				new PageQuery(3, 3));
		// then
		assertThat(first.getItems()).hasSize(3);
		assertThat(second.getItems()).hasSize(1);
		assertThat(first.getTotalElements()).isEqualTo(4);
		assertThat(second.getTotalElements()).isEqualTo(4);
		assertThat(first.getItems()).doesNotContainAnyElementsOf(second.getItems());
		assertThat(beyond.getItems()).isEmpty();
		assertThat(beyond.getTotalElements()).isEqualTo(4);
	}

	@Test
	@DisplayName("지급이 아닌 이력(사용)은 지급 이력에 나오지 않는다")
	void excludesNonGrantHistories() {
		// given
		grantNewMissionClaim(userId, 1);
		long grantsBefore = grantsOf(userId).getTotalElements();
		jdbc.update("""
				insert into ticket_histories (id, ticket_id, operation_type, status, ticket_version, expires_at, reason,
				  created_at)
				select ?, t.id, 'EXPIRE', 'EXPIRED', 2, h.expires_at, 'x', h.created_at
				from tickets t join ticket_histories h on h.ticket_id = t.id where t.user_id = ?
				""", bytes(UUID.randomUUID()), bytes(userId));
		// when
		long grantsAfter = grantsOf(userId).getTotalElements();
		// then
		assertThat(grantsBefore).isEqualTo(1);
		assertThat(grantsAfter).isEqualTo(1);
	}

	@Test
	@DisplayName("조건이 없을 때 조인 없이 센 총건수가 조인해서 읽은 행 수와 같다")
	void countWithoutJoinsMatchesRows() {
		// given
		grantAllSources(userId);
		// when
		PageResult<TicketGrantView> page = grants(filter(null, null, null, null, null, null, null), FIRST_PAGE);
		// then
		assertThat(page.getTotalElements()).isEqualTo(page.getItems().size()).isEqualTo(4);
	}

	@Test
	@DisplayName("관리자 이력 조회용 정렬 인덱스가 만들어져 있다")
	void createsAdminListIndexes() {
		// when
		java.util.List<String> indexes = jdbc.queryForList("""
				select distinct index_name from information_schema.statistics
				where table_schema = database() and table_name = 'ticket_histories'
				""", String.class);
		// then
		assertThat(indexes).contains("ix_ticket_histories_created", "ix_ticket_histories_op_created");
	}

	@Test
	@DisplayName("서버 기본 로케일이 터키어여도 대문자 I가 포함된 영문 keyword로 이름을 찾는다")
	void keywordIgnoresDefaultLocale() {
		// given
		renameUser(userId, "Hong Gil-Dong");
		grantNewMissionClaim(userId, 1);
		java.util.Locale original = java.util.Locale.getDefault();
		java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
		try {
			// when
			PageResult<TicketGrantView> page = grants(filter(userId, "GIL", null, null, null, null, null), FIRST_PAGE);
			// then
			assertThat(page.getTotalElements()).isEqualTo(1);
		} finally {
			java.util.Locale.setDefault(original);
		}
	}
}
