package com.getddo.core.entry.service;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.AdminEntryFilter;
import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.entry.domain.EntrySummary;
import com.getddo.core.entry.exception.EntryErrorCode;
import com.getddo.core.entry.exception.EntryException;
import com.getddo.core.entry.repository.EntryEventReader;
import com.getddo.core.entry.repository.EntryParticipantRepository;
import com.getddo.core.entry.repository.EntryRepository;
import com.getddo.core.user.domain.NamedValue;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserNameRepository;

/** 관리자의 응모 내역(AO06)과 이벤트 응모자(AO07) 조회. 읽기 전용이며 활성 관리자만 호출할 수 있다. */
@Service
@RequiredArgsConstructor
public class AdminEntryQueryService {

	private final EntryRepository entryRepository;
	private final EntryParticipantRepository participantRepository;
	private final EntryEventReader eventReader;
	private final UserNameRepository userNames;

	/**
	 * 모든 사용자의 응모 내역(접수·거절)을 조건에 맞게 최신순으로 조회한다.
	 *
	 * @param admin 공통 사용자 문맥이 확인한 요청 사용자. 활성 관리자여야 한다
	 * @throws EntryException 관리자가 아닌 경우({@code ENTRY-010})
	 */
	@Transactional(readOnly = true)
	public PageResult<NamedValue<EntrySummary>> findEntries(User admin, AdminEntryFilter filter, PageQuery page) {
		requireActiveAdmin(admin);
		PageResult<EntrySummary> found = entryRepository.findAll(filter, page);
		Map<UUID, String> names = userNames.findNamesByIds(
				found.getItems().stream().map(summary -> summary.getEntry().getUserId()).collect(Collectors.toSet()));
		return found.map(summary -> new NamedValue<>(summary, names.get(summary.getEntry().getUserId())));
	}

	/**
	 * 한 이벤트의 응모자를 최초 응모 시각 최신순으로 조회한다. 삭제된 이벤트의 응모자도 조회할 수 있다.
	 *
	 * @throws EntryException 관리자가 아닌 경우({@code ENTRY-010}), 이벤트가 없는 경우({@code ENTRY-004})
	 */
	@Transactional(readOnly = true)
	public PageResult<NamedValue<EntryParticipant>> findParticipants(User admin, UUID eventId, PageQuery page) {
		requireActiveAdmin(admin);
		eventReader.find(eventId).orElseThrow(() -> new EntryException(EntryErrorCode.EVENT_NOT_FOUND));
		PageResult<EntryParticipant> found = participantRepository.findByEvent(eventId, page);
		Map<UUID, String> names = userNames.findNamesByIds(
				found.getItems().stream().map(EntryParticipant::getUserId).collect(Collectors.toSet()));
		return found.map(participant -> new NamedValue<>(participant, names.get(participant.getUserId())));
	}

	private static void requireActiveAdmin(User user) {
		if (user == null || user.getRole() != UserRole.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
			throw new EntryException(EntryErrorCode.ADMIN_REQUIRED);
		}
	}
}
