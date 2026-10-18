package com.getddo.core.banner.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.getddo.core.banner.exception.BannerErrorCode;
import com.getddo.core.common.exception.BusinessException;

/** 관리 목록 전체의 ID 순서를 계산한다. 반환 목록의 인덱스+1이 저장할 순서이며 숨김 배너도 포함한다. */
public final class BannerOrder {
	private BannerOrder() {
	}

	public static List<UUID> insert(List<UUID> current, UUID newId, int position) {
		List<UUID> ordered = copyCurrent(current);
		if (ordered.size() == 5) throw new BusinessException(BannerErrorCode.LIMIT_EXCEEDED);
		requirePosition(position, ordered.size() + 1);
		if (newId == null || ordered.contains(newId)) throw new IllegalStateException("새 배너 ID가 올바르지 않습니다.");
		ordered.add(position - 1, newId);
		return List.copyOf(ordered);
	}

	public static List<UUID> move(List<UUID> current, UUID bannerId, int position) {
		List<UUID> ordered = copyCurrent(current);
		if (!ordered.contains(bannerId)) throw new BusinessException(BannerErrorCode.BANNER_NOT_FOUND);
		requirePosition(position, ordered.size());
		ordered.remove(bannerId);
		ordered.add(position - 1, bannerId);
		return List.copyOf(ordered);
	}

	public static List<UUID> remove(List<UUID> current, UUID bannerId) {
		List<UUID> ordered = copyCurrent(current);
		if (!ordered.remove(bannerId)) throw new BusinessException(BannerErrorCode.BANNER_NOT_FOUND);
		return List.copyOf(ordered);
	}

	public static List<UUID> reorder(List<UUID> current, List<UUID> requested) {
		List<UUID> ordered = copyCurrent(current);
		if (requested == null || requested.size() != ordered.size()
				|| !new HashSet<>(requested).equals(new HashSet<>(ordered))) {
			throw new BusinessException(BannerErrorCode.LIST_CHANGED);
		}
		return List.copyOf(requested);
	}

	private static List<UUID> copyCurrent(List<UUID> current) {
		if (current == null || current.size() > 5 || current.stream().anyMatch(id -> id == null)
				|| new HashSet<>(current).size() != current.size()) {
			throw new IllegalStateException("저장된 배너 목록이 올바르지 않습니다.");
		}
		return new ArrayList<>(current);
	}

	private static void requirePosition(int position, int upperBound) {
		if (position < 1 || position > upperBound) throw new BusinessException(BannerErrorCode.INVALID_SETTINGS);
	}
}
