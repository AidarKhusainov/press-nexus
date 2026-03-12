package com.nexus.press.app.service.news.platform;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Slf4j
@Component
public class PopularRssFetchProcessor implements NewsFetchProcessor {

	private static final HttpClient FALLBACK_HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(20))
		.followRedirects(HttpClient.Redirect.ALWAYS)
		.build();

	private static final List<FeedDefinition> FEEDS = List.of(
		new FeedDefinition(Media.RIA, "https://ria.ru/export/rss2/index.xml", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NYTIMES, "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml", "en"),
		new FeedDefinition(Media.BBC, "https://feeds.bbci.co.uk/news/world/rss.xml", "en"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.REUTERS, "https://news.google.com/rss/search?q=site:reuters.com/world&hl=en-US&gl=US&ceid=US:en", "en"),
		new FeedDefinition(Media.CNN, "http://rss.cnn.com/rss/edition_world.rss", "en"),
		new FeedDefinition(Media.FOXNEWS, "https://moxie.foxnews.com/google-publisher/world.xml", "en"),
		new FeedDefinition(Media.GUARDIAN, "https://www.theguardian.com/world/rss", "en"),
		new FeedDefinition(Media.NPR, "https://feeds.npr.org/1001/rss.xml", "en"),
//		new FeedDefinition(Media.ALJAZEERA, "https://www.aljazeera.com/xml/rss/all.xml", "en"),
		new FeedDefinition(Media.ABC, "https://abcnews.go.com/abcnews/topstories", "en"),
		new FeedDefinition(Media.CBS, "https://www.cbsnews.com/latest/rss/world", "en"),
		new FeedDefinition(Media.DW, "https://rss.dw.com/rdf/rss-en-top", "en"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.TASS, "https://tass.ru/rss/v2.xml", "ru"),
		new FeedDefinition(Media.RBK, "https://rssexport.rbc.ru/rbcnews/news/30/full.rss", "ru"),
		new FeedDefinition(Media.KOMMERSANT, "https://www.kommersant.ru/RSS/news.xml", "ru"),
		new FeedDefinition(Media.LENTA, "https://lenta.ru/rss/news", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.GAZETA, "https://gazeta.ru/rss", "ru"),
		new FeedDefinition(Media.IZVESTIA, "https://iz.ru/rss", "ru"),
		new FeedDefinition(Media.VEDOMOSTI, "https://www.vedomosti.ru/rss/news", "ru"),
		new FeedDefinition(Media.KP, "https://www.kp.ru/rss/allsections.xml", "ru"),
		new FeedDefinition(Media.AIF, "https://aif.ru/rss/all.php", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.ROSSIYSKAYA_GAZETA, "https://rg.ru/xml/index.xml", "ru"),
		new FeedDefinition(Media.MK, "https://www.mk.ru/rss/index.xml", "ru"),
		new FeedDefinition(Media.FONTANKA, "https://fontanka.ru/rss-feeds/rss.xml", "ru"),
		new FeedDefinition(Media.INTERFAX, "https://www.interfax.ru/rss.asp", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.REGNUM, "https://regnum.ru/rss", "ru"),
		new FeedDefinition(Media.MEDUZA, "https://meduza.io/rss/all", "ru"),
		new FeedDefinition(Media.RT_RUS, "https://russian.rt.com/rss", "ru"),
		new FeedDefinition(Media.VZGLYAD, "https://vz.ru/rss.xml", "ru"),
		new FeedDefinition(Media.BFM, "https://www.bfm.ru/news.rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NTV, "https://ntv.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.REN_TV, "https://ren.tv/export/global/rss.xml", "ru"),
		new FeedDefinition(Media.ZVEZDA, "https://tvzvezda.ru/export/rss.xml", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.FORBES_RU, "https://forbes.ru/rss", "ru"),
		new FeedDefinition(Media.CNEWS, "https://www.cnews.ru/inc/rss/news.xml", "ru"),
		new FeedDefinition(Media.HABR, "https://habr.com/ru/rss/all/all/?fl=ru", "ru"),
		new FeedDefinition(Media.VC, "https://vc.ru/rss/all", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.BANKI_RU, "https://banki.ru/rss", "ru"),
		new FeedDefinition(Media.LIFE, "https://life.ru/xml/feed.xml", "ru"),
		new FeedDefinition(Media.PRAVDA_RU, "https://www.pravda.ru/export.xml", "ru"),
		new FeedDefinition(Media.SNOB, "https://snob.ru/rss/", "ru"),
		new FeedDefinition(Media.NOVAYA_GAZETA, "https://novayagazeta.ru/feed/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.MSK1, "https://msk1.ru/rss", "ru"),
		new FeedDefinition(Media.M24, "https://m24.ru/rss.xml", "ru"),
		new FeedDefinition(Media.VM_RU, "https://vm.ru/rss", "ru"),
		new FeedDefinition(Media.MOSREGTODAY, "https://mosregtoday.ru/rss/", "ru"),
		new FeedDefinition(Media.NEWS_76_RU, "https://76.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.YARNEWS, "https://yarnews.net/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.CHERINFO, "https://cherinfo.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_29_RU, "https://29.ru/rss", "ru"),
		new FeedDefinition(Media.DVINANEWS, "https://dvinanews.ru/rss", "ru"),
		// Disabled temporarily: live test error (timeout) on 2026-03-01.
		// new FeedDefinition(Media.BNKOMI, "https://bnkomi.ru/rss", "ru"),
		new FeedDefinition(Media.PG11_RU, "https://pg11.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.PLN_PSKOV, "https://pln-pskov.ru/rss", "ru"),
		new FeedDefinition(Media.VNNEWS, "https://vnnews.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.STOLICAONEGO, "https://stolicaonego.ru/rss", "ru"),
		new FeedDefinition(Media.ONLINE47, "https://online47.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS47, "https://47news.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.KLOPS, "https://klops.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NEWKALININGRAD, "https://newkaliningrad.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.E1_RU, "https://e1.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_74_RU, "https://74.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_72_RU, "https://72.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_45_RU, "https://45.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_86_RU, "https://86.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.MUKSUN_FM, "https://muksun.fm/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.UGRA_NEWS, "https://ugra-news.ru/rss", "ru"),
		new FeedDefinition(Media.YAMAL_MEDIA, "https://yamal-media.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.SEVER_PRESS, "https://sever-press.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NGS_RU, "https://ngs.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NGS24_RU, "https://ngs24.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NGS42_RU, "https://ngs42.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NGS55_RU, "https://ngs55.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NEWSLAB, "https://newslab.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.SIB_FM, "https://sib.fm/rss", "ru"),
		// Disabled temporarily: live test error (timeout) on 2026-03-01.
		// new FeedDefinition(Media.TAYGA_INFO, "https://tayga.info/rss", "ru"),
		new FeedDefinition(Media.TOMSK_RU, "https://www.tomsk.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.VTOMSKE, "https://vtomske.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.TV2_TODAY, "https://tv2.today/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.OMSKINFORM, "https://omskinform.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.GOROD55, "https://gorod55.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.BK55, "https://bk55.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.IRCITY, "https://ircity.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.IRK_RU, "https://irk.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.BAIKAL24, "https://baikal24.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.CHITA_RU, "https://chita.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.ZAB_RU, "https://zab.ru/rss", "ru"),
		new FeedDefinition(Media.AMIC, "https://amic.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.ALTAPRESS, "https://altapress.ru/rss", "ru"),
		new FeedDefinition(Media.TOLKNEWS, "https://tolknews.ru/rss.xml", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.KATUN24, "https://katun24.ru/rss", "ru"),
		new FeedDefinition(Media.XAKAC_INFO, "https://xakac.info/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.RUSINFO19, "https://19rusinfo.ru/rss", "ru"),
		new FeedDefinition(Media.TUVAONLINE, "https://tuvaonline.ru/rss", "ru"),
		new FeedDefinition(Media.YSIA, "https://ysia.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.YKT_RU, "https://ykt.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.SAKHALIN_INFO, "https://sakhalin.info/rss", "ru"),
		new FeedDefinition(Media.ASTV, "https://astv.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.KAM24, "https://kam24.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.VESTIPK, "https://vestipk.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.MAGADANMEDIA, "https://magadanmedia.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.CHUKOTKAMEDIA, "https://chukotkamedia.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.EAOMEDIA, "https://eaomedia.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.PRIMAMEDIA, "https://primamedia.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.DEITA, "https://deita.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.VL_RU, "https://vl.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.DVNOVOSTI, "https://dvnovosti.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.DVHAB, "https://dvhab.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.VOSTOKMEDIA, "https://vostokmedia.com/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.AMUR_INFO, "https://amur.info/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.PORTAMUR, "https://portamur.ru/rss", "ru"),
		// Disabled temporarily: live test error (timeout) on 2026-03-01.
		// new FeedDefinition(Media.ASN24, "https://asn24.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.AMUR_LIFE, "https://amur.life/rss", "ru"),
		new FeedDefinition(Media.NEWS_93_RU, "https://93.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.YUGA_RU, "https://yuga.ru/rss", "ru"),
		new FeedDefinition(Media.YUGOPOLIS, "https://yugopolis.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.KUBAN24, "https://kuban24.tv/rss", "ru"),
		new FeedDefinition(Media.KUBNEWS, "https://kubnews.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_161_RU, "https://161.ru/rss", "ru"),
		new FeedDefinition(Media.DON24, "https://don24.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.ROSTOV_GAZETA, "https://rostovgazeta.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_63_RU, "https://63.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.SOVA_INFO, "https://sova.info/rss", "ru"),
		new FeedDefinition(Media.NEWS_56_RU, "https://56.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_59_RU, "https://59.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_43_RU, "https://43.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_116_RU, "https://116.ru/rss", "ru"),
		// Disabled temporarily: live test error (timeout) on 2026-03-01.
		// new FeedDefinition(Media.BUSINESS_GAZETA, "https://business-gazeta.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.REALNOE_VREMYA, "https://realnoevremya.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.INKAZAN, "https://inkazan.ru/rss", "ru"),
		new FeedDefinition(Media.KAZANFIRST, "https://kazanfirst.ru/rss", "ru"),
		new FeedDefinition(Media.UFA1, "https://ufa1.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.BASHINFORM, "https://bashinform.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.PROUFU, "https://proufu.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.MKSET, "https://mkset.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.GOROBZOR, "https://gorobzor.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.PENZA_PRESS, "https://penza-press.ru/rss", "ru"),
		new FeedDefinition(Media.ULPRESSA, "https://ulpressa.ru/rss", "ru"),
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.ULPRAVDA, "https://ulpravda.ru/rss", "ru"),
		new FeedDefinition(Media.NEWS_164_RU, "https://164.ru/rss", "ru"),
		new FeedDefinition(Media.V1_RU, "https://v1.ru/rss", "ru")
		// Disabled temporarily: live test failure (rss/content) on 2026-03-01.
		// new FeedDefinition(Media.NEWSVOLGOGRAD, "https://novostivolgograda.ru/rss", "ru")
	);

	private static final DateTimeFormatter ISO_OFFSET_NO_COLON =
		DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH);
	private static final DateTimeFormatter ISO_OFFSET_MILLIS_NO_COLON =
		DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ENGLISH);
	private static final DateTimeFormatter SPACE_OFFSET_NO_COLON =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ", Locale.ENGLISH);

	private final WebClient webClient;
	private final NewsPipelineProperties newsPipelineProperties;

	public PopularRssFetchProcessor(
		final WebClientConfig webClientConfig,
		final NewsPipelineProperties newsPipelineProperties
	) {
		this.webClient = webClientConfig.getWebClient(HttpClientName.NEWS);
		this.newsPipelineProperties = newsPipelineProperties;
	}

	@Override
	public Flux<RawNews> fetchNews() {
		return Flux.fromIterable(feedDefinitions())
			.flatMap(this::fetchFeed, Math.max(1, newsPipelineProperties.getFetchSourceConcurrency()), 1);
	}

	public static List<FeedDefinition> feedDefinitions() {
		return FEEDS;
	}

	Flux<RawNews> fetchFeed(final FeedDefinition feed) {
		log.info("Получение RSS {}: {}", feed.media(), feed.feedUrl());
		final var candidates = buildAllFeedUrlCandidates(feed);
		return Flux.fromIterable(candidates)
			.concatMap(url -> fetchFeedXmlForCandidate(feed, url)
				.flatMap(xml -> Mono.fromCallable(() -> parseFeed(xml, feed))
					.subscribeOn(Schedulers.boundedElastic())
					.map(items -> new CandidateFetchResult(url, items)))
				.onErrorResume(ex -> {
					log.warn("Не удалось получить/распарсить RSS {} через {}: {}", feed.media(), url, ex.getMessage());
					return Mono.empty();
				}))
			.filter(result -> !result.items().isEmpty())
			.next()
			.flatMapMany(result -> {
				log.info("RSS {} успешно прочитан через {} ({} новостей)",
					feed.media(), result.url(), result.items().size());
				return Flux.fromIterable(result.items());
			})
			.switchIfEmpty(Flux.defer(() -> {
				log.warn("Не удалось получить непустой RSS ни по одному URL для {}: {}", feed.media(), candidates);
				return Flux.empty();
			}));
	}

	private Mono<String> fetchFeedXmlForCandidate(final FeedDefinition feed, final String feedUrl) {
		return fetchFeedXmlViaWebClient(feedUrl)
			.onErrorResume(ex -> {
				log.warn("Основной RSS запрос через WebClient не удался для {} ({}): {}. Пробую fallback HTTP client.",
					feed.media(), feedUrl, ex.getMessage());
				return Mono.fromCallable(() -> fetchFeedXmlViaJdk(feedUrl))
					.subscribeOn(Schedulers.boundedElastic());
			});
	}

	private Mono<String> fetchFeedXmlViaWebClient(final String feedUrl) {
		return webClient.get()
			.uri(feedUrl)
			.headers(headers -> {
				headers.set("User-Agent",
					"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
						+ "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
				headers.set("Accept",
					"application/rss+xml,application/atom+xml,application/xml,text/xml,*/*;q=0.8");
				headers.set("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
			})
			.retrieve()
			.bodyToMono(String.class);
	}

	private String fetchFeedXmlViaJdk(final String feedUrl) throws Exception {
		final var request = HttpRequest.newBuilder(URI.create(feedUrl))
			.GET()
			.timeout(Duration.ofSeconds(20))
			.header("User-Agent",
				"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
					+ "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
			.header("Accept", "application/rss+xml,application/atom+xml,application/xml,text/xml,*/*;q=0.8")
			.header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
			.build();

		final var response = FALLBACK_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() >= 400) {
			throw new IllegalStateException("HTTP " + response.statusCode() + " from " + feedUrl);
		}
		return decodeBody(response.body(), response.headers().firstValue("content-type").orElse(""));
	}

	private String decodeBody(final byte[] body, final String contentType) {
		Charset charset = StandardCharsets.UTF_8;
		final var lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
		final var idx = lower.indexOf("charset=");
		if (idx >= 0) {
			final var charsetName = lower.substring(idx + "charset=".length()).split("[;\\s]")[0].trim();
			if (!charsetName.isBlank()) {
				try {
					charset = Charset.forName(charsetName);
				} catch (final Exception ignored) {
					charset = StandardCharsets.UTF_8;
				}
			}
		}
		return new String(body, charset);
	}

	private List<RawNews> parseFeed(final String xml, final FeedDefinition feed) throws Exception {
		final var normalizedXml = normalizeXmlForParsing(xml);
		final var factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		final var builder = factory.newDocumentBuilder();
		final var doc = builder.parse(new InputSource(new StringReader(normalizedXml)));
		final var list = new ArrayList<RawNews>();

		final var items = doc.getElementsByTagName("item");
		if (items.getLength() > 0) {
			for (int i = 0; i < items.getLength(); i++) {
				final var node = items.item(i);
				if (!(node instanceof final Element elem)) continue;
				mapItem(elem, feed).ifPresent(list::add);
			}
			return list;
		}

		final var entries = doc.getElementsByTagName("entry");
		for (int i = 0; i < entries.getLength(); i++) {
			final var node = entries.item(i);
			if (!(node instanceof final Element elem)) continue;
			mapItem(elem, feed).ifPresent(list::add);
		}
		return list;
	}

	private String normalizeXmlForParsing(final String xml) {
		if (!StringUtils.hasText(xml)) return "";
		var normalized = xml
			.replace("\uFEFF", "")
			.replace("\u0000", "")
			.replace("\u200B", "")
			.replace("\u200C", "")
			.replace("\u200D", "");
		final int firstTag = normalized.indexOf('<');
		if (firstTag > 0) {
			normalized = normalized.substring(firstTag);
		}
		normalized = normalized.replaceAll("<(/?)([A-Za-z_][A-Za-z0-9_-]*)\\.([A-Za-z0-9_-]+)", "<$1$2_$3");
		normalized = rewriteUndeclaredPrefixedNames(normalized);
		return normalized.trim();
	}

	private String rewriteUndeclaredPrefixedNames(final String xml) {
		final var declaredPrefixes = new LinkedHashSet<String>();
		final var namespaceDeclPattern = Pattern.compile("xmlns:([A-Za-z][A-Za-z0-9_-]*)\\s*=");
		final var namespaceDeclMatcher = namespaceDeclPattern.matcher(xml);
		while (namespaceDeclMatcher.find()) {
			declaredPrefixes.add(namespaceDeclMatcher.group(1));
		}

		final var elementPattern = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9_-]*):([A-Za-z0-9_.-]+)");
		final var rewrittenElements = elementPattern.matcher(xml).replaceAll(match -> {
			final var prefix = match.group(2);
			if (declaredPrefixes.contains(prefix)) return match.group();
			final var local = match.group(3).replace('.', '_');
			return "<" + match.group(1) + prefix + "_" + local;
		});

		final var attrPattern = Pattern.compile("\\s([A-Za-z][A-Za-z0-9_-]*):([A-Za-z0-9_.-]+)=");
		return attrPattern.matcher(rewrittenElements).replaceAll(match -> {
			final var prefix = match.group(1);
			if ("xmlns".equals(prefix) || "xml".equals(prefix)) return match.group();
			if (declaredPrefixes.contains(prefix)) return match.group();
			final var local = match.group(2).replace('.', '_');
			return " " + prefix + "_" + local + "=";
		});
	}

	private java.util.Optional<RawNews> mapItem(final Element elem, final FeedDefinition feed) {
		final var guid = firstNonBlank(
			textOf(elem, "guid"),
			textOf(elem, "id")
		);

		final var link = firstNonBlank(
			textOf(elem, "link"),
			attrOf(elem, "link", "href")
		);
		if (!StringUtils.hasText(link)) {
			return java.util.Optional.empty();
		}

		final var title = firstNonBlank(textOf(elem, "title"), "Без заголовка");
		final var descriptionRaw = firstNonBlank(
			textOf(elem, "description"),
			textOf(elem, "summary"),
			textOf(elem, "content"),
			textOf(elem, "content:encoded"),
			textOf(elem, "content_encoded")
		);
		final var resolvedLink = resolvePreferredLink(link, descriptionRaw);
		final var description = normalizeText(Jsoup.parse(descriptionRaw).text());
		final var publishedAt = parsePublishedAt(firstNonBlank(
			textOf(elem, "pubDate"),
			textOf(elem, "published"),
			textOf(elem, "updated"),
			textOf(elem, "dc:date")
		));
		final var language = firstNonBlank(textOf(elem, "language"), feed.language());
		final var id = firstNonBlank(guid, resolvedLink, stableId(feed.media(), title, description, publishedAt));

		return java.util.Optional.of(RawNews.builder()
			.id(id)
			.link(resolvedLink)
			.title(normalizeText(title))
			.description(description)
			.source(feed.media())
			.publishedDate(publishedAt)
			.language(language)
			.build());
	}

	private String textOf(final Element elem, final String tag) {
		final var direct = textFromNodeList(elem.getElementsByTagName(tag));
		if (StringUtils.hasText(direct)) return direct;

		final var localName = tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : tag;
		return textFromNodeList(elem.getElementsByTagNameNS("*", localName));
	}

	private String attrOf(final Element elem, final String tag, final String attr) {
		final var nodes = elem.getElementsByTagName(tag);
		for (int i = 0; i < nodes.getLength(); i++) {
			final var node = nodes.item(i);
			if (!(node instanceof final Element child)) continue;
			final var value = child.getAttribute(attr);
			if (StringUtils.hasText(value)) return value.trim();
		}
		return "";
	}

	private String textFromNodeList(final NodeList nodes) {
		if (nodes.getLength() == 0) return "";
		final var value = nodes.item(0).getTextContent();
		return value == null ? "" : value.trim();
	}

	private OffsetDateTime parsePublishedAt(final String raw) {
		if (!StringUtils.hasText(raw)) return OffsetDateTime.now();
		final var value = raw.trim();

		try {
			return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();
		} catch (final DateTimeParseException ignored) {}
		try {
			return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		} catch (final DateTimeParseException ignored) {}
		try {
			return ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME).toOffsetDateTime();
		} catch (final DateTimeParseException ignored) {}
		try {
			return Instant.parse(value).atOffset(ZoneOffset.UTC);
		} catch (final DateTimeParseException ignored) {}
		try {
			return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atOffset(ZoneOffset.UTC);
		} catch (final DateTimeParseException ignored) {}
		try {
			return OffsetDateTime.parse(value, ISO_OFFSET_NO_COLON);
		} catch (final DateTimeParseException ignored) {}
		try {
			return OffsetDateTime.parse(value, ISO_OFFSET_MILLIS_NO_COLON);
		} catch (final DateTimeParseException ignored) {}
		try {
			return OffsetDateTime.parse(value, SPACE_OFFSET_NO_COLON);
		} catch (final DateTimeParseException ignored) {}

		return OffsetDateTime.now();
	}

	private String stableId(final Media media, final String title, final String description, final OffsetDateTime publishedAt) {
		final var key = media.name() + "|" + title + "|" + publishedAt.toInstant() + "|" + description;
		return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
	}

	private String firstNonBlank(final String... values) {
		for (final var value : values) {
			if (StringUtils.hasText(value)) return value.trim();
		}
		return "";
	}

	private String normalizeText(final String value) {
		if (!StringUtils.hasText(value)) return "";
		return value.replace('\u00A0', ' ')
			.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
			.replaceAll("\n{3,}", "\n\n")
			.strip();
	}

	private String resolvePreferredLink(final String link, final String descriptionRaw) {
		if (!StringUtils.hasText(link)) return "";
		if (!isGoogleNewsLink(link)) return link.trim();

		if (StringUtils.hasText(descriptionRaw)) {
			final var doc = Jsoup.parse(descriptionRaw);
			for (final var a : doc.select("a[href]")) {
				final var href = a.attr("href");
				if (!StringUtils.hasText(href)) continue;
				if (isGoogleNewsLink(href)) continue;
				return href.trim();
			}
		}
		return link.trim();
	}

	private boolean isGoogleNewsLink(final String url) {
		try {
			final var host = URI.create(url).getHost();
			return host != null && host.toLowerCase(Locale.ROOT).contains("news.google.");
		} catch (final Exception e) {
			return false;
		}
	}

	private List<String> buildFeedUrlCandidates(final String feedUrl) {
		final var candidates = new LinkedHashSet<String>();
		candidates.add(feedUrl);

		try {
			final var base = URI.create(feedUrl);
			final var host = base.getHost();
			final boolean localHost = isLocalHost(host);
			final var hostVariants = new LinkedHashSet<String>();
			if (StringUtils.hasText(host)) {
				hostVariants.add(host);
				if (!localHost) {
					if (host.startsWith("www.")) hostVariants.add(host.substring(4));
					else hostVariants.add("www." + host);
				}
			}

			final var path = base.getPath() == null ? "" : base.getPath();
			final var pathVariants = new LinkedHashSet<String>();
			pathVariants.add(path);
			if (path.endsWith("/rss")) {
				pathVariants.add(path + ".xml");
				pathVariants.add(path + "/index.xml");
			}
			if (path.endsWith("/rss/")) {
				pathVariants.add(path + "index.xml");
				pathVariants.add(path.substring(0, path.length() - 1) + ".xml");
			}
			if (path.endsWith("/rss.xml")) {
				pathVariants.add(path.substring(0, path.length() - 4));
				pathVariants.add(path.substring(0, path.length() - 4) + "/index.xml");
			}
			if (path.endsWith("/feed")) {
				pathVariants.add(path + ".xml");
			}

			for (final var variantHost : hostVariants) {
				for (final var variantPath : pathVariants) {
					if (localHost) {
						final var sameSchemeVariant = rewriteUri(base, base.getScheme(), variantHost, variantPath);
						if (StringUtils.hasText(sameSchemeVariant)) candidates.add(sameSchemeVariant);
						continue;
					}
					final var httpsVariant = rewriteUri(base, "https", variantHost, variantPath);
					if (StringUtils.hasText(httpsVariant)) candidates.add(httpsVariant);
					final var httpVariant = rewriteUri(base, "http", variantHost, variantPath);
					if (StringUtils.hasText(httpVariant)) candidates.add(httpVariant);
				}
			}
		} catch (final Exception ignored) {}

		return List.copyOf(candidates);
	}

	private boolean isLocalHost(final String host) {
		if (!StringUtils.hasText(host)) return false;
		final var normalized = host.toLowerCase(Locale.ROOT);
		return "localhost".equals(normalized)
			|| "127.0.0.1".equals(normalized)
			|| "::1".equals(normalized);
	}

	private List<String> buildAllFeedUrlCandidates(final FeedDefinition feed) {
		final var candidates = new LinkedHashSet<String>();
		candidates.addAll(buildFeedUrlCandidates(feed.feedUrl()));
		candidates.addAll(buildGoogleNewsFallbackUrls(feed));
		return List.copyOf(candidates);
	}

	private List<String> buildGoogleNewsFallbackUrls(final FeedDefinition feed) {
		try {
			final var uri = URI.create(feed.feedUrl());
			final var host = uri.getHost();
			if (!StringUtils.hasText(host)) return List.of();
			if (host.contains("news.google.")) return List.of();

			final var siteHost = host.startsWith("www.") ? host.substring(4) : host;
			final var locale = "ru".equalsIgnoreCase(feed.language())
				? "hl=ru&gl=RU&ceid=RU:ru"
				: "hl=en-US&gl=US&ceid=US:en";

			final var candidates = new LinkedHashSet<String>();
			candidates.add(buildGoogleNewsUrl("site:" + siteHost, locale));
			candidates.add(buildGoogleNewsUrl(siteHost, locale));
			candidates.add(buildGoogleNewsUrl("\"" + siteHost + "\"", locale));
			if (!siteHost.equals(host)) {
				candidates.add(buildGoogleNewsUrl(host, locale));
				candidates.add(buildGoogleNewsUrl("\"" + host + "\"", locale));
			}
			return List.copyOf(candidates);
		} catch (final Exception e) {
			return List.of();
		}
	}

	private String buildGoogleNewsUrl(final String query, final String locale) {
		return "https://news.google.com/rss/search?q="
			+ URLEncoder.encode(query, StandardCharsets.UTF_8)
			+ "&" + locale;
	}

	private String rewriteUri(final URI base, final String scheme, final String host, final String path) {
		try {
			return new URI(
				scheme,
				base.getUserInfo(),
				host,
				base.getPort(),
				path,
				base.getQuery(),
				base.getFragment()
			).toString();
		} catch (final Exception e) {
			return "";
		}
	}

	private record CandidateFetchResult(String url, List<RawNews> items) {}

	public record FeedDefinition(Media media, String feedUrl, String language) {}
}
