package com.nexus.press.app.service.ai.summ;

public class SummarizationThrottledException extends RuntimeException {

	public SummarizationThrottledException(final String message) {
		super(message);
	}

	public SummarizationThrottledException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
