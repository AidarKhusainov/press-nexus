package com.nexus.press.app.brief.model;

import java.time.OffsetDateTime;

public record DailyBriefItem(
	String newsId,
	String title,
	String url,
	String media,
	OffsetDateTime eventAt,
	BriefImportance importance,
	String whatHappened,
	String whyImportant,
	String whatNext
) {}
