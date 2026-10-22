package com.getddo.db.abuse.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.abuse.domain.AbuseCaseFilter;
import com.getddo.core.abuse.domain.AbuseCaseSummary;
import com.getddo.core.abuse.domain.AbuseSourceType;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.db.abuse.entity.AbuseCaseEntity;
import com.getddo.db.abuse.mapper.AbuseCaseMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DB를 연결하지 않고 조건 바인딩·페이지 경계·표시값 전달만 검증한다. JPQL 실행 검증은 아니다. */
@ExtendWith(MockitoExtension.class)
class AbuseCaseRepositoryImplTest {

	@Mock
	private EntityManager entityManager;
	@Mock
	private AbuseCaseMapper mapper;
	@Mock
	private TypedQuery<Long> count;
	@Mock
	private TypedQuery<Object[]> select;

	@Test
	@DisplayName("목록과 건수의 필터를 같게 바인딩하고 종료 시각을 제외하며 저장 순서와 표시값을 유지한다")
	void bindsConditionsAndMapsJoinedNames() {
		// given
		UUID userId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant from = Instant.parse("2026-10-21T06:00:00Z");
		Instant to = from.plusSeconds(600);
		when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(count);
		when(count.getSingleResult()).thenReturn(3L);
		when(entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(select);
		when(select.setFirstResult(1)).thenReturn(select);
		when(select.setMaxResults(1)).thenReturn(select);
		AbuseCaseEntity entity = mock(AbuseCaseEntity.class);
		when(select.getResultList()).thenReturn(List.<Object[]>of(new Object[] {entity, "닉네임", null}));
		AbuseCaseSummary summary = mock(AbuseCaseSummary.class);
		when(mapper.toSummary(entity, "닉네임", null)).thenReturn(summary);

		// when
		PageResult<AbuseCaseSummary> result = new AbuseCaseRepositoryImpl(entityManager, mapper).findAll(
				new AbuseCaseFilter(userId, eventId, AbuseSourceType.ENTRY, from, to), new PageQuery(2, 1));

		// then
		assertThat(result.getItems()).containsExactly(summary);
		assertThat(result.getTotalElements()).isEqualTo(3);
		ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> selectSql = ArgumentCaptor.forClass(String.class);
		verify(entityManager).createQuery(countSql.capture(), eq(Long.class));
		verify(entityManager).createQuery(selectSql.capture(), eq(Object[].class));
		assertThat(selectSql.getValue()).endsWith(" order by a.blockedAt desc, a.id desc")
				.contains("left join EventEntity", "a.blockedAt >= :from", "a.blockedAt < :to")
				.doesNotContain("blockedUntil", "current_timestamp");
		assertThat(countSql.getValue().substring(countSql.getValue().indexOf(" where ")))
				.isEqualTo(selectSql.getValue().substring(selectSql.getValue().indexOf(" where "))
						.replace(" order by a.blockedAt desc, a.id desc", ""));
		for (TypedQuery<?> query : List.of(count, select)) {
			verify(query).setParameter("entrySource", "ENTRY");
			verify(query).setParameter("automaticDetection", "REPEATED_RATE_LIMIT_EXCEEDED");
			verify(query).setParameter("userId", userId);
			verify(query).setParameter("eventId", eventId);
			verify(query).setParameter("sourceType", "ENTRY");
			verify(query).setParameter("from", from);
			verify(query).setParameter("to", to);
		}
	}

	@Test
	@DisplayName("int 오프셋을 넘는 페이지는 건수를 유지하고 항목 조회 없이 빈 결과를 반환한다")
	void handlesOversizedOffset() {
		// given
		when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(count);
		when(count.getSingleResult()).thenReturn(Long.MAX_VALUE);

		// when
		PageResult<AbuseCaseSummary> result = new AbuseCaseRepositoryImpl(entityManager, mapper).findAll(
				new AbuseCaseFilter(null, null, null, null, null), new PageQuery(Integer.MAX_VALUE, 100));

		// then
		assertThat(result.getItems()).isEmpty();
		assertThat(result.getTotalElements()).isEqualTo(Long.MAX_VALUE);
		verifyNoInteractions(mapper, select);
	}

	@Test
	@DisplayName("조회 결과가 없으면 정상 빈 페이지이며 항목 쿼리를 실행하지 않는다")
	void returnsEmptyPage() {
		// given
		when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(count);
		when(count.getSingleResult()).thenReturn(0L);

		// when
		PageResult<AbuseCaseSummary> result = new AbuseCaseRepositoryImpl(entityManager, mapper).findAll(
				new AbuseCaseFilter(null, null, null, null, null), new PageQuery(1, 20));

		// then
		assertThat(result.getItems()).isEmpty();
		assertThat(result.getTotalElements()).isZero();
		verifyNoInteractions(mapper, select);
	}
}
