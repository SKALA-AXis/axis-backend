# AXIS Frontend-Backend Alignment

Updated: 2026-05-11 KST
Source of truth: `../axis-infra/api/openapi.yaml`

## 1. Frontend Analysis

The frontend is a Vite/React single-page app. It does not use URL routing; `src/app/App.tsx` stores `activeView` in React state and switches pages in `renderView()`.

| UI state | Component | Main data needed | Current backend status |
|---|---|---|---|
| unauthenticated | `AuthScreen` | login/signup/current user | UI only; backend has `/api/auth/**` |
| `home` | `HomeDashboardView` | dashboard summary, today cards | `/dashboard` compatibility + `/api/dashboard/summary` |
| `briefings` | `BriefingsView` | briefing workspace, source cards, share/print | `/briefings` compatibility + `/api/briefings/summary` |
| `insight` | `InsightResultView` | latest insight, evidence cards | `/api/insights/latest`, `/api/insights/generate` |
| `peerPlus` | `PeerPlusView` | peer summary, IR profile, keywords, related cards | `/peers` compatibility + `/api/peers`, `/api/peers/{peerId}/profile` |
| `issues` | `CardNewsWorkspaceView` | card news list/search/filter, detail, bookmark/share | `/api/cards/**`, `/issues` compatibility, `/api/issues/**` |
| `mixer` | `MixerView` | mixer options, selectable cards, run result | `/api/mixer/options`, `/api/mixer`, `/api/mixer/{mixId}/share` |
| `keywordGraph` | `KeywordGraphView` | graph nodes/edges, related cards | `/api/keyword-graph`, `/api/keyword-graph/{nodeId}/cards` |
| `rawArticles` | `RawArticlesView` | raw article archive and selected cards | `/raw-articles` compatibility + `/api/raw-articles`; currently not reachable from nav |
| `settings` | `SettingsView` | profile, notifications, view prefs, access logs | `/api/settings/**` |
| `admin` | `AdminView` | peers, sources, prompts, scheduler, usage, audit | `/api/admin/**`; hidden because role is hardcoded to `strategist` |
| global | `TopNav`, `FloatingAiChat` | search, notifications, assistant chat | canonical APIs added; UI still uses local mocks/state |

### Current API Client Shape

`src/shared/api/httpClient.ts` unwraps `{ success, data, timestamp }`. If `VITE_API_BASE_URL` is absent, repositories fall back to mocks.

Current repository paths:

| Repository | Frontend path | Canonical OpenAPI path | Note |
|---|---|---|---|
| dashboard | `/dashboard` | `/api/dashboard/summary` | backend supports both |
| briefings | `/briefings` | `/api/briefings/summary` | backend supports both |
| alerts | `/alerts` | `/api/alerts` | backend supports both |
| peers | `/peers` | `/api/peers` | backend supports both |
| raw articles | `/raw-articles` | `/api/raw-articles` | backend supports both |
| issues | `/issues` | `/api/issues` | backend supports both |
| card news | `/api/cards`, `/api/cards/today` | same | aligned |

### Remaining Mock Or Local-State Areas

- `AuthScreen` writes only `sessionStorage`; it does not call `/api/auth/login`, `/api/auth/signup`, or `/api/auth/me`.
- `TopNav` notifications and search suggestions still use `shared/mocks/notifications.ts` and `shared/mocks/peerPlus.ts`.
- `FloatingAiChat` still creates canned assistant responses locally instead of calling `/api/assistant/chat`.
- `MixerView` still calculates result in React state from `mockMixerConfig`; it does not call `/api/mixer/options` or `/api/mixer`.
- `KeywordGraphView`, `InsightResultView`, and Peer+ IR detail still read local mock modules for parts of their advanced visualization data.
- `SettingsView` profile, notification toggles, password change, and access logs are local state/mock rows.
- `AdminView` is entirely mock-backed and is not reachable unless `currentUserRole` changes to `admin`.
- Bookmarks are stored in `localStorage`, not `/api/bookmarks`.

### Browser Flow Check

Opened with `VITE_API_BASE_URL=http://localhost:8080`:

- Login screen: visible and form submit enters app. The current form does not require backend auth.
- Home: dashboard and card-news data rendered.
- Briefings: daily briefing view, date/period controls, share/print entry rendered.
- Insight: flow-step board and evidence area rendered.
- Peer+: peer selector, comparison board, IR metric area rendered.
- Card news: filters, search input, card list, detail entry buttons rendered.
- Mixer: selectable cards rendered; selecting 2 cards enabled mixer result.
- Keyword graph: category filters, graph controls, detail panel rendered.
- Settings: account/profile tabs and notification settings entry rendered.
- TopNav notifications/search: opened successfully, but confirmed local mock/state usage.
- Floating AI chat: opened and generated local report preview response.

Not opened through normal navigation:

- `RawArticlesView`: component exists but the current navigation maps `rawArticles`/`matching` to `mixer`.
- `AdminView`: component exists but hidden because `currentUserRole` is hardcoded as `strategist`.

## 2. Backend Analysis

The backend is Spring Boot 3.3.4 / Java 17 / Gradle with REST controllers under `com.skala.axis.controller`.

Current structure:

- Controllers: auth, cards, monitoring, briefings, alerts, bookmarks, mixer, settings, admin, pipeline, images, issues, peers, search.
- Data: JPA entities for `PeerCompany`, `RawArticle`, `IssueCard`, `ArticleImage`.
- Fixtures: `src/main/resources/contract-fixtures.json` loaded through `ApiContractFixtureService`.
- Response wrapper: `ApiResponse.success(data)` -> `{ success, data, timestamp }`.
- Error wrapper: `GlobalExceptionHandler`.
- External AI delegation: `AiClientService` exists, but frontend contract endpoints currently use contract fixtures where implementation is not ready.
- CORS: `axis.cors.allowed-origins`, defaulting to localhost frontend ports.

### OpenAPI Additions In The Updated Spec

The updated `openapi.yaml` promotes several frontend-first contracts:

- Dashboard: `/api/dashboard/summary`
- Global search: `/api/search`, `/api/search/suggestions`
- Peer+: `/api/peers`, `/api/peers/{peerId}/profile`
- Deprecated issue summary: `/api/issues`, `/api/issues/{id}`
- Insights: `/api/insights/latest`, `/api/insights/generate`
- Keyword graph: `/api/keyword-graph`, `/api/keyword-graph/{nodeId}/cards`
- Briefing workspace/share: `/api/briefings/summary`, `/api/briefings/{briefingId}/share`
- Alerts CRUD: `/api/alerts`, `/api/alerts/rules`, `/api/alerts/rules/{ruleId}`
- TopNav notifications: `/api/notifications`, `/api/notifications/{id}/read`
- Mixer options: `/api/mixer/options`
- Raw articles: `/api/raw-articles`, `/api/raw-articles/{id}`
- Floating chat: `/api/assistant/chat`
- Settings additions: `GET /api/settings/profile`, `/api/settings/view-preferences`

## 3. API Specification Implemented In This Slice

```text
[Dashboard Summary]
Method: GET
URL: /api/dashboard/summary
Domain: dashboard
Description: HomeDashboardView data promoted from /dashboard.
Request Params: date, peer_id
Request Body: none
Response Body: DashboardSummary
Error Cases: 400, 401, 403, 500
Frontend Usage: Home dashboard repository target after cleanup.

[Global Search]
Method: POST
URL: /api/search
Domain: dashboard/search
Description: TopNav submit and search fallback result.
Request Params: none
Request Body: { query, scopes, limit }
Response Body: GlobalSearchResponse
Error Cases: 400, 401, 403, 422, 503, 504, 500
Frontend Usage: TopNav search panel and card-news fallback search.

[Search Suggestions]
Method: GET
URL: /api/search/suggestions
Domain: dashboard/search
Description: Search panel suggestions and immediate preview.
Request Params: q, limit
Request Body: none
Response Body: GlobalSearchResponse
Error Cases: 400, 401, 403, 500
Frontend Usage: TopNav autocomplete.

[Peer+ Summary]
Method: GET
URL: /api/peers
Domain: peers
Description: Peer+ list, period labels, descriptions, and analysis text.
Request Params: q, priority, active_only
Request Body: none
Response Body: PeerPlusData
Error Cases: 400, 401, 403, 500
Frontend Usage: PeerPlusView.

[Peer+ Profile]
Method: GET
URL: /api/peers/{peerId}/profile
Domain: peers
Description: Peer profile, IR pack, keyword cloud, and related cards.
Request Params: period_unit, period_value, include_cards
Request Body: none
Response Body: PeerPlusProfile
Error Cases: 400, 401, 403, 404, 500
Frontend Usage: Peer selection detail panel.

[Insights Latest]
Method: GET
URL: /api/insights/latest
Domain: insights
Description: Latest insight result and evidence card ids.
Request Params: date, peer_id, limit_evidence_cards
Request Body: none
Response Body: InsightResult
Error Cases: 400, 401, 403, 404, 500
Frontend Usage: InsightResultView.

[Insights Generate]
Method: POST
URL: /api/insights/generate
Domain: insights
Description: Accept insight generation job request.
Request Params: none
Request Body: { card_ids, filters }
Response Body: AsyncJobAccepted
Error Cases: 400, 401, 403, 422, 503, 504, 500
Frontend Usage: future selected-card insight generation.

[Keyword Graph]
Method: GET
URL: /api/keyword-graph
Domain: keyword-graph
Description: Graph nodes, edges, selected node, trend data.
Request Params: category, date_from, date_to, min_weight
Request Body: none
Response Body: KeywordGraph
Error Cases: 400, 401, 403, 500
Frontend Usage: KeywordGraphView.

[Keyword Graph Node Cards]
Method: GET
URL: /api/keyword-graph/{nodeId}/cards
Domain: keyword-graph/cards
Description: Related card news for a selected graph node.
Request Params: limit, offset
Request Body: none
Response Body: CardListResponse
Error Cases: 400, 401, 403, 404, 500
Frontend Usage: graph overlay/detail card list.

[Briefing Workspace]
Method: GET
URL: /api/briefings/summary
Domain: briefings
Description: Visual/text briefing workspace by period.
Request Params: briefing_type, date, month, week_index, peer_id, include_cards
Request Body: none
Response Body: BriefingWorkspace
Error Cases: 400, 401, 403, 404, 500
Frontend Usage: BriefingsView period selector.

[Briefing Share]
Method: POST
URL: /api/briefings/{briefingId}/share
Domain: briefings
Description: Creates internal briefing share link.
Request Params: none
Request Body: { expires_in_hours, include_source_cards }
Response Body: ShareLink
Error Cases: 400, 401, 403, 404, 500
Frontend Usage: share/print preview flow.

[Alerts Data]
Method: GET
URL: /api/alerts
Domain: alerts
Description: Alert rules, history, condition options, channel options.
Request Params: status, limit, offset
Request Body: none
Response Body: AlertsData
Error Cases: 400, 401, 403, 500
Frontend Usage: AlertsView.

[Alert Rule Create]
Method: POST
URL: /api/alerts/rules
Domain: alerts
Description: Create alert rule.
Request Params: none
Request Body: AlertRuleUpsertRequest
Response Body: AlertRule
Error Cases: 400, 401, 403, 422, 500
Frontend Usage: AlertsView rule editor.

[Alert Rule Update/Delete]
Method: PUT/DELETE
URL: /api/alerts/rules/{ruleId}
Domain: alerts
Description: Update or delete alert rule.
Request Params: ruleId
Request Body: AlertRuleUpsertRequest for PUT
Response Body: AlertRule or deletion result
Error Cases: 400, 401, 403, 404, 422, 500
Frontend Usage: AlertsView rule editor.

[Notifications]
Method: GET/DELETE
URL: /api/notifications
Domain: notifications
Description: TopNav in-app notification list or clear all.
Request Params: unread_only, limit
Request Body: none
Response Body: NotificationList or deletion result
Error Cases: 401, 403, 500
Frontend Usage: TopNav notification dropdown.

[Notification Read]
Method: POST
URL: /api/notifications/{id}/read
Domain: notifications
Description: Mark one notification as read.
Request Params: id
Request Body: none
Response Body: { id, read }
Error Cases: 401, 403, 404, 500
Frontend Usage: TopNav notification item click.

[Mixer Options]
Method: GET
URL: /api/mixer/options
Domain: mixer
Description: Default selections and option chip groups.
Request Params: none
Request Body: none
Response Body: MixerOptions
Error Cases: 401, 403, 500
Frontend Usage: MixerView initial state.

[Raw Articles]
Method: GET
URL: /api/raw-articles
Domain: raw-articles
Description: Raw article archive metadata.
Request Params: peer_id, importance_level, processing_status, q, date_from, date_to, sort, limit, offset
Request Body: none
Response Body: RawArticleListResponse
Error Cases: 400, 401, 403, 500
Frontend Usage: RawArticlesView.

[Assistant Chat]
Method: POST
URL: /api/assistant/chat
Domain: assistant
Description: Floating AI chat message and optional report draft.
Request Params: none
Request Body: { conversation_id, message, context }
Response Body: AssistantChatResponse
Error Cases: 400, 401, 403, 422, 503, 504, 500
Frontend Usage: FloatingAiChat.

[View Preferences]
Method: GET/PUT
URL: /api/settings/view-preferences
Domain: settings
Description: Default content view mode.
Request Params: none
Request Body: { contentViewMode }
Response Body: ViewPreferences
Error Cases: 400, 401, 403, 422, 500
Frontend Usage: SettingsView and content view hooks.
```

Compatibility endpoints still supported for the current frontend repository paths:

```text
GET /dashboard
GET /briefings
GET /alerts
GET /peers
GET /raw-articles
GET /issues
```

## 4. Implementation Summary

- Added canonical controllers for dashboard, insights, keyword graph, notifications, raw articles, and assistant chat.
- Updated search, peers, issues, briefings, alerts, mixer, and settings controllers to match the updated OpenAPI response shapes.
- Expanded `ApiContractFixtureService` with fixture-backed methods for frontend-first domains.
- Expanded `contract-fixtures.json` with dashboard/search/Peer+/notifications/mixer/insight/keyword graph/briefing workspace/raw article/chat/view preference samples.
- Kept fixture samples in resource JSON, not hardcoded as large Java objects.
- Updated contract smoke tests to include the new OpenAPI paths.
- Updated frontend compatibility smoke tests to cover canonical frontend paths and UI-critical shapes.

## 5. Verification Notes

Backend tests:

```text
./gradlew test
./gradlew test --tests com.skala.axis.OpenApiContractSmokeTests
```

Frontend build:

```text
npm run build
```

Manual API checks while backend was running:

```text
GET  /api/dashboard/summary
GET  /api/mixer/options
GET  /api/raw-articles
POST /api/assistant/chat
```

Local boot note:

- Default local PostgreSQL was reachable.
- Normal `bootRun` failed because the local DB has Flyway checksum/schema drift (`V1` checksum mismatch and missing `issue_cards.company`).
- Browser validation used a non-mutating dev run with `SPRING_FLYWAY_ENABLED=false` and `SPRING_JPA_HIBERNATE_DDL_AUTO=none`.
- No destructive DB repair was performed.

## 6. Remaining Work Requiring Frontend Edits

This repository's instruction says files outside `axis-backend` require prior approval before modification. The backend now exposes the needed contracts, but these frontend files still need a follow-up edit in `axis-frontend`:

- `TopNav.tsx`: replace local notification/search mocks with `/api/notifications` and `/api/search/suggestions`.
- `FloatingAiChat.tsx`: call `/api/assistant/chat`.
- `AxisPlanningViews.tsx`: connect Mixer, Insight, Peer+ detail, and KeywordGraph to the canonical APIs.
- `SettingsView.tsx`: connect profile, notifications, password, view preferences, and access logs.
- `AdminView.tsx`: connect admin tables to `/api/admin/**` and expose admin role through auth.
- Repositories: migrate compatibility paths (`/dashboard`, `/briefings`, `/alerts`, `/peers`, `/raw-articles`, `/issues`) to canonical `/api/**` paths when frontend approval is granted.
