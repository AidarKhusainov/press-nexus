package com.nexus.press.app.news.model;

import java.util.Set;

public record NewsCluster(
	Set<String> ids,
	String representativeId
) {}
