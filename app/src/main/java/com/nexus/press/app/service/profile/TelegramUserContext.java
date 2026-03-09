package com.nexus.press.app.service.profile;

public record TelegramUserContext(
	String chatId,
	Long telegramUserId,
	String username,
	String firstName,
	String language
) {
}
