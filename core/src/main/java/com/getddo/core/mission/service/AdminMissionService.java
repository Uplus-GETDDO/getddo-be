package com.getddo.core.mission.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionRegistration;
import com.getddo.core.mission.domain.MissionSearch;
import com.getddo.core.mission.domain.QuizOption;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.repository.MissionRepository;
import com.getddo.core.mission.repository.MissionRewardPolicyRepository;
import com.getddo.core.user.domain.NamedValue;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserNameRepository;

/**
 * 관리자 미션 조회(AM01·AM02)와 등록(AM03). 활성 관리자만 호출할 수 있다.
 *
 * <p>등록한 미션은 바로 운영 상태({@code ACTIVE})이며 초안 상태는 없다. 사용자가 보는 기간은 운영 시작·종료 시각이 정한다.
 * 운영 중인 미션의 문항·조건·보상은 수정하지 않으므로 수정 경로를 두지 않는다. 미션 보상은 등급 응모권 1장으로 고정이라
 * 미션마다 수량 1의 보상 정책을 함께 만든다.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminMissionService {

	private static final int MIN_CHOICE_OPTIONS = 2;
	private static final String MISSION_CREATE = "MISSION_CREATE";
	private static final String TARGET_MISSION = "MISSION";

	private final MissionRepository missionRepository;
	private final MissionRewardPolicyRepository rewardPolicies;
	private final AuditLogService auditLogs;
	private final UserNameRepository userNames;

	/**
	 * 미션 목록을 운영 시작 시각 최신순으로 조회한다. 문항은 담지 않는다. 작성자의 현재 이름을 함께 돌려준다.
	 *
	 * @throws MissionException 관리자가 아닌 경우({@code MISSION-006})
	 */
	@Transactional(readOnly = true)
	public PageResult<NamedValue<Mission>> findMissions(User admin, MissionSearch search, PageQuery page) {
		requireActiveAdmin(admin);
		PageResult<Mission> found = missionRepository.search(search, page);
		Map<UUID, String> names = userNames.findNamesByIds(
				found.getItems().stream().map(Mission::getCreatedBy).collect(Collectors.toSet()));
		return found.map(mission -> new NamedValue<>(mission, names.get(mission.getCreatedBy())));
	}

	/**
	 * 미션 한 건을 정답 정보를 포함한 문항·선택지와 함께 조회한다.
	 *
	 * @throws MissionException 관리자가 아니거나({@code MISSION-006}) 미션이 없는 경우({@code MISSION-003})
	 */
	@Transactional(readOnly = true)
	public NamedValue<MissionDetail> findMission(User admin, UUID missionId) {
		requireActiveAdmin(admin);
		MissionDetail detail = missionRepository.findDetailById(missionId)
				.orElseThrow(() -> new MissionException(MissionErrorCode.MISSION_NOT_FOUND));
		UUID createdBy = detail.getMission().getCreatedBy();
		return new NamedValue<>(detail, userNames.findNamesByIds(Set.of(createdBy)).get(createdBy));
	}

	/**
	 * 미션을 문항·선택지·보상 정책과 함께 한 트랜잭션에 등록한다.
	 *
	 * @throws MissionException 관리자가 아니거나({@code MISSION-006}), 기간·제목·문항 구성이 올바르지 않거나, 선택형 퀴즈
	 *         문항의 선택지가 2개 미만이거나 정답이 정확히 하나가 아닌 경우({@code MISSION-001})
	 */
	@Transactional
	public NamedValue<MissionDetail> register(User admin, MissionRegistration registration) {
		requireActiveAdmin(admin);
		requireAnswerableQuiz(registration);
		UUID policyId = rewardPolicies.createFixedPolicy(admin.getId(), registration.getStartsAt());
		Mission mission = Mission.register(policyId, admin.getId(), registration.getTitle(),
				registration.getDescription(), registration.getMissionType(), registration.getStartsAt(),
				registration.getEndsAt(), registration.getImageKey());
		MissionDetail saved = missionRepository.insert(
				new MissionDetail(mission, registration.getQuizQuestions(), registration.getSurveyQuestions()));
		auditLogs.record(new AuditLogCommand(admin.getId(), MISSION_CREATE, TARGET_MISSION,
				saved.getMission().getId(), null, null,
				Map.of("missionType", registration.getMissionType().name(),
						"startsAt", registration.getStartsAt().toString(),
						"endsAt", registration.getEndsAt().toString()),
				null));
		// 방금 등록한 미션의 작성자는 요청한 관리자 본인이다.
		return new NamedValue<>(saved, admin.getName());
	}

	/** 선택형 퀴즈 문항은 사용자가 정답을 고를 수 있어야 한다. 정답이 없거나 둘 이상이면 거절한다. */
	private static void requireAnswerableQuiz(MissionRegistration registration) {
		for (QuizQuestion question : registration.getQuizQuestions()) {
			if (!question.getQuestionType().isChoice()) {
				continue;
			}
			long correct = question.getOptions().stream().filter(QuizOption::isCorrect).count();
			if (question.getOptions().size() < MIN_CHOICE_OPTIONS || correct != 1) {
				throw new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION);
			}
		}
	}

	private static void requireActiveAdmin(User user) {
		if (user == null || user.getRole() != UserRole.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
			throw new MissionException(MissionErrorCode.ADMIN_REQUIRED);
		}
	}
}
