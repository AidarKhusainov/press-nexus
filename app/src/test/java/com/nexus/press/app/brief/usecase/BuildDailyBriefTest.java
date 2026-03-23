package com.nexus.press.app.brief.usecase;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.nexus.press.app.brief.model.BriefCandidate;
import com.nexus.press.app.brief.model.BriefImportance;
import com.nexus.press.app.brief.persistence.query.BriefCandidateQuery;
import com.nexus.press.app.brief.policy.BriefToneModerationPolicy;
import com.nexus.press.app.news.usecase.FindNearDuplicateNewsIds;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class BuildDailyBriefTest {

	@Mock
	private BriefCandidateQuery briefCandidateQuery;
	@Mock
	private FindNearDuplicateNewsIds findNearDuplicateNewsIds;
	@Mock
	private AppMetrics appMetrics;

	private BuildDailyBrief useCase;

	@BeforeEach
	void setUp() {
		useCase = new BuildDailyBrief(
			briefCandidateQuery,
			findNearDuplicateNewsIds,
			new BriefToneModerationPolicy(),
			appMetrics
		);
	}

	@Test
	void selectItemsSuppressesNearDuplicatesFromSimilarityGraph() {
		final OffsetDateTime now = OffsetDateTime.parse("2026-03-09T10:00:00Z");

		final List<BriefCandidate> candidates = List.of(
			new BriefCandidate(
				"n1",
				"Банк России сохранил ключевую ставку на уровне 18%",
				"https://example.com/rate-1",
				"RBC",
				now,
				"Банк России сохранил ключевую ставку на уровне 18 процентов. Решение влияет на кредиты и инфляцию. Следующее заседание пройдет в апреле."
			),
			new BriefCandidate(
				"n2",
				"ЦБ оставил ставку 18% без изменений",
				"https://example.com/rate-2",
				"RIA",
				now.minusMinutes(5),
				"Центробанк сохранил ставку на уровне 18 процентов. Это влияет на стоимость кредитов и динамику инфляции. Следующие сигналы рынок ждет после заседания."
			),
			new BriefCandidate(
				"n3",
				"Минтранс утвердил план модернизации региональных аэропортов",
				"https://example.com/airports",
				"TASS",
				now.minusMinutes(10),
				"Минтранс утвердил план модернизации региональных аэропортов. Это должно ускорить обновление инфраструктуры и маршрутов. Финансирование распределят по этапам."
			)
		);

		final List<String> selectedIds = useCase.selectItems(
				candidates,
				5,
				"ru",
				Set.of(),
				Map.of(
					"n1", Set.of("n2"),
					"n2", Set.of("n1")
				)
			).stream()
			.map(item -> item.newsId())
			.toList();

		assertEquals(2, selectedIds.size());
		assertEquals("n3", selectedIds.get(1));
		assertTrue(selectedIds.contains("n1") || selectedIds.contains("n2"));
	}

	@Test
	void selectItemsSuppressesNearDuplicatesByTextFingerprint() {
		final OffsetDateTime now = OffsetDateTime.parse("2026-03-09T10:00:00Z");

		final List<BriefCandidate> candidates = List.of(
			new BriefCandidate(
				"n1",
				"Минфин предложил повысить налог на дивиденды крупных компаний",
				"https://example.com/tax-1",
				"VEDOMOSTI",
				now,
				"Минфин предложил повысить налог на дивиденды крупных компаний. Изменение затронет корпоративные выплаты и бюджетные поступления. Обсуждение инициативы продолжится после консультаций с бизнесом."
			),
			new BriefCandidate(
				"n2",
				"Крупным компаниям предложили повысить налог на дивиденды",
				"https://example.com/tax-2",
				"KOMMERSANT",
				now.minusMinutes(3),
				"Крупным компаниям предложили повысить налог на дивиденды. Мера повлияет на корпоративные выплаты и доходы бюджета. Обсуждение идеи продолжат на консультациях с бизнесом."
			),
			new BriefCandidate(
				"n3",
				"Российские экспортеры нарастили поставки зерна в феврале",
				"https://example.com/grain",
				"INTERFAX",
				now.minusMinutes(10),
				"Российские экспортеры нарастили поставки зерна в феврале. Рост важен для валютной выручки и логистики портов. Участники рынка ждут обновления прогноза на весну."
			)
		);

		final List<String> selectedIds = useCase.selectItems(candidates, 5, "ru", Set.of()).stream()
			.map(item -> item.newsId())
			.toList();

		assertEquals(List.of("n1", "n3"), selectedIds);
	}

	@Test
	void selectItemsBalancesMustKnowAndGoodToKnowWhenHighPriorityItemsDominateFeed() {
		final OffsetDateTime now = OffsetDateTime.parse("2026-03-09T10:00:00Z");

		final List<BriefCandidate> candidates = List.of(
			new BriefCandidate(
				"n1",
				"Банк России сохранил ключевую ставку на уровне 18%",
				"https://example.com/rate",
				"RBK",
				now,
				"Банк России сохранил ключевую ставку на уровне 18 процентов. Решение влияет на стоимость кредитов и инфляцию. Следующее заседание пройдет в апреле."
			),
			new BriefCandidate(
				"n2",
				"Минфин представил проект федерального бюджета на 2027 год",
				"https://example.com/budget",
				"VEDOMOSTI",
				now.minusMinutes(8),
				"Минфин представил проект федерального бюджета на 2027 год. Документ задает рамку для налоговой и расходной политики. Обсуждение параметров продолжится в правительстве."
			),
			new BriefCandidate(
				"n3",
				"Правительство расширило список товаров под новые тарифы",
				"https://example.com/tariffs",
				"TASS",
				now.minusMinutes(12),
				"Правительство расширило список товаров под новые тарифы. Решение повлияет на импортные цепочки и цены. Бизнес ждет разъяснений регуляторов."
			),
			new BriefCandidate(
				"n4",
				"В регионах открыли дополнительные электрички на длинные выходные",
				"https://example.com/trains",
				"REGIONS",
				now.minusHours(8),
				"В регионах открыли дополнительные электрички на длинные выходные."
			),
			new BriefCandidate(
				"n5",
				"Музеи продлили вечерние часы работы на весенние каникулы",
				"https://example.com/museums",
				"AFISHA",
				now.minusHours(10),
				"Музеи продлили вечерние часы работы на весенние каникулы."
			)
		);

		final var items = useCase.selectItems(candidates, 4, "ru", Set.of());

		assertEquals(4, items.size());
		assertEquals(2, items.stream().filter(item -> item.importance() == BriefImportance.MUST_KNOW).count());
		assertEquals(2, items.stream().filter(item -> item.importance() == BriefImportance.GOOD_TO_KNOW).count());
		assertTrue(items.stream().map(item -> item.newsId()).toList().containsAll(List.of("n4", "n5")));
		assertEquals(2, items.stream().filter(item -> item.importance() == BriefImportance.MUST_KNOW).map(item -> item.media()).distinct().count());
	}

	@Test
	void selectItemsPrefersSourceDiversityBeforeRepeatingTheSameMedia() {
		final OffsetDateTime now = OffsetDateTime.parse("2026-03-09T10:00:00Z");

		final List<BriefCandidate> candidates = List.of(
			new BriefCandidate(
				"n1",
				"В Москве открыли новый пересадочный узел метро",
				"https://example.com/metro-1",
				"RIA",
				now,
				"В Москве открыли новый пересадочный узел метро. Это сократит время в пути для части пассажиров. Транспортный эффект оценят в ближайшие недели."
			),
			new BriefCandidate(
				"n2",
				"Столичные школы получат обновленные лаборатории в апреле",
				"https://example.com/schools",
				"RIA",
				now.minusHours(9),
				"Столичные школы получат обновленные лаборатории в апреле."
			),
			new BriefCandidate(
				"n3",
				"В Петербурге запустили пилот по электронным пропускам в музеи",
				"https://example.com/museums-spb",
				"FONTANKA",
				now.minusHours(5),
				"В Петербурге запустили пилот по электронным пропускам в музеи."
			),
			new BriefCandidate(
				"n4",
				"Регионы получили дополнительное финансирование на ремонт дорог",
				"https://example.com/roads",
				"URA",
				now.minusHours(6),
				"Регионы получили дополнительное финансирование на ремонт дорог."
			)
		);

		final var items = useCase.selectItems(candidates, 3, "ru", Set.of());

		assertEquals(3, items.size());
		assertEquals(3, items.stream().map(item -> item.media()).distinct().count());
		assertTrue(items.stream().map(item -> item.newsId()).toList().contains("n1"));
		assertTrue(items.stream().map(item -> item.newsId()).toList().contains("n3"));
	}
}
