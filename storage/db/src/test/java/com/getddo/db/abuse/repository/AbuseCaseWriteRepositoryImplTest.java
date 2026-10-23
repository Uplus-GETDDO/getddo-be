package com.getddo.db.abuse.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;
import com.getddo.core.common.ratelimit.EntryBlock;
import com.getddo.db.abuse.entity.AbuseCaseEntity;
import com.getddo.db.abuse.mapper.AbuseCaseMapper;
import com.getddo.db.abuse.mapper.AbuseEvidenceMapper;

class AbuseCaseWriteRepositoryImplTest {
	private final EntityManager manager = mock(EntityManager.class);
	private final AbuseCaseMapper mapper = mock(AbuseCaseMapper.class);
	private final AbuseEvidenceMapper evidence = new AbuseEvidenceMapper();
	private final AbuseCaseWriteRepositoryImpl repository = new AbuseCaseWriteRepositoryImpl(manager, mapper, evidence);
	private final Instant now = Instant.parse("2026-10-22T20:00:00.123Z");
	private final EntryBlock block = new EntryBlock(UUID.randomUUID(), UUID.randomUUID(), null, now, now, now.plusSeconds(600),
			new AbuseEvidence("entry-v1", AbuseTriggerScope.USER, now, now.plusSeconds(60), 20, 20, 20));

	@Test
	@DisplayName("모든 최초 저장 값이 같은 경우에만 중복 전달로 인정한다")
	void onlyFullOriginalContentIsConsideredSameDelivery() {
		// given
		AbuseCaseEntity expected = entity(evidence.toJson(block.getEvidence()), now.plusSeconds(600));
		when(mapper.toEntity(block)).thenReturn(expected);
		when(manager.find(AbuseCaseEntity.class, block.getId())).thenReturn(expected);
		// when / then
		assertThat(repository.isSameStored(block)).isTrue();
		when(manager.find(AbuseCaseEntity.class, block.getId())).thenReturn(entity(expected.getEvidence(), now.plusSeconds(601)));
		assertThat(repository.isSameStored(block)).isFalse();
		when(manager.find(AbuseCaseEntity.class, block.getId())).thenReturn(entity(expected.getEvidence().replace("entry-v1", "entry-v2"), now.plusSeconds(600)));
		assertThat(repository.isSameStored(block)).isFalse();
	}

	@Test
	@DisplayName("같은 PK의 기존 행이 없으면 중복 전달 성공이 아니다")
	void differentPrimaryKeyIsNotAConfirmedDuplicate() {
		// when / then
		assertThat(repository.isSameStored(block)).isFalse();
	}

	@Test
	@DisplayName("JSON 순서는 무시하되 추가 근거는 다른 내용으로 판단한다")
	void jsonFieldOrderDoesNotMatterButAdditionalEvidenceDoes() {
		// when / then
		assertThat(evidence.sameContent("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}")).isTrue();
		assertThat(evidence.sameContent("{\"a\":1,\"b\":2}", "{\"a\":1}")).isFalse();
	}

	private AbuseCaseEntity entity(String proof, Instant until) {
		return new AbuseCaseEntity(block.getId(), block.getUserId(), null, EntryBlock.SOURCE_TYPE, EntryBlock.DETECTION_TYPE,
				EntryBlock.DETECTION_REASON, proof, now, now, now, until);
	}
}
