package com.nexus.press.app.service.ai.summ;

final class SummarizationPromptSupport {

	private SummarizationPromptSupport() {
	}

	static String systemInstruction(final String lang) {
		return switch (lang) {
			case "ru" -> "Ты опытный журналист. Отвечай ТОЛЬКО на русском языке.";
			case "en" -> "You are an experienced journalist. Respond ONLY in English.";
			case "es" -> "Eres un periodista experimentado. Responde SOLO en espanol.";
			default -> "You are an experienced journalist. Respond ONLY in English.";
		};
	}

	static String userTask(final String lang) {
		return switch (lang) {
			case "ru" -> "Кратко перескажи новость в 3-5 нейтральных предложениях, сохраняя факты и даты.";
			case "en" -> "Summarize the news in 3-5 neutral sentences, preserving facts and dates.";
			case "es" -> "Resume la noticia en 3-5 oraciones neutrales, preservando hechos y fechas.";
			default -> "Summarize the news in 3-5 neutral sentences, preserving facts and dates.";
		};
	}

	static String userPrompt(final String text, final String lang) {
		return userTask(lang) + "\n\n" + text;
	}

	static String providerLogLabel(final SummarizationProvider provider) {
		return switch (provider) {
			case GEMINI -> "Gemini";
			case GROQ -> "Groq";
			case CLOUDFLARE_WORKERS_AI -> "Cloudflare Workers AI";
			case MISTRAL -> "Mistral";
		};
	}
}
