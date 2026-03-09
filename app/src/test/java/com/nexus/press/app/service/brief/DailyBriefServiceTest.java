package com.nexus.press.app.service.brief;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.brief.model.BriefImportance;
import com.nexus.press.app.service.news.ReactiveNewsSimilarityStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class DailyBriefServiceTest {

	@Mock
	private DatabaseClient db;
	@Mock
	private ReactiveNewsSimilarityStore similarityStore;
	@Mock
	private AppMetrics appMetrics;

	private DailyBriefService service;

	@BeforeEach
	void setUp() {
		service = new DailyBriefService(
			db,
			similarityStore,
			new BriefToneModerationService(),
			appMetrics
		);
	}

	@Test
	void selectItemsSuppressesNearDuplicatesFromSimilarityGraph() {
		final OffsetDateTime now = OffsetDateTime.parse("2026-03-09T10:00:00Z");

		final List<DailyBriefService.Candidate> candidates = List.of(
			new DailyBriefService.Candidate(
				"n1",
				"Банк России сохранил ключевую ставку на уровне 18%",
				"https://example.com/rate-1",
				"RBC",
				now,
				"Банк России сохранил ключевую ставку на уровне 18 процентов. Решение влияет на кредиты и инфляцию. Следующее заседание пройдет в апреле."
			),
			new DailyBriefService.Candidate(
				"n2",
				"ЦБ оставил ставку 18% без изменений",
				"https://example.com/rate-2",
				"RIA",
				now.minusMinutes(5),
				"Центробанк сохранил ставку на уровне 18 процентов. Это влияет на стоимость кредитов и динамику инфляции. Следующие сигналы рынок ждет после заседания."
			),
			new DailyBriefService.Candidate(
				"n3",
				"Минтранс утвердил план модернизации региональных аэропортов",
				"https://example.com/airports",
				"TASS",
				now.minusMinutes(10),
				"Минтранс утвердил план модернизации региональных аэропортов. Это должно ускорить обновление инфраструктуры и маршрутов. Финансирование распределят по этапам."
			)
		);

		final List<String> selectedIds = service.selectItems(
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

		final List<DailyBriefService.Candidate> candidates = List.of(
			new DailyBriefService.Candidate(
				"n1",
				"Минфин предложил повысить налог на дивиденды крупных компаний",
				"https://example.com/tax-1",
				"VEDOMOSTI",
				now,
				"Минфин предложил повысить налог на дивиденды крупных компаний. Изменение затронет корпоративные выплаты и бюджетные поступления. Обсуждение инициативы продолжится после консультаций с бизнесом."
			),
			new DailyBriefService.Candidate(
				"n2",
				"Крупным компаниям предложили повысить налог на дивиденды",
				"https://example.com/tax-2",
				"KOMMERSANT",
				now.minusMinutes(3),
				"Крупным компаниям предложили повысить налог на дивиденды. Мера повлияет на корпоративные выплаты и доходы бюджета. Обсуждение идеи продолжат на консультациях с бизнесом."
			),
			new DailyBriefService.Candidate(
				"n3",
				"Российские экспортеры нарастили поставки зерна в феврале",
				"https://example.com/grain",
				"INTERFAX",
				now.minusMinutes(10),
				"Российские экспортеры нарастили поставки зерна в феврале. Рост важен для валютной выручки и логистики портов. Участники рынка ждут обновления прогноза на весну."
			)
		);

		final List<String> selectedIds = service.selectItems(candidates, 5, "ru", Set.of()).stream()
			.map(item -> item.newsId())
			.toList();

		assertEquals(List.of("n1", "n3"), selectedIds);
	}

	@Test
	void selectItemsBalancesMustKnowAndGoodToKnowWhenHighPriorityItemsDominateFeed() {
		final OffsetDateTime now = OffsetDateTime.parse("2026-03-09T10:00:00Z");

		final List<DailyBriefService.Candidate> candidates = List.of(
			new DailyBriefService.Candidate(
				"n1",
				"Банк России сохранил ключевую ставку на уровне 18%",
				"https://example.com/rate",
				"RBK",
				now,
				"Банк России сохранил ключевую ставку на уровне 18 процентов. Решение влияет на стоимость кредитов и инфляцию. Следующее заседание пройдет в апреле."
			),
			new DailyBriefService.Candidate(
				"n2",
				"Минфин представил проект федерального бюджета на 2027 год",
				"https://example.com/budget",
				"VEDOMOSTI",
				now.minusMinutes(8),
				"Минфин представил проект федерального бюджета на 2027 год. Документ задает рамку для налоговой и расходной политики. Обсуждение параметров продолжится в правительстве."
			),
			new DailyBriefService.Candidate(
				"n3",
				"Правительство расширило список товаров под новые тарифы",
				"https://example.com/tariffs",
				"TASS",
				now.minusMinutes(12),
				"Правительство расширило список товаров под новые тарифы. Решение повлияет на импортные цепочки и цены. Бизнес ждет разъяснений регуляторов."
			),
			new DailyBriefService.Candidate(
				"n4",
				"В регионах открыли дополнительные электрички на длинные выходные",
				"https://example.com/trains",
				"REGIONS",
				now.minusHours(8),
				"В регионах открыли дополнительные электрички на длинные выходные."
			),
			new DailyBriefService.Candidate(
				"n5",
				"Музеи продлили вечерние часы работы на весенние каникулы",
				"https://example.com/museums",
				"AFISHA",
				now.minusHours(10),
				"Музеи продлили вечерние часы работы на весенние каникулы."
			)
		);

		final var items = service.selectItems(candidates, 4, "ru", Set.of());

		assertEquals(4, items.size());
		assertEquals(2, items.stream().filter(item -> item.importance() == BriefImportance.MUST_KNOW).count());
		assertEquals(2, items.stream().filter(item -> item.importance() == BriefImportance.GOOD_TO_KNOW).count());
		assertTrue(items.stream().map(item -> item.newsId()).toList().containsAll(List.of("n4", "n5")));
		assertEquals(2, items.stream().filter(item -> item.importance() == BriefImportance.MUST_KNOW).map(item -> item.media()).distinct().count());
	}

	@Test
	void selectItemsPrefersSourceDiversityBeforeRepeatingTheSameMedia() {
		final OffsetDateTime now = OffsetDateTime.parse("2026-03-09T10:00:00Z");

		final List<DailyBriefService.Candidate> candidates = List.of(
			new DailyBriefService.Candidate(
				"n1",
				"В Москве открыли новый пересадочный узел метро",
				"https://example.com/metro-1",
				"RIA",
				now,
				"В Москве открыли новый пересадочный узел метро. Это сократит время в пути для части пассажиров. Транспортный эффект оценят в ближайшие недели."
			),
			new DailyBriefService.Candidate(
				"n2",
				"Столичные школы получат обновленные лаборатории в апреле",
				"https://example.com/schools",
				"RIA",
				now.minusHours(9),
				"Столичные школы получат обновленные лаборатории в апреле."
			),
			new DailyBriefService.Candidate(
				"n3",
				"В Петербурге запустили пилот по электронным пропускам в музеи",
				"https://example.com/museums-spb",
				"FONTANKA",
				now.minusHours(5),
				"В Петербурге запустили пилот по электронным пропускам в музеи."
			),
			new DailyBriefService.Candidate(
				"n4",
				"Регионы получили дополнительное финансирование на ремонт дорог",
				"https://example.com/roads",
				"URA",
				now.minusHours(6),
				"Регионы получили дополнительное финансирование на ремонт дорог."
			)
		);

		final var items = service.selectItems(candidates, 3, "ru", Set.of());

		assertEquals(3, items.size());
		assertEquals(3, items.stream().map(item -> item.media()).distinct().count());
		assertTrue(items.stream().map(item -> item.newsId()).toList().contains("n1"));
		assertTrue(items.stream().map(item -> item.newsId()).toList().contains("n3"));
	}
}
