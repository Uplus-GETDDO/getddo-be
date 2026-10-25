package com.getddo.core.mission.service;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.mission.domain.MissionSubmissionCommand;
import com.getddo.core.mission.domain.MissionSubmissionResult;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;

/**
 * 사용자가 미션에 답변을 제출한다(M03).
 *
 * <p>트랜잭션을 열지 않고 {@link MissionSubmissionRecorder}가 연 트랜잭션을 한 번 다시 시도한다. 같은 사용자의 동시 완료나 같은
 * 제출 ID의 동시 요청은 DB의 유일 제약이 직렬화하므로 늦은 쪽이 실패하는데, 롤백된 트랜잭션은 버리고 새 트랜잭션에서 처리하면
 * 먼저 확정된 결과를 읽어 기존 결과를 돌려준다.</p>
 */
@Service
@RequiredArgsConstructor
public class MissionSubmissionService {

	private static final int MYSQL_DUPLICATE_ENTRY = 1062;

	private final MissionSubmissionRecorder recorder;
	private final TimeProvider timeProvider;

	/**
	 * 제출을 접수하고 채점한다. 같은 제출 ID로 다시 요청하면 새로 접수하지 않고 이미 확정된 결과를 돌려준다.
	 *
	 * @throws MissionException 요청 값이 없거나 미션 없음·운영 기간 밖·답변 형식 오류·같은 제출 ID의 다른 요청인 경우
	 * @throws com.getddo.core.ticket.exception.TicketException 응모권 지급이 실패한 경우
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public MissionSubmissionResult submit(MissionSubmissionCommand command) {
		if (command == null || command.getUserId() == null || command.getMissionId() == null
				|| command.getSubmissionId() == null || command.getAnswers() == null) {
			throw new MissionException(MissionErrorCode.INVALID_MISSION_ANSWER);
		}
		// 서버 도착 시각을 처리 시작 전에 한 번 정한다. 사용자 행 잠금을 기다리거나 재처리하는 동안 종료 시각이 지나도 도착
		// 시각이 운영 기간 안이면 접수해야 한다. DATETIME(6)이 저장하는 정밀도로 잘라 응답·재조회·저장 값이 어긋나지 않게 한다.
		Instant receivedAt = timeProvider.now().truncatedTo(ChronoUnit.MICROS);
		try {
			return recorder.record(command, receivedAt);
		} catch (PessimisticLockingFailureException concurrentSubmission) {
			// 동시 제출의 잠금 교착·대기 실패다. 롤백된 트랜잭션은 버리고 새 트랜잭션에서 한 번만 다시 처리한다.
			return recorder.record(command, receivedAt);
		} catch (DataIntegrityViolationException violation) {
			// 같은 제출 ID·정답 문항·보상 청구가 동시에 겹친 UNIQUE 위반만 다시 처리한다. FK·NOT NULL·CHECK 위반은 재처리해도
			// 같으므로 그대로 전파한다.
			if (!isDuplicateKey(violation)) {
				throw violation;
			}
			return recorder.record(command, receivedAt);
		}
	}

	/** 원인 사슬에 MySQL 중복 키 오류(1062)가 있는지 확인한다. */
	private static boolean isDuplicateKey(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
				return true;
			}
		}
		return false;
	}
}
