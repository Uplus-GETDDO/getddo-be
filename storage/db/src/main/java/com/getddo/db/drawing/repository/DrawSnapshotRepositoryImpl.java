package com.getddo.db.drawing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.db.drawing.entity.DrawCandidateEntity;
import com.getddo.db.drawing.entity.DrawRunCandidateEntity;
import com.getddo.db.drawing.entity.DrawRunEntity;
import com.getddo.db.drawing.mapper.DrawSnapshotMapper;

import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_RUN;

/** 원본 잠금 이후 같은 서비스 트랜잭션에서 스냅샷을 JPA로 저장·조회한다. */
@Repository
@RequiredArgsConstructor
public class DrawSnapshotRepositoryImpl implements DrawSnapshotRepository {
	private static final int SNAPSHOT_BATCH_SIZE = 100;

	private final DrawSnapshotSourceReader source;
	private final DrawRunJpaRepository runs;
	private final DrawCandidateJpaRepository candidates;
	private final DrawRunCandidateJpaRepository links;
	private final DrawSnapshotMapper mapper;
	private final EntityManager entityManager;

	/** 서비스의 이벤트 선잠금 요청을 원본 조회에 위임해 입력 준비·확정의 잠금 순서를 유지한다. */
	@Override
	public List<UUID> findInitialDue(Instant cutoff, int limit) {
		return source.findInitialDue(cutoff, limit);
	}

	@Override
	public Optional<DrawSnapshotSource.Event> lockEvent(UUID eventId) {
		return source.lockEvent(eventId);
	}

	/** 실행 번호 0인 최초 실행과 저장된 후보 연결을 조회해 고정 스냅샷을 복원한다. */
	@Override
	public Optional<DrawSnapshot> findInitial(UUID eventId) {
		return runs.findByEventIdAndDrawTypeAndRunNumber(eventId, DrawType.INITIAL, 0)
				.map(run -> mapper.toDomain(run, links.findCandidates(run.getId())));
	}

	/** 현재 응모자와 접수·차감 근거를 서비스의 최초 입력 검증에 제공한다. */
	@Override
	public List<DrawSnapshotSource.Participant> findParticipants(UUID eventId) {
		return source.findParticipants(eventId);
	}

	/** 이벤트 잠금 아래 기존 최초 실행을 재사용하거나 입력 미고정 PREPARING 실행만 등록한다. */
	@Override public DrawSnapshot registerInitial(UUID eventId) {
		Optional<DrawSnapshot> existing = findInitial(eventId);
		if (existing.isPresent()) return existing.get();
		DrawRunEntity run = runs.saveAndFlush(new DrawRunEntity(eventId));
		return mapper.toDomain(run, List.of());
	}

	/** 실행 ID에 저장된 상태·규칙·후보 연결을 복원하며 입력 미고정 실행도 그대로 반환한다. */
	@Override public Optional<DrawSnapshot> findRegistered(UUID runId) {
		return runs.findById(runId).map(run -> mapper.toDomain(run, links.findCandidates(runId)));
	}

	/** 같은 이벤트의 미고정 INITIAL/PREPARING 실행인지 검증하고 후보·입력을 한 번 저장한다. */
	@Override public DrawSnapshot saveRegisteredInitial(DrawSnapshot snapshot) {
		DrawRunEntity run = runs.findForUpdate(snapshot.runId()).orElseThrow(() -> new BusinessException(INVALID_RUN));
		if (run.getDrawType() != DrawType.INITIAL || run.getStatus() != DrawRunStatus.PREPARING
				|| run.getSnapshotFixedAt() != null || !run.getEventId().equals(snapshot.eventId())) {
			throw new BusinessException(INVALID_RUN);
		}
		return saveInput(run, snapshot);
	}

	/** 미고정 REPLACEMENT 실행에 최초 후보 ID를 연결하고 최초 규칙에 대응하는 버전 2/4 입력을 고정한다. 최초 후보 상세 행을 새로 만들지 않는다. */
	@Override public DrawSnapshot saveRegisteredReplacement(DrawSnapshot snapshot) {
		DrawRunEntity run = runs.findForUpdate(snapshot.runId()).orElseThrow(() -> new BusinessException(INVALID_RUN));
		if (run.getDrawType() != DrawType.REPLACEMENT || run.getStatus() != DrawRunStatus.PREPARING
				|| run.getSnapshotFixedAt() != null || !run.getEventId().equals(snapshot.eventId())
				|| snapshot.drawType() != DrawType.REPLACEMENT || snapshot.status() != DrawRunStatus.READY
				|| snapshot.rules() == null || !snapshot.rules().replacementFormat() || snapshot.rules().originalRunId() == null) {
			throw new BusinessException(INVALID_RUN);
		}
		List<DrawCandidateEntity> existing = candidates.findAllById(snapshot.candidates().stream().map(DrawSnapshot.Candidate::id).toList());
		if (existing.size() != snapshot.candidates().size()) {
			throw new BusinessException(INVALID_RUN);
		}
		for (DrawCandidateEntity candidate : existing) {
			if (!candidate.getDrawRun().getId().equals(snapshot.rules().originalRunId())) {
				throw new BusinessException(INVALID_RUN);
			}
		}
		links.saveAllAndFlush(existing.stream().map(candidate -> new DrawRunCandidateEntity(run, candidate)).toList());
		mapper.fixInput(run, snapshot);
		runs.flush();
		entityManager.refresh(run);
		return mapper.toDomain(run, links.findCandidates(run.getId()));
	}

	/** 기존 최초 준비 흐름에서 새 실행을 등록하고 후보·입력을 같은 트랜잭션에 저장한다. */
	@Override
	public DrawSnapshot saveInitial(DrawSnapshot snapshot) {
		DrawRunEntity run = runs.saveAndFlush(new DrawRunEntity(snapshot.eventId()));
		return saveInput(run, snapshot);
	}

	/** 최초 후보·연결 저장에만 배치 100을 적용하고, 성공·실패 모두 호출자의 세션 설정을 복원한다. */
	private DrawSnapshot saveInput(DrawRunEntity run, DrawSnapshot snapshot) {
		Session session = entityManager.unwrap(Session.class);
		Integer previousBatchSize = session.getJdbcBatchSize();
		try {
			session.setJdbcBatchSize(SNAPSHOT_BATCH_SIZE);
			List<DrawCandidateEntity> savedCandidates = candidates.saveAllAndFlush(snapshot.candidates().stream()
					.map(candidate -> mapper.toEntity(run, candidate)).toList());
			links.saveAllAndFlush(savedCandidates.stream()
					.map(candidate -> new DrawRunCandidateEntity(run, candidate)).toList());
			mapper.fixInput(run, snapshot);
			runs.flush();

			// 실제 DB 정밀도로 다시 읽어 첫 응답과 재요청의 스냅샷을 일치시킨다.
			entityManager.refresh(run);
			return findInitial(snapshot.eventId()).orElseThrow();
		} finally {
			// 같은 서비스 트랜잭션의 후속 작업에 추첨용 배치 크기가 전파되지 않게 한다.
			session.setJdbcBatchSize(previousBatchSize);
		}
	}
}
