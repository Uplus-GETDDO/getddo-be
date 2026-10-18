package com.getddo.api.event.dto.request;

import java.time.LocalDate;

import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.BindParam;

import com.getddo.core.event.domain.AdminEventQuery;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;

/** 관리자 검색 쿼리를 받는다. 날짜 변환은 Spring에, UTC 경계 계산은 서비스에 맡긴다. */
@Getter
public final class AdminEventQueryRequest {
	private final String keyword;
	private final EventStatus status;
	private final EventType eventType;
	@Schema(description = "검색 시작 날짜(KST, 해당 날짜 포함)", type = "string", format = "date", example = "2026-10-10")
	private final LocalDate from;
	@Schema(description = "검색 종료 날짜(KST, 해당 날짜 포함)", type = "string", format = "date", example = "2026-10-20")
	private final LocalDate to;
	private final Boolean weightingEnabled;
	@Schema(description = "등록일 검색 시작 날짜(KST, 포함)", type = "string", format = "date", example = "2026-10-01")
	private final LocalDate createdFrom;
	@Schema(description = "등록일 검색 종료 날짜(KST, 포함)", type = "string", format = "date", example = "2026-10-17")
	private final LocalDate createdTo;
	@Schema(description = "createdAt·startsAt·endsAt·title 중 하나와 asc·desc. 기본 createdAt,desc", example = "createdAt,desc")
	private final String sort;

	// LocalDate 변환과 별개로 빈 값·확장 연도를 거절하는 기존 YYYY-MM-DD 계약을 유지한다.
	@Schema(hidden = true)
	@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}")
	private final String fromInput;
	@Schema(hidden = true)
	@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}")
	private final String toInput;
	@Schema(hidden = true)
	@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}")
	private final String createdFromInput;
	@Schema(hidden = true)
	@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}")
	private final String createdToInput;

	public AdminEventQueryRequest(String keyword, EventStatus status, EventType eventType,
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@BindParam("from") String fromInput, @BindParam("to") String toInput,
			Boolean weightingEnabled,
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
			@BindParam("createdFrom") String createdFromInput, @BindParam("createdTo") String createdToInput,
			String sort) {
		this.keyword = keyword;
		this.status = status;
		this.eventType = eventType;
		this.from = from;
		this.to = to;
		this.fromInput = fromInput;
		this.toInput = toInput;
		this.weightingEnabled = weightingEnabled;
		this.createdFrom = createdFrom;
		this.createdTo = createdTo;
		this.createdFromInput = createdFromInput;
		this.createdToInput = createdToInput;
		this.sort = sort;
	}

	public AdminEventQuery toDomain() {
		return new AdminEventQuery(status, eventType, keyword, from, to,
				weightingEnabled, createdFrom, createdTo, sort);
	}
}
