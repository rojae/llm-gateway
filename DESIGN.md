# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-10-08
- Primary product surfaces: subscription monitoring, API analytics, ingestion, collector administration, audit history.
- Evidence reviewed: `llm-gateway-admin/app/admin/{console,monitoring,model,views}.tsx`, `llm-gateway-admin/app/globals.css`, `docs/remote-monitoring.md`, live admin at localhost:3000, `output/playwright/admin-ux-before.png`.

## Brand
- Personality: calm, practical operations workspace; existing forest green identity.
- Trust signals: distinguish received records, attributed usage, unknown values, and API equivalent cost from subscription billing.
- Avoid: decorative dashboards that displace records, very small grey text, long IDs as the only navigation label, invented data or conversation messages.

## Product goals
- Goals: find an account and session, read individual collected questions, switch between questions without losing context, see receiving problems quickly, estimate observed API-equivalent cost, allocate entered monthly subscription fees to recorded questions.
- Non-goals: Kafka integration, SSE transport, assistant-response capture, changing authentication or claiming subscription usage as an API bill.
- Success signals: account/session context remains visible on desktop, question content reads inline, primary body text is readable, no unauthorized or automatic prompt-content requests.

## Personas and jobs
- Primary personas: operator supervising subscription CLI usage and investigating individual sessions.
- User jobs: identify who owns a session, inspect a question, understand whether usage is attributable, investigate rejected records, review content-access history.
- Key contexts of use: repeated desktop inspection during actual service operation; tablet/mobile follow-up.

## Information architecture
- Primary navigation: subscription monitoring first; API usage and requests together; ingestion, collector keys, and audit under operations.
- Core screens: account rail, session list, session inspector, question reader, separately selected observation events.
- Content hierarchy: receiving summary → account/session selection → question content → usage and technical metadata.
- Parent lists remain available while viewing children. Narrow screens stack panels instead of shrinking text.

## Design principles
- Make the reading task visible: collected question text precedes technical identifiers.
- Preserve context: selected account, selected session, and selected question remain identifiable.
- Keep uncertainty explicit: unknown usage is never rendered as zero; reported observations do not become a subscription bill.
- Tradeoffs: metadata-only navigation labels preserve audited content access; search of loaded account records must say it searches the current page.

## Visual language
- Color: existing green variables, neutral white panels, darker secondary text; amber/red for explicit problems.
- Typography: retain DM Sans / Noto Sans KR; main text 13–14px, metadata at least 11–12px, question prose 14px with comfortable line height.
- Spacing/layout rhythm: compact header/status; 12–20px gaps; lists and reader use the available desktop width.
- Shape/radius/elevation: reuse existing panel/button radius and light borders; minimal elevation.
- Motion: existing limited hover/loading indicators; respect reduced motion.
- Imagery/iconography: existing lucide-react icons only; no new dependencies.

## Components
- Existing components to reuse: Panel, Badge, Empty, existing buttons and pagination.
- New/changed components: compact receiving summary, account rail, readable session labels, inline question reader, question selection list, per-observation token cards, monthly subscription allocation and explicit cost calculation basis.
- Variants and states: selected, loading, empty, error, no content permission, missing content, truncated content, usage uncertainty.
- Token/component ownership: `app/globals.css` for shared tokens and layouts; `monitoring.tsx` for monitoring interactions.

## Accessibility
- Target standard: keyboard operability and readable contrast following WCAG AA principles; this change is not a formal conformance audit.
- Keyboard/focus behavior: native buttons for all selections; visible focus; selection communicated by aria-pressed and text; no new modal for question reading.
- Contrast/readability: improve secondary text; do not encode states with color alone.
- Screen-reader semantics: named navigation, search labels, section headings, explicit content access and error messages.
- Reduced motion and sensory considerations: preserve reduced-motion CSS; polling must not repeatedly announce every count.

## Responsive behavior
- Supported devices: desktop 1440/1280px, tablet 768px, mobile 390px (also check 320px containment).
- Layout adaptations: account rail and session/reader columns on larger screens; stacked full-width panels on smaller screens; wide technical tables scroll inside their panels.
- Touch/hover differences: buttons remain legible with useful hit areas; keyboard and touch do not depend on hover.

## Interaction states
- Loading: show an in-panel status; keep already received records during background polling.
- Empty: state the next relevant action and selected scope.
- Error: retain explicit API failure messages; distinguish old received counts from connection state. Failed cost reads label allocations as unavailable even if a previous report remains cached.
- Success: selection highlights and current scope; last successful UI update displayed near the live controls. Unsaved monthly fee drafts stay scoped to account and month during session/question navigation and metadata polling. Remote edits show a notice and an explicit reload action.
- Disabled: honest pagination boundaries and permissions.
- Offline/slow network: show polling errors; do not imply that historical data confirms a current connection. Deferred metadata selection/manual refresh resumes when a hidden tab becomes visible even if automatic polling is paused; an idle paused view makes no automatic request.

## Content voice
- Tone: short, Korean, direct.
- Terminology: 질문 기록 means collected user prompts; 감사 기록 means management/access actions. No assistant dialogue is invented.
- Microcopy rules: unattributed totals use 질문 연결 안 됨, unknown model rates and missing token fields get specific reasons; show API 환산액 / 청구액 아님; disclose the prompt-only collection boundary in the reader.

## Implementation constraints
- Framework/styling system: existing Next.js, React, TypeScript, shared CSS.
- Design-token constraints: extend current variables and components; no new design system/dependencies.
- Performance constraints: retain visible-only polling and abort/generation protections; content detail is explicit, audited, and never polled or prefetched.
- Compatibility constraints: preserve tenant/auth permission boundaries, collector management, API views, and unknown usage semantics; estimates stay separate from final attributed totals. Model price snapshots carry a version and official source. Monthly fees are user entered, never inferred from login. Allocation uses all retained questions in the owner account for the selected month/time zone; it changes with collection and retention.
- Test/screenshot expectations: typecheck/build, live account → session → question flow, no body request before selection or on polling, responsive screenshots, permission/error/selection-race verification.

## Open questions
- [ ] Assistant-response telemetry support remains a separate capture/verification project; this UI only shows collected prompts.
- [ ] Server-side account/session search can be added when the current page-scoped search is insufficient.
