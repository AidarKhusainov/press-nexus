package com.nexus.press.app.brief.model;

import java.time.OffsetDateTime;

public record BriefCandidate(
	String id,
	String title,
	String url,
	String media,
	OffsetDateTime eventAt,
	String summary
) {}
