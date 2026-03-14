package com.nexus.press.app.service.ai.summ;

public class GeminiTransientException extends RuntimeException {

	public GeminiTransientException(final String message) {
		super(message);
	}

	public GeminiTransientException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
