package com.nexus.press.app.brief.model;

import java.time.OffsetDateTime;
import java.util.List;

public record DailyBrief(
	OffsetDateTime generatedAt,
	OffsetDateTime from,
	OffsetDateTime to,
	String language,
	List<DailyBriefItem> items
) {}
