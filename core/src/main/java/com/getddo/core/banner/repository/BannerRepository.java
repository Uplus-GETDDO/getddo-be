package com.getddo.core.banner.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.getddo.core.banner.domain.Banner;
import com.getddo.core.banner.domain.BannerSettings;

/** 잠금·변경 메서드는 호출 Service의 동일 트랜잭션 안에서 사용한다. */
public interface BannerRepository {
	List<Banner> findAll();
	List<Banner> findVisible();

	/** 빈 목록에서도 등록·삭제·순서 변경을 직렬화하는 카탈로그 잠금. */
	void lockCatalog();

	/** 카탈로그 잠금 후 사용하지 않는 순서에 생성하고 공통 UUID·생성/수정 시각을 반환한다. */
	Banner append(BannerSettings settings, int availablePosition);

	/** ID·생성 시각·기존 URL은 보존하고 연결 이벤트·이미지 키만 수정한다. */
	void update(UUID bannerId, BannerSettings settings);
	void delete(UUID bannerId);

	/** 현재 전체 ID를 받아 CHECK·UNIQUE를 유지하며 순서를 한 트랜잭션에서 교체한다. */
	void reorder(List<UUID> bannerIds, Instant changedAt);
}
