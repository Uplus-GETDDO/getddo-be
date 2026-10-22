package com.getddo.core.user.domain;

import java.util.Objects;

import lombok.Getter;

/**
 * 관리자 화면에 보여 줄 값과 그 값에 연결된 사용자의 현재 이름.
 *
 * <p>이름은 표시용 부가 정보다. 이름이 바뀌면 이후 조회에는 현재 이름이 나가며, 동일인 판단과 업무 규칙은 값 안의 ID를 따른다.
 * 값에 연결된 ID가 없거나 사용자를 찾지 못하면 이름은 {@code null}이다.</p>
 *
 * @param <T> 이름을 덧붙일 값
 */
@Getter
public final class NamedValue<T> {

	private final T value;
	/** 값에 연결된 사용자의 현재 이름. 연결된 ID가 없거나 사용자를 찾지 못하면 null. */
	private final String name;

	public NamedValue(T value, String name) {
		this.value = Objects.requireNonNull(value, "value");
		this.name = name;
	}
}
