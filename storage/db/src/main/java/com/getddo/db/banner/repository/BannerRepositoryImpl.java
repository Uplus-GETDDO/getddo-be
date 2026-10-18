package com.getddo.db.banner.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.getddo.core.banner.domain.Banner;
import com.getddo.core.banner.domain.BannerOrder;
import com.getddo.core.banner.domain.BannerSettings;
import com.getddo.core.banner.exception.BannerErrorCode;
import com.getddo.core.banner.repository.BannerRepository;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.db.banner.entity.BannerEntity;
import com.getddo.db.banner.mapper.BannerMapper;
import com.getddo.db.common.util.UuidBinary;

/** Service의 트랜잭션과 카탈로그 잠금 아래 최대 5행의 순서를 원자적으로 저장한다. */
@Repository
@RequiredArgsConstructor
public class BannerRepositoryImpl implements BannerRepository {
	private final EntityManager entityManager;
	private final JdbcTemplate jdbc;
	private final BannerMapper mapper;

	@Override
	public List<Banner> findAll() {
		return entities().stream().map(this::toDetachedDomain).toList();
	}

	@Override
	public List<Banner> findVisible() {
		// INNER JOIN이 미연결 이전 배너를 숨긴다. 이벤트 취소·논리 삭제는 한 SELECT로 판정한다.
		return entityManager.createQuery("""
				select b from BannerEntity b join b.event e
				where e.deletedAt is null and e.status <> :canceled
				order by b.displayOrder, b.id
				""", BannerEntity.class).setParameter("canceled", EventStatus.CANCELED)
				.getResultList().stream().map(this::toDetachedDomain).toList();
	}

	@Override
	public void lockCatalog() {
		Integer id = jdbc.queryForObject("select id from banner_catalog_lock where id = 1 for update", Integer.class);
		if (id == null || id != 1) throw new IllegalStateException("배너 카탈로그 잠금 행이 없습니다.");
	}

	@Override
	public Banner append(BannerSettings settings, int availablePosition) {
		BannerEntity entity = new BannerEntity(settings, availablePosition);
		entityManager.persist(entity);
		entityManager.flush();
		return toDetachedDomain(entity);
	}

	@Override
	public void update(UUID bannerId, BannerSettings settings) {
		BannerEntity entity = entityManager.find(BannerEntity.class, bannerId);
		if (entity == null) throw new BusinessException(BannerErrorCode.BANNER_NOT_FOUND);
		entity.changeSettings(settings);
		entityManager.flush();
		entityManager.detach(entity);
	}

	@Override
	public void delete(UUID bannerId) {
		if (jdbc.update("delete from banners where id = ?", UuidBinary.toBytes(bannerId)) != 1) {
			throw new BusinessException(BannerErrorCode.BANNER_NOT_FOUND);
		}
	}

	@Override
	public void reorder(List<UUID> bannerIds, Instant changedAt) {
		entityManager.flush();
		List<BannerEntity> current = entities();
		BannerOrder.reorder(current.stream().map(BannerEntity::getId).toList(), bannerIds);
		List<BannerEntity> changed = current.stream()
				.filter(banner -> banner.getDisplayOrder() != bannerIds.indexOf(banner.getId()) + 1).toList();
		current.forEach(entityManager::detach);
		if (changed.isEmpty()) return;
		// 최대 5행이 모두 차면 CHECK(1~5)와 UNIQUE 아래 임시 순서 값이 없다.
		// 변경 행만 동일 ID·생성시각·기존 URL로 재삽입한다. COMMIT 전에는 외부에 중간 삭제가 보이지 않는다.
		// banners를 참조하는 FK는 현재 없다. 향후 추가 시 이 저장 전략도 함께 변경해야 한다.
		for (BannerEntity banner : changed) {
			jdbc.update("delete from banners where id = ?", UuidBinary.toBytes(banner.getId()));
		}
		// MySQL DATETIME에는 UTC의 벽시각을 전달한다. Timestamp는 JDBC/JVM 기본 시간대를 적용해 재배치마다 시각을 밀 수 있다.
		List<Object[]> values = new ArrayList<>();
		for (BannerEntity banner : changed) {
			values.add(new Object[] {UuidBinary.toBytes(banner.getId()),
					banner.getEventId() == null ? null : UuidBinary.toBytes(banner.getEventId()), banner.getImageKey(),
					bannerIds.indexOf(banner.getId()) + 1, LocalDateTime.ofInstant(banner.getCreatedAt(), ZoneOffset.UTC),
					LocalDateTime.ofInstant(changedAt, ZoneOffset.UTC), banner.getEventUrl()});
		}
		jdbc.batchUpdate("""
				insert into banners (id, event_id, image_key, display_order, created_at, updated_at, event_url)
				values (?, ?, ?, ?, ?, ?, ?)
				""", values);
	}

	private List<BannerEntity> entities() {
		return entityManager.createQuery("select b from BannerEntity b order by b.displayOrder, b.id", BannerEntity.class)
				.getResultList();
	}

	private Banner toDetachedDomain(BannerEntity entity) {
		Banner result = mapper.toDomain(entity);
		// 네이티브 순서 교체 뒤 이 배너만 이전 1차 캐시에서 읽히지 않게 한다. 다른 업무 Entity는 건드리지 않는다.
		entityManager.detach(entity);
		return result;
	}
}
