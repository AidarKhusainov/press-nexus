package com.nexus.press.app.service.brief.model;

import java.time.OffsetDateTime;
import java.util.List;

public record DailyBrief(
	OffsetDateTime generatedAt,
	OffsetDateTime from,
	OffsetDateTime to,
	String language,
	List<DailyBriefItem> items
) {}
