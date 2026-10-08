export type View = 'overview' | 'requests' | 'monitoring' | 'ingestion' | 'collectors' | 'audit';
export type Filters = {
  source: string; from: string; to: string; zone: string; traffic: string;
  keyId: string; model: string; sessionId: string; billingMode: string; provider: string; outcome: string;
};
export type Session = {
  authenticated: boolean; tenantId?: string; subject?: string; user?: { subject?: string }; roles?: string[];
  permissions?: string[]; csrfToken?: string; mode?: string;
};
export type Stats = {
  requests?: number; errors?: number; cancelled?: number; totalTokens?: number;
  inputTokens?: number; outputTokens?: number; cacheWriteTokens?: number; cacheReadTokens?: number;
  p95TotalMs?: number; costUsd?: number | null; apiEquivalentCostUsd?: number | null;
  costComplete?: boolean; equivalentCostComplete?: boolean; unpricedRequests?: number;
  missingUsageRequests?: number; partialUsageRequests?: number; missingEquivalentCostRequests?: number;
  source?: string; billingMode?: string; model?: string; provider?: string;
};
export type Overview = { totals: Stats; bySource?: Stats[]; byModel?: Stats[]; byDay?: { day: string; requests: number }[]; byModelTruncated?: boolean };
export type RequestItem = {
  requestId: string; startedAt?: string; timestamp?: string; ts?: string; source?: string; billingMode?: string;
  provider?: string; model?: string; outcome?: string; status?: number; totalTokens?: number;
  costUsd?: number | null; apiEquivalentCostUsd?: number | null; totalMs?: number; ttftMs?: number;
  keyId?: string; sessionId?: string; usageAvailability?: string; querySource?: string; agentType?: string;
  method?: string; path?: string; traffic?: string; stream?: boolean; upstreamRequestId?: string;
  promptTokens?: number; completionTokens?: number; cacheWriteTokens?: number; cacheWrite1hTokens?: number;
  cacheReadTokens?: number; upstreamError?: string; upstreamErrorReason?: string; retryAfterSeconds?: number;
};
export type Content = { text?: string; detail?: string; truncated?: boolean; partial?: boolean } | null;
export type RequestDetail = { request: RequestItem; contents?: { request?: Content; response?: Content } };
export type Paged<T> = { items: T[]; total: number };
export type Ingestion = {
  enabled?: boolean; running?: boolean; status?: string; lastSuccessAt?: string; observedAt?: string;
  rejectedLines?: number; lastError?: string;
  sources?: { sourceId?: string; state?: string; stale?: boolean; observedAt?: string; lastSuccessAt?: string }[];
  files?: { sourceId?: string; fileName?: string; offsetBytes?: number; updatedAt?: string }[];
};
export type Collector = {
  id: string; name: string; prefix?: string; billingMode?: string; createdAt?: string; createdBy?: string;
  expiresAt?: string; lastUsedAt?: string; revokedAt?: string; ownerAccountId?: string; registrationId?: string;
};
export type Audit = { time?: string; actor?: string; action?: string; target?: string; outcome?: string };

export const viewMeta: Record<View, { title: string; description: string; permission: string }> = {
  overview: { title: '사용량 분석', description: '소스별 요청과 토큰, 관측된 비용을 확인합니다.', permission: 'usage:read' },
  requests: { title: '요청 기록', description: '요청의 결과와 응답 내용을 추적합니다.', permission: 'usage:read' },
  monitoring: { title: '구독 모니터링', description: '누가 어떤 세션에서 무엇을 질문했는지 확인합니다.', permission: 'usage:read' },
  ingestion: { title: '적재 상태', description: '데이터 수집 상태와 읽기 위치를 확인합니다.', permission: 'ingestion:read' },
  collectors: { title: '수집 키 관리', description: '조직의 텔레메트리 수집 키를 관리합니다.', permission: 'collectors:manage' },
  audit: { title: '감사 기록', description: '관리 작업과 접근 기록을 확인합니다.', permission: 'audit:read' },
};

export const number = (v: unknown) => v == null ? '—' : Number(v).toLocaleString('ko-KR');
export const usd = (v: unknown) => v == null ? '미확인' : '$' + Number(v).toLocaleString('en-US', { minimumFractionDigits: 6, maximumFractionDigits: 6 });
export const duration = (v: unknown) => v == null ? '—' : Number(v) >= 1000 ? (Number(v) / 1000).toFixed(2) + ' s' : Number(v).toFixed(1) + ' ms';
export const display = (v: unknown) => v == null || v === '' ? '—' : String(v);
export const time = (v: unknown, zone = 'Asia/Seoul') => {
  if (!v) return '—';
  const d = new Date(String(v));
  if (Number.isNaN(d.getTime())) return '—';
  try { return new Intl.DateTimeFormat('ko-KR', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(d); }
  catch { return '—'; }
};
export const sourceLabel = (source?: string) => source === 'claude_code' ? 'Claude Code' : source === 'codex' ? 'Codex' : source === 'gateway' || !source ? 'Gateway' : source;
export const billingLabel = (mode?: string) => ({ api_key: 'API 키', subscription_declared: '구독 · 자기 신고', api_key_declared: 'API 키 · 자기 신고', unknown: '미확인' }[mode || 'unknown'] || '미확인');
export const outcomeLabel = (outcome?: string) => ({ success: '성공', http_error: 'HTTP 오류', upstream_error: '업스트림 오류', cancelled: '취소' }[outcome || ''] || display(outcome));
export const costIncomplete = (s: Stats) => s.costComplete === false || Number(s.unpricedRequests || 0) + Number(s.missingUsageRequests || 0) + Number(s.partialUsageRequests || 0) > 0;
export const equivalentIncomplete = (s: Stats) => s.equivalentCostComplete === false || Number(s.missingEquivalentCostRequests || 0) > 0;
export const today = () => {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (name: string) => parts.find(p => p.type === name)?.value || '';
  return `${part('year')}-${part('month')}-${part('day')}`;
};
export const defaultFilters = (): Filters => ({ source: 'gateway', from: today(), to: today(), zone: 'Asia/Seoul', traffic: 'live', keyId: '', model: '', sessionId: '', billingMode: '', provider: '', outcome: '' });
export function validateFilters(filters: Filters): string | null {
  if (!filters.from || !filters.to || filters.from > filters.to) return '시작일과 종료일을 올바르게 지정하세요.';
  const days = (Date.parse(filters.to) - Date.parse(filters.from)) / 86400000 + 1;
  if (!Number.isFinite(days) || days > 366) return '조회 기간은 최대 366일입니다.';
  try { new Intl.DateTimeFormat('ko-KR', { timeZone: filters.zone }).format(); }
  catch { return '유효한 IANA 시간대를 입력하세요. 예: Asia/Seoul'; }
  return null;
}
export const query = (filters: Filters) => new URLSearchParams(Object.entries(filters).filter(([, value]) => value));

export type MonitoringAccount = { id: string; name: string; createdAt?: string; sessionCount?: number; questionCount?: number; lastReceivedAt?: string };
export type MonitoringSession = { id: string; accountId: string; source: string; externalSessionId?: string; cliVersion?: string; providerAccountId?: string; providerAccountStatus?: string; firstSeenAt?: string; lastSeenAt?: string; questionCount?: number; totalTokens?: number | null; apiEquivalentCostUsd?: number | null; usageAvailability?: string; unattributedEvents?: number; responseRevision?: string };
export type MonitoringQuestion = { id: string; externalPromptId?: string; source: string; startedAt?: string; model?: string; inputTokens?: number | null; outputTokens?: number | null; cacheReadTokens?: number | null; cacheWriteTokens?: number | null; totalTokens?: number | null; apiEquivalentCostUsd?: number | null; usageAvailability?: string; attribution?: string; coverage?: string; callCount?: number; responseRevision?: string };
export type ResponseAvailability = 'available' | 'not_collected' | 'permission_required' | 'purged';
export type MonitoringResponse = { id: string; questionId?: string | null; source: string; emittedAt?: string; sequence?: number | null; model?: string | null; attribution: string; content: { text: string; truncated: boolean } | null; contentAvailability: ResponseAvailability };
export type MonitoringDetail = { question: MonitoringQuestion; content: { text: string; truncated?: boolean } | null; contentAvailability?: string; responses?: MonitoringResponse[]; responseAvailability?: ResponseAvailability };
export type ApiEstimate = { amountUsd: number | null; availability: string; basis?: string | null; model?: string | null; priceVersion?: string | null; sourceUrl?: string | null; longContext?: boolean; ordinaryInputTokens?: number | null; inputUsdPerMillion?: number | null; cacheReadUsdPerMillion?: number | null; cacheWriteUsdPerMillion?: number | null; outputUsdPerMillion?: number | null; assumptions?: string[] };
export type MonitoringEvent = { id: string; eventKind: string; emittedAt?: string; source: string; model?: string; inputTokens?: number | null; outputTokens?: number | null; cacheReadTokens?: number | null; cacheWriteTokens?: number | null; scope?: string; semantics?: string; attribution?: string; apiEstimate?: ApiEstimate };
export type MonitoringCosts = { month: string; zone: string; subscription: { amount: number | null; currency: string; allocationBasis: string; questionCount: number; sessionCount: number; perQuestion: number | null }; session: { id: string; questionCount: number; subscriptionAllocation: number | null; latestObservation: MonitoringEvent | null } | null; question: { id: string; inMonth: boolean; subscriptionAllocation: number | null } | null };
export type MonitoringStatus = { ready?: boolean; lastReceivedAt?: string; stored?: number; duplicates?: number; rejected?: number; unsupported?: number; unattributed?: number };
