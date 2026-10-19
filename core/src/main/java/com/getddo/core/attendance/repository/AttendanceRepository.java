package com.getddo.core.attendance.repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.getddo.core.attendance.domain.Attendance;

/** 출석 기록 저장소. 사용자·KST 날짜마다 출석은 한 건만 저장할 수 있다. */
public interface AttendanceRepository {

	/**
	 * 사용자의 특정 KST 날짜 출석을 조회한다.
	 *
	 * @param userId         사용자 ID
	 * @param attendanceDate 출석 기준 KST 날짜
	 * @return 출석. 없으면 빈 값
	 */
	Optional<Attendance> findByUserIdAndDate(UUID userId, LocalDate attendanceDate);

	/**
	 * 사용자가 해당 월에 출석한 KST 날짜를 조회한다.
	 *
	 * @param userId 사용자 ID
	 * @param month  조회할 KST 월
	 * @return 날짜 오름차순. 출석이 없으면 빈 목록
	 */
	List<LocalDate> findAttendanceDates(UUID userId, YearMonth month);

	/**
	 * 출석을 저장하고 바로 DB에 반영한다.
	 *
	 * <p>같은 날 동시 요청은 {@code UNIQUE(user_id, attendance_date)}에서 먼저 줄 세워진다. 곧바로 반영해 두 번째 요청이
	 * 연속 출석 현황이나 보상 청구를 건드리기 전에 이 지점에서 대기하거나 실패하게 한다.</p>
	 *
	 * @param attendance ID가 없는 출석
	 * @return ID와 기록 시각이 채워진 출석
	 */
	Attendance insert(Attendance attendance);
}
