package com.getddo.core.stats.exception;

import com.getddo.core.common.exception.BusinessException;

public class StatsException extends BusinessException {

	public StatsException(StatsErrorCode errorCode) {
		super(errorCode);
	}
}
