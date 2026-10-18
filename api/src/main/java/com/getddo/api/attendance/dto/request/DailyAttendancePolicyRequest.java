package com.getddo.api.attendance.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 일일 출석 정책 등록(AP02) 요청. 적용 시점은 서버가 다음 KST 자정으로 정한다.
 *
 * @param rewardTicketCount 일일 출석 보상 수량. 1 이상
 */
public record DailyAttendancePolicyRequest(@NotNull @Min(1) Integer rewardTicketCount) {
}
