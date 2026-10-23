package com.getddo.db.mission;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionSearch;
import com.getddo.core.mission.service.AdminMissionService;
import com.getddo.core.user.domain.NamedValue;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserNameRepository;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 미션 목록·상세(AM01·AM02)의 작성자 이름과 사용자 이름 일괄 조회를 실제 MySQL로 검증한다. */
class AdminMissionNamesIntegrationTest extends MissionIntegrationTestSupport {

	@Autowired
	private AdminMissionService adminService;
	@Autowired
	private UserNameRepository userNames;

	private User admin() {
		return new User(adminId, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null, null, null);
	}

	private String nameOf(UUID user) {
		return jdbc.queryForObject("select name from users where id = ?", String.class, bytes(user));
	}

	@Test
	@DisplayName("미션 목록과 상세가 작성자의 현재 이름을 담고 이름을 바꾸면 다음 조회에 바뀐 이름이 나간다")
	void missionsCarryCreatorName() {
		// given
		MissionDetail saved = saveQuizMission("작성자 이름 미션");
		UUID missionId = saved.getMission().getId();

		// when
		PageResult<NamedValue<Mission>> list = adminService.findMissions(admin(), MissionSearch.all(), new PageQuery(1, 20));
		NamedValue<MissionDetail> detail = adminService.findMission(admin(), missionId);

		// then
		assertThat(list.getItems()).extracting(named -> named.getValue().getId()).contains(missionId);
		assertThat(list.getItems()).filteredOn(named -> named.getValue().getId().equals(missionId))
				.allSatisfy(named -> assertThat(named.getName()).isEqualTo(nameOf(adminId)));
		assertThat(detail.getName()).isEqualTo(nameOf(adminId));

		// when: 작성자의 이름이 바뀐다
		jdbc.update("update users set name = ? where id = ?", "바뀐 관리자", bytes(adminId));

		// then: 같은 작성자 ID에 현재 이름이 나간다
		NamedValue<MissionDetail> renamed = adminService.findMission(admin(), missionId);
		assertThat(renamed.getValue().getMission().getCreatedBy()).isEqualTo(adminId);
		assertThat(renamed.getName()).isEqualTo("바뀐 관리자");
	}

	@Test
	@DisplayName("사용자 이름 일괄 조회는 있는 사용자만 이름을 돌려주고 없는 ID는 담지 않으며 빈 입력은 빈 결과다")
	void findsNamesInBatch() {
		// given
		UUID unknown = UUID.randomUUID();

		// when
		Map<UUID, String> found = userNames.findNamesByIds(Set.of(userId, adminId, unknown));

		// then
		assertThat(found).containsOnlyKeys(userId, adminId);
		assertThat(found.get(userId)).isEqualTo(nameOf(userId));
		assertThat(found.get(adminId)).isEqualTo(nameOf(adminId));
		assertThat(userNames.findNamesByIds(Set.of())).isEmpty();
	}
}
