package com.nexus.press.app.news.model;

public record NewsEmbeddingVector(
	String id,
	float[] embedding
) {}
