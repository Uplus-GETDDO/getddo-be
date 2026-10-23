package com.getddo.db.abuse.repository;

import java.util.Objects;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.getddo.core.abuse.repository.AbuseCaseWriteRepository;
import com.getddo.core.common.ratelimit.EntryBlock;
import com.getddo.db.abuse.entity.AbuseCaseEntity;
import com.getddo.db.abuse.mapper.AbuseCaseMapper;
import com.getddo.db.abuse.mapper.AbuseEvidenceMapper;

/** 이력 최초 삽입과 동일 PK의 전체 저장값 비교를 담당한다. 트랜잭션은 core Service가 소유한다. */
@Repository
@RequiredArgsConstructor
public class AbuseCaseWriteRepositoryImpl implements AbuseCaseWriteRepository {
	private final EntityManager entityManager;
	private final AbuseCaseMapper mapper;
	private final AbuseEvidenceMapper evidence;

	@Override
	public void insert(EntryBlock block) {
		entityManager.persist(mapper.toEntity(block));
		// UNIQUE·FK·CHECK 오류를 현재 건의 트랜잭션 안에서 확인한다. 기존 행을 merge/upsert하지 않는다.
		entityManager.flush();
	}

	@Override
	public boolean isSameStored(EntryBlock block) {
		AbuseCaseEntity stored = entityManager.find(AbuseCaseEntity.class, block.getId());
		if (stored == null) {
			// 복합 UNIQUE만 같은 다른 blockId는 같은 차단이 아니다.
			return false;
		}
		AbuseCaseEntity expected = mapper.toEntity(block);
		return Objects.equals(stored.getUserId(), expected.getUserId())
				&& Objects.equals(stored.getEventId(), expected.getEventId())
				&& stored.getAttendanceId() == null && stored.getMissionSubmissionId() == null
				&& stored.getGamePlayId() == null
				&& Objects.equals(stored.getSourceType(), expected.getSourceType())
				&& Objects.equals(stored.getDetectionType(), expected.getDetectionType())
				&& Objects.equals(stored.getDetectionReason(), expected.getDetectionReason())
				&& Objects.equals(stored.getOccurredAt(), expected.getOccurredAt())
				&& Objects.equals(stored.getDetectedAt(), expected.getDetectedAt())
				&& Objects.equals(stored.getBlockedAt(), expected.getBlockedAt())
				&& Objects.equals(stored.getBlockedUntil(), expected.getBlockedUntil())
				&& evidence.sameContent(stored.getEvidence(), expected.getEvidence());
	}
}
