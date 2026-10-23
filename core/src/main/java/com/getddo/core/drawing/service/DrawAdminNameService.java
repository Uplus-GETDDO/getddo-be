package com.getddo.core.drawing.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.drawing.domain.DrawExecutionResult;
import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawPublicationChange;
import com.getddo.core.drawing.domain.DrawVerification;
import com.getddo.core.user.repository.UserNameRepository;

/** 권한 검사를 마친 관리자 응답에 필요한 현재 이름을 한 번에 조회한다. 저장 근거는 변경하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DrawAdminNameService {
	private final UserNameRepository users;

	/** 결과·유지 당첨자·취소 처리자의 이름을 함께 조회한다. */
	public Map<UUID, String> forDetail(DrawInspectionView.Detail detail) {
		Set<UUID> ids = new HashSet<>();
		detail.getResults().forEach(result -> ids.add(result.getUserId()));
		if (detail.getExecution() != null) {
			detail.getExecution().getCancellations().forEach(cancellation -> ids.add(cancellation.getCanceledBy()));
		}
		if (detail.getRun().getReplacementRules() != null) {
			detail.getRun().getReplacementRules().getRetainedAwards().forEach(award -> ids.add(award.getUserId()));
		}

		return find(ids);
	}

	/** 후보 한 페이지의 이름을 조회한다. */
	public Map<UUID, String> forCandidates(Collection<DrawInspectionView.Candidate> candidates) {
		Set<UUID> ids = new HashSet<>();
		candidates.forEach(candidate -> ids.add(candidate.getSnapshot().userId()));

		return find(ids);
	}

	/** 감사 이력 한 페이지의 처리자 이름을 조회한다. */
	public Map<UUID, String> forAudits(Collection<DrawInspectionView.Audit> audits) {
		Set<UUID> ids = new HashSet<>();
		audits.forEach(audit -> ids.add(audit.getActorId()));

		return find(ids);
	}

	/** 검증 이력의 처리자 이름을 조회한다. */
	public Map<UUID, String> forVerifications(Collection<DrawVerification> verifications) {
		Set<UUID> ids = new HashSet<>();
		verifications.forEach(verification -> ids.add(verification.getVerifiedBy()));

		return find(ids);
	}

	/** 확정된 실행 결과의 당첨자 이름을 조회한다. */
	public Map<UUID, String> forExecution(DrawExecutionResult result) {
		Set<UUID> ids = new HashSet<>();
		if (result.getConfirmation() != null) {
			result.getConfirmation().getResults().forEach(stored -> ids.add(stored.getSelection().getUserId()));
		}

		return find(ids);
	}

	/** 공개 변경 한 페이지의 전후 당첨자와 처리자 이름을 조회한다. */
	public Map<UUID, String> forChanges(Collection<DrawPublicationChange> changes) {
		Set<UUID> ids = new HashSet<>();
		changes.forEach(change -> {
			ids.add(change.getPublication().getConfirmedBy());
			change.getExecutions().forEach(execution -> ids.add(execution.getConfirmedBy()));
			change.getChanges().forEach(slot -> {
				ids.add(slot.getBeforeUserId());
				ids.add(slot.getAfterUserId());
			});
		});

		return find(ids);
	}

	/** 확인 또는 발표 기록에 저장된 처리자의 현재 이름을 조회한다. */
	public Map<UUID, String> forActor(UUID actorId) {
		Set<UUID> ids = new HashSet<>();
		ids.add(actorId);

		return find(ids);
	}

	private Map<UUID, String> find(Set<UUID> ids) {
		ids.remove(null);
		return ids.isEmpty() ? Map.of() : users.findNamesByIds(ids);
	}
}
