package com.getddo.core.user.repository;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 관리자 화면에 함께 보여 줄 사용자의 현재 이름 조회. 읽기 전용이다. */
public interface UserNameRepository {

	/**
	 * 사용자 ID들의 현재 이름을 한 번에 조회한다. 목록 한 페이지의 이름을 건건이 조회하지 않게 쓴다.
	 *
	 * @param userIds 조회할 사용자 ID. 비어 있으면 빈 맵
	 * @return 찾은 사용자의 ID별 이름. 없는 사용자는 담지 않는다
	 */
	Map<UUID, String> findNamesByIds(Collection<UUID> userIds);
}
