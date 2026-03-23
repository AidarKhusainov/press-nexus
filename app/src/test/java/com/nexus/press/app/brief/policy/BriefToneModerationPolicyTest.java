package com.nexus.press.app.brief.policy;

import com.nexus.press.app.brief.model.BriefImportance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BriefToneModerationPolicyTest {

	private final BriefToneModerationPolicy policy = new BriefToneModerationPolicy();

	@Test
	void rejectsClickbaitForGoodToKnowItems() {
		final var decision = policy.moderate(
			"ШОК!!! Вы не поверите, что произошло!!!",
			"Это взорвало интернет и никто не ожидал такого поворота.",
			"Сенсация дня с явным эмоциональным накалом.",
			"Подробности позже.",
			"ru",
			BriefImportance.GOOD_TO_KNOW
		);

		assertTrue(decision.rejected());
		assertTrue(decision.toneScore() >= 5);
	}

	@Test
	void allowsNeutralCard() {
		final var decision = policy.moderate(
			"Правительство утвердило план модернизации транспорта",
			"Опубликован поэтапный график внедрения с бюджетом.",
			"Решение влияет на сроки инфраструктурных проектов.",
			"Следующий отчёт представят через месяц.",
			"ru",
			BriefImportance.GOOD_TO_KNOW
		);

		assertFalse(decision.rejected());
		assertTrue(decision.toneScore() < 5);
	}

	@Test
	void normalizesRepeatedPunctuation() {
		final var decision = policy.moderate(
			"Важное обновление!!!",
			"Что случилось???",
			"Почему важно!!!",
			"Что дальше???",
			"ru",
			BriefImportance.MUST_KNOW
		);

		assertEquals("Важное обновление!", decision.moderatedTitle());
		assertEquals("Что случилось?", decision.moderatedWhatHappened());
		assertEquals("Почему важно!", decision.moderatedWhyImportant());
		assertEquals("Что дальше?", decision.moderatedWhatNext());
	}
}
