package com.getddo.core.banner.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.banner.domain.Banner;
import com.getddo.core.banner.domain.BannerOrder;
import com.getddo.core.banner.domain.BannerSettings;
import com.getddo.core.banner.exception.BannerErrorCode;
import com.getddo.core.banner.repository.BannerRepository;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.repository.EventRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

/** 배너 자격·전체 개수·이벤트 연결·순서·감사 기록을 하나의 업무 단위로 처리한다. */
@Service
@RequiredArgsConstructor
public class BannerService {
	private final BannerRepository banners;
	private final EventRepository events;
	private final AuditLogService audit;
	private final TimeProvider time;
	private final BannerImageConfiguration images;

	@Transactional(readOnly = true)
	public List<Banner> list(User actor) {
		if (actor == null || actor.getStatus() != UserStatus.ACTIVE
				|| (actor.getRole() != UserRole.USER && actor.getRole() != UserRole.ADMIN)) {
			throw new BusinessException(BannerErrorCode.USER_INACTIVE);
		}
		return banners.findVisible();
	}

	@Transactional(readOnly = true)
	public List<Banner> listForAdmin(User actor) {
		requireAdmin(actor);
		return banners.findAll();
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Banner create(User actor, BannerSettings settings) {
		requireAdmin(actor);
		images.requireConfigured();
		requireConnectableEvent(settings);
		banners.lockCatalog();
		List<Banner> before = banners.findAll();
		if (before.size() >= 5) throw new BusinessException(BannerErrorCode.LIMIT_EXCEEDED);
		if (settings.getDisplayOrder() > before.size() + 1) {
			throw new BusinessException(BannerErrorCode.INVALID_SETTINGS);
		}
		Banner created = banners.append(settings, availablePosition(before));
		banners.reorder(BannerOrder.insert(ids(before), created.getId(), settings.getDisplayOrder()), time.now());
		List<Banner> after = banners.findAll();
		record(actor, "BANNER_CREATE", created.getId(), before, after);
		return find(after, created.getId());
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Banner update(User actor, UUID bannerId, BannerSettings settings) {
		requireAdmin(actor);
		images.requireConfigured();
		// 모든 등록·수정은 이벤트 → 카탈로그 순서로 잠가 이벤트 취소와 새 연결의 경합을 정리한다.
		requireConnectableEvent(settings);
		banners.lockCatalog();
		List<Banner> before = banners.findAll();
		List<UUID> order = BannerOrder.move(ids(before), bannerId, settings.getDisplayOrder());
		banners.update(bannerId, settings);
		banners.reorder(order, time.now());
		List<Banner> after = banners.findAll();
		record(actor, "BANNER_UPDATE", bannerId, before, after);
		return find(after, bannerId);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void delete(User actor, UUID bannerId) {
		requireAdmin(actor);
		banners.lockCatalog();
		List<Banner> before = banners.findAll();
		List<UUID> order = BannerOrder.remove(ids(before), bannerId);
		banners.delete(bannerId);
		banners.reorder(order, time.now());
		record(actor, "BANNER_DELETE", bannerId, before, banners.findAll());
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public List<Banner> reorder(User actor, List<UUID> requested) {
		requireAdmin(actor);
		banners.lockCatalog();
		List<Banner> before = banners.findAll();
		List<UUID> order = BannerOrder.reorder(ids(before), requested);
		if (!before.isEmpty()) images.requireConfigured();
		banners.reorder(order, time.now());
		List<Banner> after = banners.findAll();
		// 같은 ID 순서라도 이전 데이터의 빈 순서를 정리했다면 실제 전후 값을 기록한다.
		if (!before.isEmpty() && !snapshot(before).equals(snapshot(after))) {
			record(actor, "BANNER_REORDER", order.getFirst(), before, after);
		}
		return after;
	}

	private void requireConnectableEvent(BannerSettings settings) {
		if (settings == null) throw new BusinessException(BannerErrorCode.INVALID_SETTINGS);
		EventStatus status = events.findStatusByIdForUpdate(settings.getEventId())
				.orElseThrow(() -> new BusinessException(BannerErrorCode.EVENT_NOT_FOUND));
		if (status == EventStatus.CANCELED) throw new BusinessException(BannerErrorCode.EVENT_CANCELED);
	}

	private static void requireAdmin(User actor) {
		if (actor == null || actor.getRole() != UserRole.ADMIN || actor.getStatus() != UserStatus.ACTIVE) {
			throw new BusinessException(BannerErrorCode.ADMIN_REQUIRED);
		}
	}

	private static int availablePosition(List<Banner> current) {
		for (int position = 1; position <= 5; position++) {
			int candidate = position;
			if (current.stream().noneMatch(banner -> banner.getDisplayOrder() == candidate)) return position;
		}
		throw new BusinessException(BannerErrorCode.LIMIT_EXCEEDED);
	}

	private static List<UUID> ids(List<Banner> list) {
		return list.stream().map(Banner::getId).toList();
	}

	private static Banner find(List<Banner> list, UUID id) {
		return list.stream().filter(banner -> banner.getId().equals(id)).findFirst()
				.orElseThrow(() -> new BusinessException(BannerErrorCode.BANNER_NOT_FOUND));
	}

	private void record(User actor, String action, UUID targetId, List<Banner> before, List<Banner> after) {
		audit.record(new AuditLogCommand(actor.getId(), action, "BANNER", targetId,
				null, snapshot(before), snapshot(after), null));
	}

	private static Map<String, Object> snapshot(List<Banner> banners) {
		return Map.of("banners", banners.stream().map(banner -> {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("id", banner.getId().toString());
			value.put("eventId", banner.getEventId() == null ? null : banner.getEventId().toString());
			value.put("imageKey", banner.getImageKey());
			value.put("displayOrder", banner.getDisplayOrder());
			return value;
		}).toList());
	}
}
