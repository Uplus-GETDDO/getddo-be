package com.getddo.core.abuse.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.getddo.core.event.repository.EventQueryRepository;
import lombok.RequiredArgsConstructor;

/** 집계용 이벤트 scope와 구별하여 저장 payload에 연결할 실재 이벤트만 확인한다. */
@Service
@RequiredArgsConstructor
public class EntryAbuseEventService {
	private final EventQueryRepository events;

	/** 삭제·미존재 이벤트는 NULL이며 응모 자격·공개 상태를 새로 판정하지 않는다. */
	@Transactional(readOnly = true)
	public UUID findExistingId(UUID id) {
		return events.findById(id).isPresent() ? id : null;
	}
}
