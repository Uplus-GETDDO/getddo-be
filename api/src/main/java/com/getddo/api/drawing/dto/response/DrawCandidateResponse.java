package com.getddo.api.drawing.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawSnapshot;

/** 관리자용 확정 후보. 현재 사용자 개인정보를 조회·추가하지 않는다. */
public record DrawCandidateResponse(UUID id, UUID participantId, UUID userId, long ticketCount, long weight,
		EntryEvidence entrySnapshot, EligibilityEvidence eligibilitySnapshot) {
	/** 저장된 후보와 응모 근거를 API 응답으로 변환한다. */
	public static DrawCandidateResponse from(DrawInspectionView.Candidate view) {
		DrawSnapshot.Candidate candidate = view.getSnapshot();
		return new DrawCandidateResponse(candidate.id(), candidate.participantId(), candidate.userId(),
				candidate.ticketCount(), candidate.weight(), EntryEvidence.from(candidate.entryEvidence()),
				EligibilityEvidence.from(candidate.eligibilityEvidence()));
	}
	public record EntryEvidence(int version, long bronzeCount, long silverCount, long goldCount, long platinumCount, List<Entry> entries) {
		public EntryEvidence {
			entries = List.copyOf(entries);
		}
		static EntryEvidence from(DrawSnapshot.EntryEvidence evidence) {
			return evidence == null ? null : new EntryEvidence(evidence.version(), evidence.bronzeCount(),
					evidence.silverCount(),
					evidence.goldCount(), evidence.platinumCount(), evidence.entries().stream().map(entry -> new Entry(entry.id(),
					entry.deductedTicketCount(),
					entry.acceptedAt(), entry.uses().stream().map(use -> new Use(use.historyId(), use.ticketId(), use.ownerId(),
					use.grade() == null ? null : use.grade().name())).toList())).toList());
		}
	}
	public record Entry(UUID id, long deductedTicketCount, Instant acceptedAt, List<Use> uses) {
		public Entry {
			uses = List.copyOf(uses);
		}
	}
	public record Use(UUID historyId, UUID ticketId, UUID ownerId, String grade) {
	}
	public record EligibilityEvidence(int version, UUID userId, String membership,
			String role, boolean excluded, Instant checkedAt) {
		static EligibilityEvidence from(DrawSnapshot.EligibilityEvidence evidence) {
			return evidence == null ? null : new EligibilityEvidence(evidence.version(),
					evidence.userId(), evidence.membership(),
					evidence.role(), evidence.excluded(), evidence.checkedAt());
		}
	}
}
