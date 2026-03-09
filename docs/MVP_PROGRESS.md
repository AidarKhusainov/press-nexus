# MVP Progress Tracker (from `plan.txt`)

Plan source: `plan.txt` (sections `Sprint Goal`, `Definition of Done`, `Epics and Tasks`, `Day-by-Day Plan`, `Go/No-Go`).

Last updated: 2026-03-09

## Status Legend

- `DONE` — implemented in code and available for use.
- `IN_PROGRESS` — partially implemented or missing final validation.
- `TODO` — not started yet.
- `CHECK` — must be confirmed with metrics/manual validation.

## Epic Summary

| Epic | Status | Progress |
|---|---|---|
| A. Ingestion + Clean Data | IN_PROGRESS | Sources/fetch/normalization/dedup are implemented, but duplicate reduction criterion is not confirmed by report |
| B. Daily Brief Engine | IN_PROGRESS | Clustering, ranking, 3-block format, and tone moderation quality gate are implemented; usefulness KPI validation is pending beta data |
| C. User Profile + Delivery | IN_PROGRESS | Scheduled Telegram delivery exists; digest personalization by topics/frequency via `users/user_topics` is enabled; onboarding UX with inline buttons is implemented, KPI validation is pending |
| D. Feedback + Analytics | IN_PROGRESS | Product-report API, daily scheduler, Prometheus snapshot metrics and Grafana dashboard are implemented; KPI filling requires real beta traffic |
| E. Premium Test | TODO | Deferred until beta stabilization: product is temporarily fully free |

## Task Breakdown (A–E)

| Epic | Task from plan | Status | Code evidence |
|---|---|---|---|
| A | Source registry + periodic fetch | DONE | `service/news/platform/*`, `service/scheduler/ScheduledNewsFetchTask.java` |
| A | Article normalization (title/text/source/time/category/url) | DONE | `service/news/NewsPopulateContentService.java`, `service/news/NewsPersistenceService.java` |
| A | Dedup (URL + similarity) | IN_PROGRESS | URL + `content_hash` in DB, near-dup partially via similarity/cluster |
| A | Criterion: -70% duplicates | CHECK | Separate baseline vs current report/metric required |
| B | Event clustering | DONE | `service/news/NewsClusteringService.java`, `cluster*` tables |
| B | must/good ranking | DONE | `service/brief/DailyBriefService.java` (`scoreImportance`) |
| B | 3-block summary | DONE | `service/brief/DailyBriefService.java`, `service/brief/DailyBriefFormatter.java` |
| B | Emotional filter | DONE | Dedicated tone moderation quality gate in `service/brief/BriefToneModerationService.java`, integrated into `service/brief/DailyBriefService.java`; moderation metric `press.brief.tone.moderation` in `observability/AppMetrics.java` |
| B | Criterion: 70% "useful" | CHECK | Feedback collection loop + report required |
| C | Telegram bot: start, topics, frequency | DONE | Webhook `/api/telegram/webhook`, commands `/start`, `/topics`, `/frequency`, `/profile` and inline onboarding buttons for topics/frequency in `service/profile/TelegramOnboardingBotService.java` |
| C | User preference storage | DONE | Personalized build and delivery by `topics`/`digest_frequency`/`last_delivery_at`: `service/profile/UserProfileService.java`, `service/brief/DailyBriefService.java`, `service/delivery/DailyBriefDeliveryService.java` |
| C | Scheduled digest delivery | DONE | `service/delivery/*`, `service/scheduler/ScheduledDailyBriefTask.java` |
| C | Onboarding criterion < 1 min | CHECK | Completion time is now measured via metric `press.onboarding.completion.seconds` and onboarding completion logs; target validation requires real beta data |
| D | Useful/Noise/Anxious buttons | DONE | `service/delivery/DailyBriefDeliveryService.java` (inline keyboard), `service/profile/TelegramOnboardingBotService.java` (callback handler), `service/feedback/FeedbackEventService.java` + `web/FeedbackController.java` (write API to `feedback_events`) |
| D | delivery/click/feedback/unsubscribe events | DONE | `service/delivery/DailyBriefDeliveryService.java` (click/unsubscribe inline callbacks), `service/profile/TelegramOnboardingBotService.java` (`click` URL handoff + `/unsubscribe`/`/stop` + unsubscribe callback), `service/profile/UserProfileService.java` (`digest_enabled` toggle), запись в `feedback_events` через `service/feedback/FeedbackEventService.java` |
| D | D1/D7 + quality dashboard | DONE | Product snapshot gauges in `observability/AppMetrics.java` + scheduler publish in `service/scheduler/ScheduledProductReportTask.java` + dashboard `monitoring/grafana/dashboards/press-nexus-product-analytics.json` |
| D | Daily auto-report | DONE | Scheduler `service/scheduler/ScheduledProductReportTask.java` + formatter `service/analytics/ProductReportFormatter.java` implemented |
| E | Premium screen/message 199/299/399 | TODO | Deferred: paid offers are temporarily not shown to users |
| E | Collect "ready to pay" intent | TODO | Deferred: intent is temporarily not collected during beta |
| E | Intent conversion by segments | TODO | Not implemented |

## Day-by-Day Plan (from `plan.txt`)

| Day | Plan | Status |
|---|---|---|
| 1 | Data model + `sources/raw_articles/users` | IN_PROGRESS |
| 2 | Stable fetch pipeline + scheduler | DONE |
| 3 | Dedup + basic clustering | IN_PROGRESS |
| 4 | Importance scoring | DONE |
| 5 | Card format + digest template | DONE |
| 6 | Emotional filter | DONE |
| 7 | Telegram onboarding + topics | DONE |
| 8 | Telegram delivery job | DONE |
| 9 | Feedback events | DONE |
| 10 | Analytics + daily quality-report | DONE |
| 11 | Closed beta for 20 users | TODO |
| 12 | Noise/ranking adjustments | TODO |
| 13 | Expand to 50–100 users | TODO |
| 14 | Premium intent test + go/no-go | TODO |

## Go/No-Go Metrics

| Metric | Target | Current | Status |
|---|---|---|---|
| D7 retention | `>= 35%` | no data | TODO |
| Useful | `>= 70%` | no data | TODO |
| Noise | `<= 20%` | no data | TODO |
| Premium intent | `>= 10%` | no data | TODO |

## Next Tasks (priority)

1. Fill Go/No-Go metrics from real beta traffic using dashboard `Press Nexus Product Analytics`.
2. Return to premium intent test after beta stabilization.

## How to Update Progress

1. After each feature, update statuses in the tables above.
2. For each `CHECK`, add a link to report/metric (Grafana/SQL/log).
3. Update `Go/No-Go metrics` section daily with actual values.
