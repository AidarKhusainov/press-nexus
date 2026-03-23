package com.nexus.press.app.profile.model;

public record TelegramUserContext(
	String chatId,
	Long telegramUserId,
	String username,
	String firstName,
	String language
) {
}
