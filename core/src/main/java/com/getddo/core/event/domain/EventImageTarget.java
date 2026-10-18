package com.getddo.core.event.domain;

/** 업로드한 이미지를 사용할 이벤트·경품 영역이다. */
public enum EventImageTarget {
	EVENT("events"), PRIZE("prizes");

	private final String directory;

	EventImageTarget(String directory) {
		this.directory = directory;
	}

	public String getDirectory() {
		return directory;
	}
}
