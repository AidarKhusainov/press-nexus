package com.nexus.press.app.util;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {

	private TextChunker() {
	}

	/**
	 * Простой чанкинг: собираем предложения до maxChars.
	 */
	public static List<String> bySentences(final String text, final int maxChars) {
		final List<String> out = new ArrayList<>();
		if (text == null || text.isBlank()) return out;

		final String[] parts = text.split("(?<=[.!?…])\\s+");
		final StringBuilder buf = new StringBuilder();

		for (final String p : parts) {
			if (p.length() > maxChars) {
				flush(buf, out);
				splitHard(p, maxChars, out);
				continue;
			}
			if (buf.length() + p.length() + 1 > maxChars && !buf.isEmpty()) {
				flush(buf, out);
			}
			if (!buf.isEmpty()) buf.append(' ');
			buf.append(p);
		}
		flush(buf, out);
		return out;
	}

	private static void splitHard(final String s, final int max, final List<String> out) {
		int i = 0;
		while (i < s.length()) {
			final int j = Math.min(i + max, s.length());
			out.add(s.substring(i, j).trim());
			i = j;
		}
	}

	private static void flush(final StringBuilder buf, final List<String> out) {
		if (!buf.isEmpty()) {
			out.add(buf.toString().trim());
			buf.setLength(0);
		}
	}
}

