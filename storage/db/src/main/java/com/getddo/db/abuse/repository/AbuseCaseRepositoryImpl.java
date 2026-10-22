package com.getddo.db.abuse.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.getddo.core.abuse.domain.AbuseCaseDetail;
import com.getddo.core.abuse.domain.AbuseCaseFilter;
import com.getddo.core.abuse.domain.AbuseCaseSummary;
import com.getddo.core.abuse.repository.AbuseCaseRepository;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.db.abuse.entity.AbuseCaseEntity;
import com.getddo.db.abuse.mapper.AbuseCaseMapper;
import com.getddo.db.abuse.mapper.AbuseEvidenceMapper;
import com.getddo.db.common.pagination.PageOffsets;

/** 목록·건수에 같은 조건을 적용하고 사용자·선택 이벤트 표시값을 한 번에 읽는다. */
@Repository
@RequiredArgsConstructor
public class AbuseCaseRepositoryImpl implements AbuseCaseRepository {

	private static final String FROM = """
			 from AbuseCaseEntity a
			 join UserEntity u on u.id = a.userId
			 left join EventEntity e on e.id = a.eventId
			""";

	private static final String AUTOMATIC_ENTRY = " where a.sourceType = :entrySource"
			+ " and a.detectionType = :automaticDetection and a.blockedAt is not null";

	private final EntityManager entityManager;
	private final AbuseCaseMapper mapper;
	private final AbuseEvidenceMapper evidenceMapper;

	/** 만료 조건을 두지 않아 오래된 차단도 조회하며 이벤트가 없는 행은 left join으로 보존한다. */
	@Override
	public PageResult<AbuseCaseSummary> findAll(AbuseCaseFilter filter, PageQuery page) {
		Map<String, Object> parameters = automaticParameters();
		String where = where(filter, parameters);
		TypedQuery<Long> count = entityManager.createQuery("select count(a)" + FROM + where, Long.class);
		parameters.forEach(count::setParameter);
		long total = count.getSingleResult();
		if (page.offset() >= total || PageOffsets.exceedsIntRange(page)) {
			return new PageResult<>(List.of(), page.getPage(), page.getSize(), total);
		}

		TypedQuery<Object[]> select = entityManager.createQuery("select a, u.name, e.title" + FROM + where
				+ " order by a.blockedAt desc, a.id desc", Object[].class);
		parameters.forEach(select::setParameter);
		List<AbuseCaseSummary> items = select.setFirstResult((int) page.offset()).setMaxResults(page.getSize())
				.getResultList().stream()
				.map(row -> mapper.toSummary((AbuseCaseEntity) row[0], (String) row[1], (String) row[2])).toList();
		return new PageResult<>(items, page.getPage(), page.getSize(), total);
	}

	/** GD-142와 동일한 조인·대상 조건을 쓰며 만료 여부로 상세 이력을 제외하지 않는다. */
	@Override
	public Optional<AbuseCaseDetail> findById(UUID caseId) {
		Map<String, Object> parameters = automaticParameters();
		parameters.put("caseId", caseId);
		TypedQuery<Object[]> select = entityManager.createQuery("select a, u.name, e.title" + FROM
				+ AUTOMATIC_ENTRY + " and a.id = :caseId", Object[].class);
		parameters.forEach(select::setParameter);
		return select.getResultList().stream().findFirst().map(row -> {
			AbuseCaseEntity entity = (AbuseCaseEntity) row[0];
			return new AbuseCaseDetail(mapper.toSummary(entity, (String) row[1], (String) row[2]),
					evidenceMapper.toDomain(entity.getEvidence()));
		});
	}

	private static Map<String, Object> automaticParameters() {
		Map<String, Object> parameters = new HashMap<>();
		parameters.put("entrySource", "ENTRY");
		parameters.put("automaticDetection", "REPEATED_RATE_LIMIT_EXCEEDED");
		return parameters;
	}

	/** 조회 가능한 자동 차단을 고정하고 선택 조건의 값은 모두 바인딩한다. */
	private static String where(AbuseCaseFilter filter, Map<String, Object> parameters) {
		StringBuilder where = new StringBuilder(AUTOMATIC_ENTRY);
		if (filter.getUserId() != null) {
			where.append(" and a.userId = :userId");
			parameters.put("userId", filter.getUserId());
		}
		if (filter.getEventId() != null) {
			where.append(" and a.eventId = :eventId");
			parameters.put("eventId", filter.getEventId());
		}
		if (filter.getSourceType() != null) {
			where.append(" and a.sourceType = :sourceType");
			parameters.put("sourceType", filter.getSourceType().name());
		}
		if (filter.getFrom() != null) {
			where.append(" and a.blockedAt >= :from");
			parameters.put("from", filter.getFrom());
		}
		if (filter.getTo() != null) {
			where.append(" and a.blockedAt < :to");
			parameters.put("to", filter.getTo());
		}
		return where.toString();
	}
}
