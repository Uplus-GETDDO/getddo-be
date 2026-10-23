package com.getddo.db.user.repository;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.AdminUserQuery;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.AdminUserQueryRepository;
import com.getddo.db.common.pagination.PageOffsets;
import com.getddo.db.user.entity.UserEntity;
import com.getddo.db.user.mapper.UserMapper;

/** 기존 사용자 Entity·MapStruct를 재사용하며 선택 조건을 목록과 건수에 동일하게 적용한다. */
@Repository
@RequiredArgsConstructor
public class AdminUserQueryRepositoryImpl implements AdminUserQueryRepository {
	private final EntityManager entityManager;
	private final UserMapper mapper;

	@Override
	public PageResult<User> findAll(AdminUserQuery query) {
		CriteriaBuilder builder = entityManager.getCriteriaBuilder();
		CriteriaQuery<Long> count = builder.createQuery(Long.class);
		Root<UserEntity> countRoot = count.from(UserEntity.class);
		count.select(builder.count(countRoot)).where(predicates(query, builder, countRoot));
		long total = entityManager.createQuery(count).getSingleResult();
		PageQuery page = query.getPage();
		// JPA의 int 오프셋 범위를 넘는 요청도 전체 건수를 보존한 빈 페이지로 반환한다.
		if (PageOffsets.exceedsIntRange(page) || page.offset() >= total) {
			return new PageResult<>(List.of(), page.getPage(), page.getSize(), total);
		}
		CriteriaQuery<UserEntity> select = builder.createQuery(UserEntity.class);
		Root<UserEntity> user = select.from(UserEntity.class);
		select.select(user).where(predicates(query, builder, user))
				.orderBy(builder.desc(user.get("createdAt")), builder.desc(user.get("id")));
		List<User> items = entityManager.createQuery(select).setFirstResult((int) page.offset())
				.setMaxResults(page.getSize()).getResultList().stream().map(mapper::toDomain).toList();
		return new PageResult<>(items, page.getPage(), page.getSize(), total);
	}

	@Override
	public long countActiveRegularUsers() {
		return entityManager.createQuery(
				"select count(u) from UserEntity u where u.role = :role and u.status = :status", Long.class)
				.setParameter("role", UserRole.USER)
				.setParameter("status", UserStatus.ACTIVE)
				.getSingleResult();
	}

	private static Predicate[] predicates(AdminUserQuery query, CriteriaBuilder builder, Root<UserEntity> user) {
		List<Predicate> conditions = new ArrayList<>();
		if (query.getUserId() != null) {
			conditions.add(builder.equal(user.get("id"), query.getUserId()));
		}
		if (query.getRole() != null) {
			conditions.add(builder.equal(user.get("role"), query.getRole()));
		}
		if (query.getStatus() != null) {
			conditions.add(builder.equal(user.get("status"), query.getStatus()));
		}
		if (query.getMembership() != null) {
			conditions.add(builder.equal(user.get("membership"), query.getMembership()));
		}
		if (query.getKeyword() != null) {
			String escaped = query.getKeyword().replace("!", "!!").replace("%", "!%").replace("_", "!_");
			conditions.add(builder.like(user.get("name"), "%" + escaped + "%", '!'));
		}
		return conditions.toArray(Predicate[]::new);
	}
}
