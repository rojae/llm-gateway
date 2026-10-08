'use client';

import { useEffect, useRef, useState } from 'react';
import { Activity, ArrowLeft, ArrowRight, ChevronDown, ChevronRight, ClipboardCopy, MessageSquare, Pause, Play, RefreshCw, Search, ShieldCheck, TriangleAlert, Users, X } from 'lucide-react';
import type { MonitoringAccount, MonitoringCosts, MonitoringDetail, MonitoringEvent, MonitoringQuestion, MonitoringSession, MonitoringStatus, Paged } from './model';
import { display, number, sourceLabel, time, today, usd, validateFilters, defaultFilters } from './model';
import { Badge, Empty, Panel } from './views';
import { CostPanel, EstimateBasis, estimateLabel, money } from './costs';

export type AdminApi = <T>(path: string, options?: { method?: string; body?: unknown; signal?: AbortSignal }) => Promise<T>;

// Each selection owns its requests. Aborted or older generations cannot restore data.
function useVisibleData<T>(api: AdminApi, path: string | null, onError: (error: unknown) => void, revision = 0, poll = true, explicit = false) {
  const [state, setState] = useState<{ path: string | null; data: T | null; loading: boolean; error: string; updated: string }>({ path: null, data: null, loading: false, error: '', updated: '' });
  const generation = useRef(0);
  const apiRef = useRef(api); apiRef.current = api;
  const trigger = useRef<{ path: string | null; revision: number }>({ path: null, revision });
  const onErrorRef = useRef(onError); onErrorRef.current = onError;
  useEffect(() => {
    const own = ++generation.current;
    const changed = trigger.current.path !== path || trigger.current.revision !== revision;
    trigger.current = { path, revision };
    let controller: AbortController | null = null;
    let running = false;
    const willLoad = explicit || poll || changed;
    let needsLoad = willLoad;
    setState(previous => previous.path === path ? { ...previous, loading: !!path && !previous.data && willLoad } : { path, data: null, loading: !!path && willLoad, error: '', updated: '' });
    async function load() {
      if (!path || (!explicit && document.hidden) || running) return;
      running = true;
      needsLoad = false;
      controller = new AbortController();
      const request = controller;
      try {
        const data = await apiRef.current<T>(path, { signal: request.signal });
        if (!request.signal.aborted && generation.current === own) setState({ path, data, loading: false, error: '', updated: new Date().toISOString() });
      } catch (error) {
        if (!request.signal.aborted && generation.current === own) {
          setState(previous => ({ ...previous, loading: false, error: error instanceof Error ? error.message : '기록을 불러오지 못했습니다.' }));
          onErrorRef.current(error);
        }
      } finally { if (controller === request) running = false; }
    }
    const visibility = () => {
      if (document.hidden) { if (running) needsLoad = true; controller?.abort(); running = false; }
      else if (poll || needsLoad) void load();
    };
    if (willLoad) void load();
    const timer = poll ? window.setInterval(() => void load(), 2000) : null;
    if (!explicit) document.addEventListener('visibilitychange', visibility);
    return () => { ++generation.current; controller?.abort(); if (timer != null) window.clearInterval(timer); if (!explicit) document.removeEventListener('visibilitychange', visibility); };
    // Authentication identity changes remount the owning view. CSRF rotation updates
    // apiRef without triggering an additional audited content read.
  }, [path, revision, poll, explicit]);
  return state.path === path ? state : { path, data: null, loading: !!path, error: '', updated: '' };
}

function Pages<T>({ data, offset, onPage, loading, compact = false }: { data: Paged<T> | null; offset: number; onPage: (n: number) => void; loading: boolean; compact?: boolean }) {
  return <div className={`pagination ${compact ? 'compact-pagination' : ''}`}><span>총 {data ? number(data.total) : '—'}건 · {number(offset / 25 + 1)}페이지</span><div><button className="button ghost" aria-label="이전 페이지" disabled={loading || offset === 0} onClick={() => onPage(Math.max(0, offset - 25))}><ArrowLeft size={14} />{!compact && '이전'}</button><button className="button ghost" aria-label="다음 페이지" disabled={loading || !data || offset + 25 >= data.total} onClick={() => onPage(offset + 25)}>{!compact && '다음'}<ArrowRight size={14} /></button></div></div>;
}
function LoadState({ loading, error }: { loading: boolean; error: string }) {
  return <>{loading && <div className="loading-panel" role="status"><RefreshCw className="spin" size={18} />기록을 불러오는 중입니다</div>}{error && <div className="inline-note error" role="alert">{error}</div>}</>;
}
function usageLabel(availability?: string, attribution?: string) {
  if (attribution === 'unattributed') return '질문 연결 안 됨';
  if (attribution === 'conflict') return '귀속 충돌';
  if (attribution === 'linked' && !availability) return '연결됨';
  return ({ reported: '관측됨', observed: '관측됨', complete: '관측됨', partial: '일부 관측', missing: '사용량 미수신', unknown: '사용량 미확인', unconfirmed: '사용량 미확인', unattributed: '질문 연결 안 됨' }[availability || ''] || '사용량 미확인');
}
function uncertain(item: MonitoringQuestion | MonitoringSession) {
  return item.source === 'codex' && (item.usageAvailability !== 'reported' && item.usageAvailability !== 'complete' || 'attribution' in item && item.attribution !== 'linked');
}
function tokens(item: MonitoringQuestion | MonitoringSession, value: number | null | undefined) { return uncertain(item) ? '질문 연결 안 됨' : value == null ? '사용량 미수신' : number(value); }
function cost(item: MonitoringQuestion | MonitoringSession) { return uncertain(item) ? '질문 연결 안 됨' : item.apiEquivalentCostUsd == null ? '비용 미제공' : usd(item.apiEquivalentCostUsd); }
function usageReason(item: MonitoringQuestion | MonitoringSession) {
  if (uncertain(item)) return '현재 Codex 수집 방식으로는 토큰 관측을 개별 질문과 연결할 수 없어 질문별 합계를 계산할 수 없습니다.';
  if (item.totalTokens == null) return '연결된 호출의 토큰 사용량이 수신되지 않았거나 일부 항목이 빠져 있습니다.';
  if (item.apiEquivalentCostUsd == null) return '토큰은 관측됐지만 비용 관측값 또는 확인된 모델 단가가 없어 환산액을 계산할 수 없습니다.';
  return '';
}

function shortId(value: string) { return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value; }
function briefTime(value: unknown, zone: string) {
  if (!value || Number.isNaN(Date.parse(String(value)))) return '시각 미확인';
  return new Intl.DateTimeFormat('ko-KR', { timeZone: zone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(String(value)));
}

export function MonitoringView({ api, canReadContent, canConfigureCosts, onError, revision }: { api: AdminApi; canReadContent: boolean; canConfigureCosts: boolean; onError: (e: unknown) => void; revision: number }) {
  const [account, setAccount] = useState<MonitoringAccount | null>(null);
  const [selectedSession, setSelectedSession] = useState<MonitoringSession | null>(null);
  const [question, setQuestion] = useState<MonitoringQuestion | null>(null);
  const [accountOffset, setAccountOffset] = useState(0);
  const [sessionOffset, setSessionOffset] = useState(0);
  const [questionOffset, setQuestionOffset] = useState(0);
  const [eventOffset, setEventOffset] = useState(0);
  const [showEvents, setShowEvents] = useState(false);
  const [paused, setPaused] = useState(false);
  const [accountSearch, setAccountSearch] = useState('');
  const [draft, setDraft] = useState({ source: '', from: today(), to: today(), zone: 'Asia/Seoul' });
  const [filters, setFilters] = useState(draft);
  const [notice, setNotice] = useState('');
  const [detailRevision, setDetailRevision] = useState(0);
  const [costMonth, setCostMonth] = useState(today().slice(0, 7));
  const [costRevision, setCostRevision] = useState(0);
  const reader = useRef<HTMLElement>(null);
  const sessionParams = new URLSearchParams({ ...filters, accountId: account?.id || '', limit: '25', offset: String(sessionOffset) });
  if (!filters.source) sessionParams.delete('source');
  const accounts = useVisibleData<Paged<MonitoringAccount>>(api, `monitoring/accounts?limit=25&offset=${accountOffset}`, onError, revision, !paused);
  const sessions = useVisibleData<Paged<MonitoringSession>>(api, account ? `monitoring/sessions?${sessionParams}` : null, onError, revision, !paused);
  const questions = useVisibleData<Paged<MonitoringQuestion>>(api, selectedSession ? `monitoring/sessions/${encodeURIComponent(selectedSession.id)}/questions?limit=25&offset=${questionOffset}` : null, onError, revision, !paused);
  const status = useVisibleData<MonitoringStatus>(api, 'monitoring/status', onError, revision, !paused);
  const events = useVisibleData<Paged<MonitoringEvent>>(api, selectedSession ? `monitoring/sessions/${encodeURIComponent(selectedSession.id)}/events?limit=25&offset=${eventOffset}` : null, onError, revision, !paused);
  const costParams = new URLSearchParams({ month: costMonth, zone: filters.zone });
  if (selectedSession) costParams.set('sessionId', selectedSession.id);
  if (question) costParams.set('questionId', question.id);
  const costs = useVisibleData<MonitoringCosts>(api, account ? `monitoring/accounts/${encodeURIComponent(account.id)}/costs?${costParams}` : null, onError, revision + costRevision, !paused);
  // Only explicit question selection/retry reads audited content. Polling, focus and visibility do not.
  const detail = useVisibleData<MonitoringDetail>(api, question && canReadContent ? `monitoring/questions/${encodeURIComponent(question.id)}` : null, onError, detailRevision, false, true);
  const chooseAccount = (next: MonitoringAccount | null) => { setAccount(next); setSelectedSession(null); setQuestion(null); setSessionOffset(0); setQuestionOffset(0); setEventOffset(0); setShowEvents(false); setNotice(''); };
  const chooseSession = (next: MonitoringSession | null) => { setSelectedSession(next); setQuestion(null); setQuestionOffset(0); setEventOffset(0); setShowEvents(false); setNotice(''); };
  useEffect(() => { setQuestion(null); }, [canReadContent]);
  useEffect(() => { if (question && window.innerWidth <= 700) reader.current?.scrollIntoView({ block: 'start' }); }, [question?.id]);
  function apply(next: typeof draft) {
    const problem = validateFilters({ ...defaultFilters(), ...next });
    if (problem) { setNotice(problem); return; }
    setNotice(''); setFilters({ ...next }); setSessionOffset(0); chooseSession(null);
  }
  function quickRange(days: number) {
    const to = today(); const from = new Date(`${to}T00:00:00Z`);
    from.setUTCDate(from.getUTCDate() - days + 1);
    const next = { ...draft, from: from.toISOString().slice(0, 10), to, zone: 'Asia/Seoul' };
    setDraft(next); apply(next);
  }
  const currentAccount = accounts.data?.items.find(item => item.id === account?.id) || account;
  const currentSession = sessions.data?.items.find(item => item.id === selectedSession?.id) || selectedSession;
  const visibleAccounts = (accounts.data?.items || []).filter(item => item.name.toLocaleLowerCase().includes(accountSearch.trim().toLocaleLowerCase()));
  const questionIndex = questions.data?.items.findIndex(item => item.id === question?.id) ?? -1;
  const listedQuestion = questions.data?.items.find(item => item.id === question?.id);
  const detailedQuestion = canReadContent ? detail.data?.question : null;
  // An explicit read may be newer while polling is paused; subsequent list polls
  // update usage without reading the audited body again.
  const q = detailedQuestion && detail.updated > questions.updated ? detailedQuestion : listedQuestion || detailedQuestion || question;
  const questionAllocation = costs.error ? '비용 조회 실패' : costs.loading && !costs.data ? '계산 중…' : costs.data?.question && !costs.data.question.inMonth ? '산정 월에 포함되지 않음' : money(costs.data?.question?.subscriptionAllocation, costs.data?.subscription.currency);
  const connected = !status.error && status.data?.ready === true;
  const tokenObservations = (events.data?.items || []).filter(item => [item.inputTokens, item.outputTokens, item.cacheReadTokens, item.cacheWriteTokens].some(value => value != null));
  return <div className="monitoring-view">
    <section className="receiving-summary" aria-label="수신 상태">
      <div className={`receiving-connection ${status.error ? 'has-error' : ''}`}><Activity size={18} /><div><strong>{status.error ? '조회 연결 실패' : connected ? '조회 연결됨' : status.loading ? '수신 상태 확인 중' : '수신 준비 미확인'}</strong><span>{status.updated ? `조회 ${briefTime(status.updated, filters.zone)}` : '수신 상태를 확인합니다'}{status.error && ' · 이전 조회 기준'}</span></div></div>
      <div className="receiving-value"><span>저장된 기록</span><strong>{number(status.data?.stored)}</strong></div>
      <div className="receiving-value receiving-last"><span>마지막 수신</span><strong title={time(status.data?.lastReceivedAt, filters.zone)}>{status.data?.lastReceivedAt ? briefTime(status.data.lastReceivedAt, filters.zone) : '아직 없음'}</strong></div>
      <details className="receiving-diagnostics"><summary><TriangleAlert size={15} /><span>거절 {number(status.data?.rejected)}</span><ChevronDown size={14} /></summary><div><h3>수신 상세 · 누적 기록</h3><dl>{[['중복', status.data?.duplicates], ['미지원 형식', status.data?.unsupported], ['질문에 미귀속', status.data?.unattributed]].map(([label, value]) => <div key={String(label)}><dt>{label}</dt><dd>{number(value)}</dd></div>)}</dl><p>미지원·미귀속 기록도 저장됩니다. 거절 건수는 누적값이며 현재 장애를 뜻하지 않습니다.</p></div></details>
      <button className={`button ghost compact live-control ${paused ? 'is-paused' : ''}`} aria-pressed={!paused} onClick={() => setPaused(value => !value)}>{paused ? <Play size={14} /> : <Pause size={14} />}{paused ? '자동 갱신 재개' : '2초 자동 갱신'}</button>
    </section>
    {status.error && <div className="inline-note error" role="alert">{status.error}</div>}
    <div className="monitoring-workspace">
      <aside className="account-rail" aria-label="모니터링 계정">
        <Panel title="계정" action={<span className="header-chip">{accounts.data ? number(accounts.data.total) : '—'}</span>}>
          <label className="account-search"><Search size={15} /><input value={accountSearch} onChange={e => setAccountSearch(e.target.value)} placeholder="이 페이지에서 계정 검색" aria-label="이 페이지에서 계정 검색" /></label>
          <LoadState loading={accounts.loading} error={accounts.error} />
          {accounts.data && (visibleAccounts.length ? <div className="account-list">{visibleAccounts.map(item => <button key={item.id} className={`account-option ${account?.id === item.id ? 'selected' : ''}`} aria-pressed={account?.id === item.id} aria-label={`${item.name} 계정 선택`} onClick={() => chooseAccount(item)}><span className="account-avatar">{item.name.slice(0, 1).toUpperCase()}</span><span className="account-option-text"><strong>{item.name}</strong><small>세션 {number(item.sessionCount)} · 질문 {number(item.questionCount)}</small></span><ChevronRight size={15} /></button>)}</div> : <Empty title={accountSearch ? '검색 결과가 없습니다' : '등록된 계정이 없습니다'} detail={accountSearch ? '현재 계정 페이지에서 검색합니다. 검색어를 지우거나 페이지를 이동하세요.' : '수집 키 관리에서 계정을 등록하세요.'} />)}
          <Pages compact data={accounts.data} offset={accountOffset} onPage={n => { setAccountOffset(n); setAccountSearch(''); }} loading={accounts.loading} />
        </Panel>
        <p className="rail-help"><ShieldCheck size={14} />질문 본문을 열면 열람 이력이 감사 기록에 남습니다.</p>
      </aside>
      <div className="monitoring-main">
        {!account ? <section className="monitoring-welcome"><span className="welcome-icon"><Users size={28} /></span><h2>확인할 계정을 선택하세요</h2><p>세션을 고르고 질문 원문을 읽을 수 있습니다.</p><div className="reading-steps"><span>1. 계정</span><ChevronRight size={15} /><span>2. 세션</span><ChevronRight size={15} /><span>3. 질문</span></div><p className="muted">AI 답변은 현재 수집되지 않습니다.</p></section> : <>
          <div className="monitoring-scope"><div><span className="mini-label">선택한 계정</span><h2>{currentAccount?.name}</h2></div><span>전체 세션 {number(currentAccount?.sessionCount)} · 전체 질문 {number(currentAccount?.questionCount)}</span></div>
          <CostPanel key={account.id} api={api} accountId={account.id} data={costs.data} loading={costs.loading} error={costs.error} month={costMonth} onMonth={setCostMonth} canConfigure={canConfigureCosts} onSaved={() => setCostRevision(value => value + 1)} onError={onError} />
          <details className={`monitoring-filter-disclosure ${!selectedSession ? 'is-expanded' : ''}`} open={!selectedSession || undefined}>
          <summary><span>{filters.from} ~ {filters.to} · {filters.source ? sourceLabel(filters.source) : '전체 CLI'}</span><span>조회 범위 변경<ChevronDown size={14} /></span></summary>
          <form className="filters monitoring-filters" onSubmit={e => { e.preventDefault(); apply(draft); }}>
            <div className="range-shortcuts"><span>조회 기간</span><button className="button ghost compact" type="button" onClick={() => quickRange(1)}>오늘</button><button className="button ghost compact" type="button" onClick={() => quickRange(7)}>최근 7일</button><button className="button ghost compact" type="button" onClick={() => quickRange(30)}>최근 30일</button><span className="range-zone">{filters.zone}</span></div>
            <div className="filter-main"><label>CLI<select value={draft.source} onChange={e => setDraft({ ...draft, source: e.target.value })}><option value="">전체 CLI</option><option value="claude_code">Claude Code</option><option value="codex">Codex</option></select></label><label>시작일<input type="date" required value={draft.from} onChange={e => setDraft({ ...draft, from: e.target.value })} /></label><label>종료일<input type="date" required value={draft.to} onChange={e => setDraft({ ...draft, to: e.target.value })} /></label><button className="button primary" type="submit"><Search size={15} />조회</button></div>
            <details className="monitoring-zone"><summary>시간대 변경</summary><label>시간대<input required value={draft.zone} onChange={e => setDraft({ ...draft, zone: e.target.value })} /></label></details>
          </form>
          </details>
          {notice && <div className="inline-note" role="status">{notice}</div>}
          <div className={`session-workspace ${selectedSession ? 'has-session' : ''}`}>
            <Panel title="세션" detail={selectedSession ? '다른 세션으로 바로 이동할 수 있습니다.' : '최근 수신 순 · 세션을 선택해 질문을 확인하세요.'} action={<span className="header-chip">{sessions.data ? number(sessions.data.total) : '—'}개</span>}>
              <LoadState loading={sessions.loading} error={sessions.error} />
              {sessions.data && (sessions.data.items.length ? selectedSession ? <div className="session-list">{sessions.data.items.map(item => <button className={`session-option ${selectedSession.id === item.id ? 'selected' : ''}`} key={item.id} aria-pressed={selectedSession.id === item.id} aria-label={`${sourceLabel(item.source)} 세션 ${shortId(item.externalSessionId || item.id)} 열기`} onClick={() => chooseSession(item)}><span className="session-option-top"><strong>{sourceLabel(item.source)}</strong><span>질문 {number(item.questionCount)}</span></span><span>{briefTime(item.lastSeenAt, filters.zone)}</span><small className="mono" title={item.externalSessionId || item.id}>{shortId(item.externalSessionId || item.id)}</small></button>)}</div> : <div className="table-wrap"><table className="session-table"><thead><tr><th>세션</th><th>최근 수신</th><th className="right">질문</th><th className="right">토큰</th><th className="right">질문 귀속 환산액</th><th /></tr></thead><tbody>{sessions.data.items.map(item => <tr key={item.id}><td><button className="session-table-link" aria-label={`${sourceLabel(item.source)} 세션 ${shortId(item.externalSessionId || item.id)} 열기`} onClick={() => chooseSession(item)}><span><strong>{sourceLabel(item.source)} 세션</strong><small className="mono" title={item.externalSessionId || item.id}>{shortId(item.externalSessionId || item.id)}</small></span></button></td><td>{briefTime(item.lastSeenAt, filters.zone)}</td><td className="right">{number(item.questionCount)}</td><td className="right">{tokens(item, item.totalTokens)}</td><td className="right">{item.source === 'codex' ? '관측값은 세션에서 확인' : cost(item)}</td><td><ChevronRight size={16} /></td></tr>)}</tbody></table></div> : <Empty title="일치하는 세션이 없습니다" detail="조회 기간을 넓히거나 CLI 필터를 확인하세요." />)}
              <Pages compact={!!selectedSession} data={sessions.data} offset={sessionOffset} onPage={n => { setSessionOffset(n); chooseSession(null); }} loading={sessions.loading} />
            </Panel>
            {currentSession && <section className="session-inspector" aria-label="선택한 세션">
              <header className="session-inspector-heading"><div><div className="inspector-eyebrow"><Badge value="neutral">{sourceLabel(currentSession.source)}</Badge><span>{briefTime(currentSession.firstSeenAt, filters.zone)} 시작</span></div><h2>{currentAccount?.name}의 질문 기록</h2><span className="session-identifier mono" title={currentSession.externalSessionId || currentSession.id}>{shortId(currentSession.externalSessionId || currentSession.id)}<button className="icon-button" aria-label="세션 ID 복사" onClick={async () => { try { await navigator.clipboard.writeText(currentSession.externalSessionId || currentSession.id); setNotice('세션 ID를 복사했습니다.'); } catch { setNotice('세션 ID를 복사하지 못했습니다. 기술 정보에서 직접 선택해 복사하세요.'); } }}><ClipboardCopy size={13} /></button></span></div><button className="button ghost compact" onClick={() => chooseSession(null)}><ArrowLeft size={14} />세션 목록</button></header>
              <div className="session-summary"><div><span>질문</span><strong>{number(currentSession.questionCount)}개</strong></div><div><span>질문별 토큰 합계</span><strong>{tokens(currentSession, currentSession.totalTokens)}</strong></div><div><span>{currentSession.apiEquivalentCostUsd != null ? "API 환산액" : "최근 응답 환산액"}</span><strong>{costs.error ? "비용 조회 실패" : currentSession.apiEquivalentCostUsd != null ? usd(currentSession.apiEquivalentCostUsd) : estimateLabel(costs.data?.session?.latestObservation?.apiEstimate)}</strong></div></div><div className="session-cost-context"><span>구독료 배분 · {costMonth}: <strong>{costs.error ? "조회 실패" : costs.data?.subscription.amount != null && !costs.data?.session?.questionCount ? "해당 월 질문 없음" : money(costs.data?.session?.subscriptionAllocation, costs.data?.subscription.currency)}</strong></span>{currentSession.apiEquivalentCostUsd == null && <span>산정 월 최근 토큰 관측 1건 · 세션 전체 합계 아님</span>}</div>
              {usageReason(currentSession) && <div className="usage-explanation"><TriangleAlert size={15} /><p>{usageReason(currentSession)}<span>개별 토큰 관측에는 API 단가를 적용합니다. 구독료는 위 비용 산정에서 별도로 배분합니다.</span></p><button className="text-button" onClick={() => { setShowEvents(true); setQuestion(null); }}>관측값 보기<ChevronRight size={14} /></button></div>}
              <div className="inspector-tabs" aria-label="세션 표시 내용"><button aria-pressed={!showEvents} className={!showEvents ? 'active' : ''} onClick={() => setShowEvents(false)}><MessageSquare size={16} />질문 기록</button><button aria-pressed={showEvents} className={showEvents ? 'active' : ''} onClick={() => { setShowEvents(true); setQuestion(null); }}><Activity size={16} />관측 이벤트</button></div>
              {!showEvents ? <div className="question-workspace">
                <aside className="question-rail" aria-label="세션 질문 목록"><div className="question-list-heading"><strong>질문 {questions.data ? number(questions.data.total) : '—'}건</strong><span>시간 순</span></div><LoadState loading={questions.loading} error={questions.error} />{questions.data && (questions.data.items.length ? <div className="question-list">{questions.data.items.map((item, index) => <button key={item.id} className={`question-option ${question?.id === item.id ? 'selected' : ''}`} aria-pressed={question?.id === item.id} aria-label={`질문 ${questionOffset + index + 1} 보기`} onClick={() => setQuestion(item)}><span><strong>질문 {questionOffset + index + 1}</strong><ChevronRight size={14} /></span><small>{briefTime(item.startedAt, filters.zone)}</small><small>{item.model || '모델 미확인'}</small><span className="question-availability">{usageLabel(item.usageAvailability, item.attribution)}</span></button>)}</div> : <Empty title="질문 기록이 없습니다" detail="CLI의 질문 본문 수집 설정을 확인하세요." />)}<Pages compact data={questions.data} offset={questionOffset} onPage={n => { setQuestion(null); setQuestionOffset(n); }} loading={questions.loading} /></aside>
                <section ref={reader} className="question-reader" aria-label="질문 본문 읽기">
                  {!q ? <Empty title="질문을 선택해 본문을 읽으세요" detail="질문은 선택할 때 조회하며, 열람 이력이 기록됩니다." /> : <>
                    <header className="reader-heading"><div><span className="mini-label">수집된 질문 원문</span><h3>{questionIndex >= 0 ? `질문 ${questionOffset + questionIndex + 1}` : '선택한 질문'}</h3><p>{briefTime(q.startedAt, filters.zone)} · {q.model || '모델 미확인'}</p></div><div className="reader-actions"><button className="icon-button" aria-label="이전 질문" disabled={questionIndex <= 0} onClick={() => setQuestion(questions.data!.items[questionIndex - 1])}><ArrowLeft size={16} /></button><button className="icon-button" aria-label="다음 질문" disabled={questionIndex < 0 || questionIndex + 1 >= (questions.data?.items.length || 0)} onClick={() => setQuestion(questions.data!.items[questionIndex + 1])}><ArrowRight size={16} /></button><button className="icon-button" aria-label="질문 닫기" onClick={() => setQuestion(null)}><X size={16} /></button></div></header>
                    {canReadContent ? <><LoadState loading={detail.loading} error={detail.error} />{detail.error && <button className="button ghost compact reader-retry" onClick={() => setDetailRevision(value => value + 1)}>본문 다시 조회</button>}{detail.data && <div className="prompt-content">{detail.data.content ? <>{detail.data.content.truncated && <Badge value="partial">기록 한도 초과 · 일부 본문</Badge>}<pre className="prompt-text">{detail.data.content.text || '수집된 질문이 비어 있습니다.'}</pre></> : <Empty title="본문 기록이 없습니다" detail="본문 수집이 꺼져 있거나 보관 기간이 지났을 수 있습니다." />}</div>}</> : <div className="inline-note">현재 역할에는 질문 본문 조회 권한이 없습니다.</div>}
                    <p className="reader-boundary"><ShieldCheck size={14} />질문 원문만 수집됩니다. AI 답변은 포함되지 않습니다.</p>
                    <details className="question-metadata"><summary>사용량 · 기술 정보<ChevronDown size={15} /></summary><p>{usageReason(q) || '관측된 호출의 사용량입니다.'} API 환산액은 구독 청구액이 아닙니다.</p><div className="detail-grid">{[['질문 ID', q.externalPromptId || q.id], ['입력 / 출력 토큰', `${tokens(q, q.inputTokens)} / ${tokens(q, q.outputTokens)}`], ['캐시 읽기 / 쓰기', `${tokens(q, q.cacheReadTokens)} / ${tokens(q, q.cacheWriteTokens)}`], ['전체 토큰', tokens(q, q.totalTokens)], ['질문 API 환산액', cost(q)], ['질문 구독료 배분', questionAllocation], ['귀속', usageLabel(q.usageAvailability, q.attribution)], ['관측 범위', q.coverage === 'observed' ? '관측된 호출' : q.coverage === 'partial' ? '일부 관측' : '미확인']].map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}</div></details>
                  </>}
                </section>
              </div> : <div className="observation-panel"><p className="observation-note">개별 토큰 관측값입니다. 호출별·누적 값의 경계가 확인되지 않은 기록은 합산하지 않습니다.</p><LoadState loading={events.loading} error={events.error} />{events.data && <section className="token-observations" aria-label="개별 토큰 관측값"><div className="token-observations-heading"><h3>수신된 토큰 관측</h3><span>현재 이벤트 페이지 · {tokenObservations.length}건</span></div>{tokenObservations.length ? <div className="token-observation-list">{tokenObservations.map(item => <article className="token-observation" key={item.id}><header><strong>{item.model || '모델 미확인'}</strong><span>{briefTime(item.emittedAt, filters.zone)}</span></header><dl>{[['입력', item.inputTokens], ['출력', item.outputTokens], ['캐시 읽기', item.cacheReadTokens], ['캐시 쓰기', item.cacheWriteTokens]].map(([label, value]) => <div key={String(label)}><dt>{label}</dt><dd>{number(value)}</dd></div>)}</dl><div className="observation-cost"><span>API 환산액</span><strong>{estimateLabel(item.apiEstimate)}</strong></div><EstimateBasis estimate={item.apiEstimate} /><p>{usageLabel(undefined, item.attribution)} · {item.eventKind}</p></article>)}</div> : <p className="token-observation-empty">이 이벤트 페이지에는 토큰 관측값이 없습니다. 전체 기록이 여러 페이지이면 다음 페이지도 확인하세요.</p>}<p className="token-observation-boundary">API 환산 시 캐시 토큰은 일반 입력에서 제외해 중복 계산을 피합니다. 각 관측값을 더해 질문·세션 전체 비용으로 계산하지 않습니다.</p></section>}{events.data?.items.length ? <div className="table-wrap"><table><thead><tr><th>종류 / 시각</th><th>모델</th><th className="right">입력 / 출력</th><th className="right">캐시 읽기 / 쓰기</th><th>범위 / 의미</th><th>귀속</th></tr></thead><tbody>{events.data.items.map(item => <tr key={item.id}><td>{item.eventKind}<small>{time(item.emittedAt, filters.zone)}</small></td><td>{display(item.model)}</td><td className="right">{number(item.inputTokens)} / {number(item.outputTokens)}</td><td className="right">{number(item.cacheReadTokens)} / {number(item.cacheWriteTokens)}</td><td>{display(item.scope)} / {display(item.semantics)}</td><td>{usageLabel(undefined, item.attribution)}</td></tr>)}</tbody></table></div> : !events.loading && !events.error && <Empty title="관측 이벤트가 없습니다" detail="이 세션에서 수신된 이벤트가 표시됩니다." />}<Pages data={events.data} offset={eventOffset} onPage={setEventOffset} loading={events.loading} /></div>}
              <details className="session-technical"><summary>세션 기술 정보<ChevronDown size={14} /></summary><dl><div><dt>세션 ID</dt><dd className="mono">{currentSession.externalSessionId || currentSession.id}</dd></div><div><dt>CLI 버전</dt><dd>{display(currentSession.cliVersion)}</dd></div><div><dt>공급자 계정 관측 · 로그인 검증 아님</dt><dd>{currentSession.providerAccountStatus === 'observed' ? '관측됨' : currentSession.providerAccountStatus === 'conflict' ? '관측 충돌' : '미확인'}{currentSession.providerAccountId ? ` · ${currentSession.providerAccountId}` : ''}</dd></div><div><dt>질문에 미귀속된 이벤트</dt><dd>{number(currentSession.unattributedEvents)}건</dd></div></dl></details>
            </section>}
          </div>
        </>}
      </div>
    </div>
    <p className="monitoring-footnote">API 환산액은 모델 단가를 적용한 참고값입니다. 구독료 배분액은 입력한 월 금액을 기록된 질문에 나눈 값입니다. 두 금액을 합산하지 않으며 미확인 사용량은 0으로 표시하지 않습니다.</p>
  </div>;
}

export function CollectorOwners({ api, onError, revision, children }: { api: AdminApi; onError: (e: unknown) => void; revision: number; children: (accounts: MonitoringAccount[], page: React.ReactNode) => React.ReactNode }) {
  const [offset, setOffset] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => pending.current?.abort(), []);
  const accounts = useVisibleData<Paged<MonitoringAccount>>(api, `monitoring/accounts?limit=25&offset=${offset}`, onError, revision + refresh);
  return <><Panel title="모니터링 계정 등록" detail="실제 CLI 사용자를 등록하세요. 키 발급자와 데이터 소유자는 별개입니다."><form className="collector-form" onSubmit={async e => { e.preventDefault(); const form = e.currentTarget; const name = String(new FormData(form).get('accountName') || '').trim(); if (!name || busy) return; const controller = new AbortController(); pending.current?.abort(); pending.current = controller; setBusy(true); setError(''); try { await api('monitoring/accounts', { method: 'POST', body: { name }, signal: controller.signal }); if (!controller.signal.aborted) { form.reset(); setOffset(0); setRefresh(v => v + 1); } } catch (e) { if (!controller.signal.aborted) { setError(e instanceof Error ? e.message : '계정 등록에 실패했습니다.'); onError(e); } } finally { if (!controller.signal.aborted) setBusy(false); } }}><label>계정 이름<input name="accountName" required maxLength={80} placeholder="예: 개발팀 오재성" /></label><button className="button primary" disabled={busy} type="submit">{busy ? '등록 중…' : '계정 등록'}</button></form>{error && <div className="inline-note error" role="alert">{error}</div>}</Panel><LoadState loading={accounts.loading} error={accounts.error} />{children(accounts.data?.items || [], <Pages data={accounts.data} offset={offset} loading={accounts.loading} onPage={setOffset} />)}</>;
}

export function CollectorSetup({ token }: { token: string }) {
  const [endpoint, setEndpoint] = useState('https://gateway.example.com/telemetry/v1/logs');
  let valid = false;
  try { const url = new URL(endpoint); valid = url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash && url.pathname === '/telemetry/v1/logs'; } catch { /* incomplete input */ }
  const claude = JSON.stringify({ env: { CLAUDE_CODE_ENABLE_TELEMETRY: '1', OTEL_LOGS_EXPORTER: 'otlp', OTEL_METRICS_EXPORTER: 'none', OTEL_TRACES_EXPORTER: 'none', OTEL_EXPORTER_OTLP_LOGS_PROTOCOL: 'http/json', OTEL_EXPORTER_OTLP_LOGS_ENDPOINT: endpoint, OTEL_EXPORTER_OTLP_LOGS_HEADERS: `Authorization=Bearer ${token}`, OTEL_EXPORTER_OTLP_LOGS_COMPRESSION: 'none', OTEL_LOGS_EXPORT_INTERVAL: '1000', OTEL_LOG_USER_PROMPTS: '1', OTEL_LOG_ASSISTANT_RESPONSES: '0', OTEL_LOG_TOOL_DETAILS: '0', OTEL_LOG_RAW_API_BODIES: '0' } }, null, 2);
  const codex = `[otel]\nlog_user_prompt = true\n\n[otel.exporter.otlp-http]\nendpoint = ${JSON.stringify(endpoint)}\nprotocol = "json"\nheaders = { Authorization = ${JSON.stringify(`Bearer ${token}`)} }`;
  return <div className="view-stack"><form className="collector-form" onSubmit={e => e.preventDefault()}><label>원격 수신 주소<input value={endpoint} onChange={e => setEndpoint(e.target.value)} type="url" spellCheck={false} /></label></form><p className="section-note">예시 도메인을 실제 서버 주소로 바꾸세요. 로컬 수집기 설치 없이 CLI에서 직접 보냅니다. 기존 모델 주소와 구독 로그인은 그대로 유지하세요. 질문 본문이 서버에 저장됩니다.</p>{!valid ? <div className="inline-note error">HTTPS 주소와 /telemetry/v1/logs 경로를 입력하세요. 인증정보·쿼리·해시가 포함된 주소는 사용할 수 없습니다.</div> : <>{[{ title: 'Claude Code', location: '~/.claude/settings.json의 env에 병합', text: claude }, { title: 'Codex', location: '실제 $CODEX_HOME/config.toml · 기본 ~/.codex/config.toml', text: codex }].map(item => <section className="content-panel" key={item.title}><div className="content-heading">{item.title}</div><p className="panel-note">{item.location}. 프로젝트 설정이 아닌 사용자 설정에 적용하세요. 기존 설정을 통째로 덮어쓰지 마세요.</p><pre>{item.text}</pre></section>)}<p className="section-note">새 설정을 적용한 CLI에서 질문을 보낸 후 모니터링의 수신 시각·저장 건수·질문을 확인하세요. 이 예시는 logs만 전송하고 metrics/traces는 끕니다. 기존 metrics/traces 전송을 사용하는 경우 해당 none 설정은 적용하지 마세요. 이 주소는 logs 전용입니다.</p></>}</div>;
}
