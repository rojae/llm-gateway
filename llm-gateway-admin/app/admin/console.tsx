'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, ArrowRight, ChevronDown, ClipboardCopy, Database, KeyRound, LayoutDashboard, LogOut, Menu, RefreshCw, Search, ShieldCheck, X } from 'lucide-react';
import type { Audit, Collector, Filters, Ingestion, Overview, Paged, RequestDetail, RequestItem, Session, View } from './model';
import { defaultFilters, query, time, validateFilters, viewMeta } from './model';
import { CollectorOwners, CollectorSetup, MonitoringView } from './monitoring';
import { AuditView, CollectorsView, DetailView, IngestionView, OverviewView, RequestsView } from './views';

type Mode = 'loading' | 'local' | 'enterprise';
type Dataset = Overview | Paged<RequestItem> | Ingestion | Paged<Collector> | Paged<Audit>;
const navigation: { view: View; icon: typeof Activity; group: string }[] = [
  { view: 'monitoring', icon: Activity, group: '관측 · 분석' },
  { view: 'overview', icon: LayoutDashboard, group: '관측 · 분석' }, { view: 'requests', icon: Search, group: '관측 · 분석' },
  { view: 'ingestion', icon: Database, group: '운영 관리' }, { view: 'collectors', icon: KeyRound, group: '운영 관리' }, { view: 'audit', icon: ShieldCheck, group: '운영 관리' },
];
function initialView(session: Session): View { return navigation.find(item => session.permissions?.includes(viewMeta[item.view].permission))?.view || 'overview'; }
const messages: Record<number, string> = {
  400: '필터 값을 확인하세요. 날짜 범위와 정확한 ID를 사용해야 합니다.',
  401: '인증이 만료되었거나 접속 키가 올바르지 않습니다.',
  403: '이 요청에 접근할 권한이 없습니다.',
  404: '요청 기록을 찾을 수 없습니다.',
  503: '분석 저장소에 연결할 수 없습니다. API와 DB 상태를 확인하세요.',
};

function Brand({ dark = false }: { dark?: boolean }) {
  return <div className={`brand ${dark ? 'dark' : ''}`}><span className="brand-symbol"><Activity size={22} strokeWidth={2.3} /></span><span className="brand-name">llm<span>gateway</span><small>OPERATIONS CONSOLE</small></span></div>;
}

export default function Console() {
  const [mode, setMode] = useState<Mode>('loading');
  const [session, setSession] = useState<Session | null>(null);
  const [token, setToken] = useState('');
  const [tokenInput, setTokenInput] = useState('');
  const [loginUrl, setLoginUrl] = useState('/oauth2/authorization/enterprise');
  const [loginError, setLoginError] = useState('');
  const [loginBusy, setLoginBusy] = useState(false);
  const [view, setView] = useState<View>('monitoring');
  const [draft, setDraft] = useState<Filters>(defaultFilters);
  const [filters, setFilters] = useState<Filters>(defaultFilters);
  const [offset, setOffset] = useState(0);
  const [data, setData] = useState<Dataset | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [updated, setUpdated] = useState('');
  const [revision, setRevision] = useState(0);
  const [detail, setDetail] = useState<RequestDetail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [issuedToken, setIssuedToken] = useState('');
  const [mobileNav, setMobileNav] = useState(false);
  const loadAbort = useRef<AbortController | null>(null);
  const mutationAbort = useRef<AbortController | null>(null);
  const authGeneration = useRef(0);
  const detailAbort = useRef<AbortController | null>(null);

  const api = useCallback(async <T,>(path: string, options?: { method?: string; body?: unknown; overrideToken?: string; signal?: AbortSignal }): Promise<T> => {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (mode === 'local') headers.Authorization = `Bearer ${options?.overrideToken ?? token}`;
    if (options?.body !== undefined) headers['Content-Type'] = 'application/json';
    if (mode === 'enterprise' && options?.method && options.method !== 'GET') headers['X-CSRF-TOKEN'] = session?.csrfToken || '';
    let response: Response;
    try {
      response = await fetch(`/admin/api/${path}`, {
        method: options?.method || 'GET', headers,
        body: options?.body === undefined ? undefined : JSON.stringify(options.body),
        credentials: mode === 'enterprise' ? 'same-origin' : 'omit', cache: 'no-store', redirect: 'error', signal: options?.signal,
      });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw e;
      throw new Error('API 서버에 연결할 수 없습니다. 게이트웨이와 화면 서버의 실행 상태를 확인하세요.');
    }
    if (!response.ok) throw new Error(messages[response.status] || `요청에 실패했습니다 (HTTP ${response.status}).`);
    if (response.status === 204) return null as T;
    try { return await response.json() as T; }
    catch { throw new Error('API 응답을 해석할 수 없습니다.'); }
  }, [mode, token, session?.csrfToken]);

  useEffect(() => {
    let active = true;
    if (new URLSearchParams(window.location.search).get('login') === 'failed') {
      setLoginError('회사 계정 로그인에 실패했습니다. 다시 시도하거나 관리자에게 문의하세요.');
      window.history.replaceState({}, '', window.location.pathname);
    }
    (async () => {
      try {
        const response = await fetch('/admin/auth/config', { cache: 'no-store', credentials: 'same-origin', redirect: 'error' });
        if (!response.ok) throw new Error('인증 설정을 읽지 못했습니다.');
        const config = await response.json() as { mode?: string; loginUrl?: string };
        if (!active) return;
        const next = config.mode === 'enterprise' ? 'enterprise' : 'local';
        setMode(next);
        if (next === 'enterprise') {
          setLoginUrl(config.loginUrl || '/oauth2/authorization/enterprise');
          const sessionResponse = await fetch('/admin/api/session', { cache: 'no-store', credentials: 'same-origin', redirect: 'error' });
          if (!active) return;
          if (sessionResponse.ok) { const result = await sessionResponse.json() as Session; setView(initialView(result)); setSession(result); }
          else if (sessionResponse.status !== 401) setLoginError(messages[sessionResponse.status] || `세션 확인에 실패했습니다 (HTTP ${sessionResponse.status}).`);
        }
      } catch (e) { if (active) { setMode('loading'); setLoginError(e instanceof Error ? e.message : '인증 설정을 읽지 못했습니다.'); } }
    })();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!session) return;
    if (view === 'monitoring') { loadAbort.current?.abort(); setData(null); setLoading(false); return; }
    const controller = new AbortController();
    loadAbort.current?.abort();
    loadAbort.current = controller;
    setLoading(true); setError(''); setNotice(''); setData(null);
    const params = query(filters);
    let path: string;
    if (view === 'requests') { params.set('limit', '25'); params.set('offset', String(offset)); path = `requests?${params}`; }
    else if (view === 'collectors' || view === 'audit') path = `${view}?limit=25&offset=${offset}`;
    else if (view === 'ingestion') path = 'ingestion';
    else path = `overview?${params}`;
    api<Dataset>(path, { signal: controller.signal }).then(result => {
      if (controller.signal.aborted) return;
      setData(result); setUpdated(new Date().toISOString());
    }).catch(e => {
      if (controller.signal.aborted) return;
      if (e instanceof Error && e.message === messages[401]) { clearSession('세션이 만료되었습니다. 다시 로그인하세요.'); return; }
      setError(e instanceof Error ? e.message : '기록을 불러오지 못했습니다.');
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
    // clearSession only handles a 401 and intentionally resets the component state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, session, view, filters, offset, revision]);

  const authIdentity = JSON.stringify([session?.tenantId, session?.subject, session?.user?.subject, [...(session?.roles || [])].sort(), [...(session?.permissions || [])].sort()]);
  const monitoringError = useCallback((error: unknown) => {
    if (error instanceof Error && error.message === messages[401]) clearSession('세션이 만료되었습니다. 다시 로그인하세요.');
  }, []);
  useEffect(() => {
    if (!session) return;
    let active = true;
    let running = false;
    let controller: AbortController | null = null;
    async function checkSession() {
      if (document.hidden || running) return;
      running = true;
      const request = new AbortController(); controller = request;
      try {
        const next = await api<Session>('session', { signal: request.signal });
        if (!active || request.signal.aborted) return;
        if (!next.authenticated) { clearSession('세션이 만료되었습니다. 다시 로그인하세요.'); return; }
        const nextIdentity = JSON.stringify([next.tenantId, next.subject, next.user?.subject, [...(next.roles || [])].sort(), [...(next.permissions || [])].sort()]);
        if (nextIdentity !== authIdentity) {
          ++authGeneration.current; detailAbort.current?.abort(); mutationAbort.current?.abort();
          setDetail(null); setDetailError(''); setIssuedToken(''); setData(null); setSession(next);
          if (!next.permissions?.includes(viewMeta[view].permission)) setView(navigation.find(item => next.permissions?.includes(viewMeta[item.view].permission))?.view || 'overview');
        } else if (next.csrfToken !== session?.csrfToken) setSession(next);
      } catch (error) { if (active && !request.signal.aborted) monitoringError(error); }
      finally { if (controller === request) running = false; }
    }
    const visibility = () => { if (document.hidden) { controller?.abort(); running = false; } else void checkSession(); };
    const timer = window.setInterval(() => void checkSession(), 2000);
    document.addEventListener('visibilitychange', visibility);
    return () => { active = false; controller?.abort(); window.clearInterval(timer); document.removeEventListener('visibilitychange', visibility); };
  }, [api, authIdentity, !!session, session?.csrfToken, view, monitoringError]);

  function clearSession(message = '') {
    ++authGeneration.current;
    loadAbort.current?.abort(); detailAbort.current?.abort(); mutationAbort.current?.abort();
    setToken(''); setTokenInput(''); setSession(null); setData(null); setDetail(null); setIssuedToken('');
    setDetailBusy(false); setDetailError(''); setLoading(false);
    setDraft(defaultFilters()); setFilters(defaultFilters()); setView('monitoring'); setOffset(0); setError(''); setNotice(''); setUpdated(''); setLoginError(message);
  }
  function actionError(error: unknown, fallback: string) {
    if (error instanceof Error && error.message === messages[401]) {
      clearSession('세션이 만료되었습니다. 다시 로그인하세요.');
      return;
    }
    setError(error instanceof Error ? error.message : fallback);
  }
  async function signIn(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const candidate = tokenInput.trim();
    if (!candidate) { setLoginError('운영자 토큰을 입력하세요.'); return; }
    setLoginBusy(true); setLoginError('');
    try {
      const result = await api<Session>('session', { overrideToken: candidate });
      if (!result.authenticated) throw new Error('운영자 인증을 확인할 수 없습니다.');
      setToken(candidate); setTokenInput(''); setView(initialView(result)); setSession(result);
    } catch (e) { setLoginError(e instanceof Error ? e.message : '로그인에 실패했습니다.'); }
    finally { setLoginBusy(false); }
  }
  async function logout() {
    const csrfToken = session?.csrfToken || '';
    clearSession();
    if (mode === 'enterprise') {
      try {
        const response = await fetch('/admin/auth/logout', { method: 'POST', headers: { 'X-CSRF-TOKEN': csrfToken }, credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
        if (!response.ok && response.status !== 401) setLoginError('화면의 기록은 지웠으나 서버 세션 종료에 실패했습니다. 다시 로그인 후 로그아웃하세요.');
      } catch { setLoginError('화면의 기록은 지웠으나 서버 세션 종료에 실패했습니다. 다시 로그인 후 로그아웃하세요.'); }
    }
  }
  function changeView(next: View) {
    if (!session?.permissions?.includes(viewMeta[next].permission)) return;
    setView(next); setOffset(0); setMobileNav(false); setData(null); setError(''); setUpdated('');
  }
  function applyFilters(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = validateFilters(draft);
    if (problem) { setNotice(problem); return; }
    setNotice(''); setOffset(0); setFilters({ ...draft });
  }
  function resetFilters() { const next = defaultFilters(); setDraft(next); setFilters(next); setOffset(0); setNotice(''); }
  async function showDetail(id: string) {
    detailAbort.current?.abort();
    const controller = new AbortController(); detailAbort.current = controller;
    setDetail({ request: { requestId: id } }); setDetailBusy(true); setDetailError('');
    try { const result = await api<RequestDetail>(`requests/${encodeURIComponent(id)}`, { signal: controller.signal }); if (!controller.signal.aborted) setDetail(result); }
    catch (e) { if (!controller.signal.aborted) {
      if (e instanceof Error && e.message === messages[401]) clearSession('세션이 만료되었습니다. 다시 로그인하세요.');
      else setDetailError(e instanceof Error ? e.message : '요청 상세를 불러오지 못했습니다.');
    } }
    finally { if (!controller.signal.aborted) setDetailBusy(false); }
  }
  async function createCollector(name: string, days: number, billingMode: string, ownerAccountId: string, registrationId: string): Promise<boolean> {
    if (!name || !ownerAccountId) { setError('수집 키 이름과 소유 계정을 지정하세요.'); return false; }
    const generation = authGeneration.current;
    const controller = new AbortController(); mutationAbort.current?.abort(); mutationAbort.current = controller;
    setError('');
    try {
      const result = await api<{ token?: string }>('collectors', { method: 'POST', body: { name, expiresInDays: days, billingMode, ownerAccountId, ...(registrationId ? { registrationId } : {}) }, signal: controller.signal });
      if (controller.signal.aborted || generation !== authGeneration.current) return false;
      if (!result.token) throw new Error('키 발급 결과를 확인할 수 없습니다.');
      setIssuedToken(result.token); setOffset(0); setRevision(n => n + 1);
      return true;
    } catch (e) { if (!controller.signal.aborted && generation === authGeneration.current) actionError(e, '키 발급에 실패했습니다.'); return false; }
  }
  async function revokeCollector(item: Collector) {
    if (!window.confirm(`수집 키 “${item.name}” (${item.prefix || item.id})를 폐기할까요? 이후 전송은 거부됩니다.`)) return;
    setError('');
    try { await api<null>(`collectors/${encodeURIComponent(item.id)}`, { method: 'DELETE' }); setRevision(n => n + 1); }
    catch (e) { actionError(e, '키 폐기에 실패했습니다.'); }
  }

  if (!session) return <div className="login-layout">
    <aside className="login-art"><Brand /><div className="login-story"><span className="eyebrow light">OPERATE WITH CLARITY</span><h1>모든 요청을,<br /><em>하나의 시야에.</em></h1><p>사용량에서 응답까지.<br />게이트웨이의 기록을 정확하게 읽는 공간입니다.</p><div className="story-pills"><span>USAGE</span><span>REQUESTS</span><span>INGESTION</span></div></div><div className="art-footer">LLM GATEWAY <span>·</span> OPERATOR WORKSPACE</div></aside>
    <main className="login-main"><div className="login-card"><div className="login-icon"><ShieldCheck size={25} /></div><span className="eyebrow">SECURE WORKSPACE</span><h2>운영 콘솔에 연결</h2><p className="login-description">{mode === 'loading' ? '게이트웨이의 인증 설정을 확인하고 있습니다.' : mode === 'local' ? '로컬 운영자 토큰으로 사용량과 요청 기록에 접근합니다.' : '조직 계정으로 로그인하여 권한에 맞는 기록을 확인합니다.'}</p>
      {mode === 'local' ? <form onSubmit={signIn}><label htmlFor="operator-token">운영자 토큰</label><input id="operator-token" type="password" value={tokenInput} onChange={e => setTokenInput(e.target.value)} autoComplete="off" spellCheck={false} required placeholder="GW_ADMIN_TOKEN" /><small className="field-help">Anthropic·OpenAI API 키와 다른 관리자 접속 키입니다. 브라우저 저장소에 보관하지 않습니다.</small><button className="button primary login-submit" disabled={loginBusy} type="submit">{loginBusy ? '연결 확인 중…' : '콘솔 열기'}<ArrowRight size={17} /></button></form> : mode === 'enterprise' ? <a className="button primary login-submit" href={loginUrl}>회사 계정으로 로그인 <ArrowRight size={17} /></a> : <button className="button ghost login-submit" onClick={() => location.reload()}>다시 시도 <RefreshCw size={16} /></button>}
      {loginError && <div className="form-error" role="alert">{loginError}</div>}<div className="login-hint"><span className="online-dot" />새로고침하거나 로그아웃하면 로컬 토큰은 지워집니다.</div>
    </div></main>
  </div>;

  const visibleNav = navigation.filter(item => session.permissions?.includes(viewMeta[item.view].permission));
  const activeData = !loading ? data : null;
  return <div className="app-shell">
    <aside className={`sidebar ${mobileNav ? 'open' : ''}`}>
      <Brand dark /><div className="sidebar-divider" />
      <div className="tenant-card"><span className="mini-label">현재 조직</span><strong>{session.tenantId || '현재 조직'}</strong><small>{session.user?.subject || session.subject || '운영자'}</small><div className="role-row">{(session.roles || []).map(role => <span key={role}>{role}</span>)}</div></div>
      <nav aria-label="주 메뉴">{['관측 · 분석', '운영 관리'].map(group => visibleNav.some(item => item.group === group) && <div className="nav-group" key={group}><span className="mini-label nav-caption">{group}</span>{visibleNav.filter(item => item.group === group).map(({ view: item, icon: Icon }) => <button key={item} className={`nav-item ${view === item ? 'active' : ''}`} aria-current={view === item ? 'page' : undefined} onClick={() => changeView(item)}><Icon size={18} strokeWidth={1.8} /><span>{viewMeta[item].title}</span><ArrowRight size={14} className="nav-arrow" /></button>)}</div>)}</nav>
      <div className="sidebar-bottom"><span className="session-indicator"><span className="online-dot" />운영자 세션 연결됨</span><p>관측된 기록을 기준으로 합니다.<br />공급자 청구서와 다를 수 있습니다.</p><button className="logout-button" onClick={logout}><LogOut size={17} /> 로그아웃</button></div>
    </aside>
    {mobileNav && <button className="mobile-scrim" aria-label="메뉴 닫기" onClick={() => setMobileNav(false)} />}
    <main className="main"><div className="topbar"><button className="mobile-menu" onClick={() => setMobileNav(true)} aria-label="메뉴 열기"><Menu size={21} /></button><span className="breadcrumb">WORKSPACE <span>/</span> {viewMeta[view].title}</span><div className="topbar-right"><span className="environment"><span className="online-dot" />{mode === 'enterprise' ? 'ENTERPRISE' : 'LOCAL'}</span><span className="avatar">{session.user?.subject?.slice(0, 1).toUpperCase() || 'O'}</span></div></div>
      <div className="content"><header className="page-heading"><div><span className="eyebrow">GATEWAY ANALYTICS</span><h1>{viewMeta[view].title}</h1><p>{viewMeta[view].description}</p></div><button className="button outline" disabled={loading} onClick={() => setRevision(n => n + 1)}><RefreshCw size={16} className={loading ? 'spin' : ''} /> 새로고침</button></header>
        {(view === 'overview' || view === 'requests') && <form className="filters" onSubmit={applyFilters}><div className="filter-main"><label>데이터 소스<select value={draft.source} onChange={e => setDraft({ ...draft, source: e.target.value })}><option value="gateway">Gateway · API 요청</option><option value="claude_code">Claude Code · 구독 모니터링</option><option value="all">전체 · 소스 함께 보기</option></select></label><label>시작일<input type="date" required value={draft.from} onChange={e => setDraft({ ...draft, from: e.target.value })} /></label><label>종료일<input type="date" required value={draft.to} onChange={e => setDraft({ ...draft, to: e.target.value })} /></label><label>트래픽<select value={draft.traffic} onChange={e => setDraft({ ...draft, traffic: e.target.value })}><option value="live">Live · 실제</option><option value="test">Test · 테스트</option><option value="demo">Demo · 데모</option><option value="all">전체</option></select></label><button className="button primary filter-apply" type="submit"><Search size={15} /> 조회</button></div><details className="advanced-filters"><summary><ChevronDown size={15} /> 상세 필터 <span>키 · 모델 · 세션 · 결과</span></summary><div className="filter-advanced"><label>시간대<input value={draft.zone} onChange={e => setDraft({ ...draft, zone: e.target.value })} placeholder="Asia/Seoul" /></label><label>키 ID<input value={draft.keyId} onChange={e => setDraft({ ...draft, keyId: e.target.value })} placeholder="정확히 일치" /></label><label>모델<input value={draft.model} onChange={e => setDraft({ ...draft, model: e.target.value })} placeholder="모델 ID" /></label><label>세션 ID<input value={draft.sessionId} onChange={e => setDraft({ ...draft, sessionId: e.target.value })} placeholder="정확히 일치" /></label><label>청구 모드<select value={draft.billingMode} onChange={e => setDraft({ ...draft, billingMode: e.target.value })}><option value="">전체</option><option value="api_key">API 키</option><option value="subscription_declared">구독 · 자기 신고</option><option value="api_key_declared">API 키 · 자기 신고</option><option value="unknown">미확인</option></select></label><label>공급자<select value={draft.provider} onChange={e => setDraft({ ...draft, provider: e.target.value })}><option value="">전체</option><option value="anthropic">Anthropic</option><option value="openai">OpenAI</option><option value="custom">Custom</option></select></label><label>결과<select value={draft.outcome} onChange={e => setDraft({ ...draft, outcome: e.target.value })}><option value="">전체</option><option value="success">성공</option><option value="http_error">HTTP 오류</option><option value="upstream_error">업스트림 오류</option><option value="cancelled">취소</option></select></label><button type="button" className="text-button" onClick={resetFilters}>초기화</button></div></details></form>}
        {notice && <div className="inline-note" role="status">{notice}</div>}{error && <div className="inline-note error" role="alert">{error}</div>}
        {loading && <div className="loading-panel"><RefreshCw size={18} className="spin" /> 기록을 불러오는 중입니다</div>}
        {activeData && view === 'overview' && <OverviewView data={activeData as Overview} source={filters.source} />}
        {activeData && view === 'requests' && <RequestsView data={activeData as Paged<RequestItem>} offset={offset} loading={loading} onPage={setOffset} onDetail={showDetail} zone={filters.zone} />}
        {activeData && view === 'ingestion' && <IngestionView data={activeData as Ingestion} zone={filters.zone} />}
        {activeData && view === 'collectors' && <CollectorOwners key={authIdentity} api={api} onError={monitoringError} revision={revision}>{(accounts, accountPage) => <CollectorsView accounts={accounts} accountPage={accountPage} data={activeData as Paged<Collector>} offset={offset} loading={loading} onPage={setOffset} onCreate={createCollector} onRevoke={revokeCollector} zone={filters.zone} />}</CollectorOwners>}
        {view === 'monitoring' && session.permissions?.includes('usage:read') && <MonitoringView key={authIdentity} api={api} canReadContent={!!session.permissions?.includes('content:read')} canConfigureCosts={!!session.permissions?.includes('collectors:manage')} onError={monitoringError} revision={revision} />}
        {activeData && view === 'audit' && <AuditView data={activeData as Paged<Audit>} offset={offset} loading={loading} onPage={setOffset} zone={filters.zone} />}
        <footer className="footer"><span>LLM GATEWAY · OPERATIONS CONSOLE</span><span>{view === 'monitoring' ? '계정 · 세션 · 질문 원문' : updated ? `마지막 업데이트 ${time(updated, filters.zone)}` : '기록을 기다리는 중'}</span></footer>
      </div>
    </main>
    {detail && <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) { detailAbort.current?.abort(); setDetail(null); } }}><section className="modal" role="dialog" aria-modal="true" aria-labelledby="detail-title"><div className="modal-heading"><div><span className="eyebrow">REQUEST INSPECTOR</span><h2 id="detail-title">요청 상세</h2></div><button className="icon-button" aria-label="상세 닫기" onClick={() => { detailAbort.current?.abort(); setDetail(null); }}><X size={21} /></button></div><div className="modal-content">{detailBusy ? <div className="loading-panel"><RefreshCw size={18} className="spin" /> 상세를 불러오는 중입니다</div> : detailError ? <div className="inline-note error">{detailError}</div> : <DetailView data={detail} canReadContent={!!session.permissions?.includes('content:read')} zone={filters.zone} />}</div></section></div>}
    {issuedToken && <div className="modal-backdrop"><section className="modal credential-modal" role="dialog" aria-modal="true" aria-labelledby="credential-title"><div className="modal-heading"><div><span className="eyebrow">CREDENTIAL ISSUED</span><h2 id="credential-title">수집 키 발급 완료</h2></div><button className="icon-button" aria-label="키 화면 닫기" onClick={() => setIssuedToken('')}><X size={21} /></button></div><div className="modal-content"><p>이 키는 지금 한 번만 볼 수 있습니다. 닫으면 다시 조회할 수 없습니다.</p><p className="section-note">현재 조직의 사용량 전송 전용이며 관리 콘솔에 접근할 수 없습니다.</p><textarea className="credential-value" value={issuedToken} readOnly spellCheck={false} aria-label="발급된 수집 키" /><button className="button primary" onClick={async () => { try { await navigator.clipboard.writeText(issuedToken); setNotice('수집 키를 복사했습니다. 안전한 위치에 보관하세요.'); } catch { setNotice('자동 복사가 불가능합니다. 키를 선택해 직접 복사하세요.'); } }}><ClipboardCopy size={16} /> 키 복사</button><CollectorSetup token={issuedToken} /></div></section></div>}
  </div>;
}
