'use client';

import { useEffect, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import type { ApiEstimate, MonitoringCosts } from './model';
import { number, usd } from './model';
import type { AdminApi } from './monitoring';

export function money(value: number | null | undefined, currency = 'USD') {
  if (value == null) return '월 구독료 입력 필요';
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency, minimumFractionDigits: currency === 'KRW' ? 0 : 2, maximumFractionDigits: 6 }).format(value);
}
export function estimateLabel(estimate?: ApiEstimate | null) {
  if (estimate?.amountUsd != null) return usd(estimate.amountUsd);
  return ({ unpriced: '모델 단가 미등록', missing_usage: '토큰 정보 부족', invalid_usage: '토큰 값 확인 필요' }[estimate?.availability || ''] || '비용 관측 없음');
}
export function EstimateBasis({ estimate }: { estimate?: ApiEstimate | null }) {
  if (!estimate || estimate.amountUsd == null) return null;
  return <details className="estimate-basis"><summary>계산 기준<ChevronDown size={12} /></summary>{estimate.basis === 'client_reported' ? <p>CLI에서 보고한 API 환산액입니다.</p> : <><p>일반 입력 × {estimate.inputUsdPerMillion} + 캐시 읽기 × {estimate.cacheReadUsdPerMillion} + 캐시 쓰기 × {estimate.cacheWriteUsdPerMillion} + 출력 × {estimate.outputUsdPerMillion}, 합계를 100만으로 나눕니다.</p><p>계산에 사용한 일반 입력은 {number(estimate.ordinaryInputTokens)}토큰입니다. {estimate.longContext ? '272K 초과 입력 단가를 적용했습니다.' : 'Standard 단가 기준입니다.'} 관측되지 않은 캐시는 0으로 가정하며 Fast·지역 할증·도구 비용은 포함하지 않습니다.</p></>}{estimate.sourceUrl && <p><a href={estimate.sourceUrl} target="_blank" rel="noreferrer">공식 모델 단가</a> · 확인 {estimate.priceVersion}</p>}<p>구독 청구액이 아니며, 개별 관측값을 세션 전체 사용량으로 합산하지 않습니다.</p></details>;
}

type SubscriptionSetting = { amount: number | null; currency: string };

function SubscriptionForm({ api, setting, month, accountId, onBusy, onSaved, onError }: { api: AdminApi; setting: SubscriptionSetting; month: string; accountId: string; onBusy: (value: boolean) => void; onSaved: () => void; onError: (error: unknown) => void }) {
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState('');
  const [editor, setEditor] = useState(() => ({ amount: setting.amount == null ? '' : String(setting.amount), currency: setting.currency, basis: setting, dirty: false }));
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => { pending.current?.abort(); onBusy(false); }, [onBusy]);
  useEffect(() => {
    setEditor(previous => previous.dirty ? previous : { amount: setting.amount == null ? '' : String(setting.amount), currency: setting.currency, basis: setting, dirty: false });
  }, [setting.amount, setting.currency]);
  const changedRemotely = editor.dirty && (setting.amount !== editor.basis.amount || setting.currency !== editor.basis.currency);
  return <form className="cost-form" onSubmit={async event => {
    event.preventDefault(); if (busy) return;
    const raw = editor.amount.trim();
    if (raw && (!/^\d+(\.\d{1,6})?$/.test(raw) || Number(raw) > 1e9)) { setFeedback('금액은 0~10억, 소수점 6자리 이내로 입력하세요.'); return; }
    const saved = { amount: raw === '' ? null : Number(raw), currency: editor.currency };
    const controller = new AbortController(); pending.current = controller; setBusy(true); onBusy(true); setFeedback('');
    try {
      await api(`monitoring/accounts/${encodeURIComponent(accountId)}/costs/subscription`, { method: 'PUT', body: { month, ...saved }, signal: controller.signal });
      if (!controller.signal.aborted) { setEditor(previous => ({ ...previous, basis: saved, dirty: false })); setFeedback('저장했습니다. 이 월의 비용을 다시 계산합니다.'); onSaved(); }
    } catch (failure) { if (!controller.signal.aborted) { setFeedback(failure instanceof Error ? failure.message : '비용 기준 저장에 실패했습니다.'); onError(failure); } }
    finally { if (!controller.signal.aborted) { setBusy(false); onBusy(false); } }
  }}>
    <label>이 계정의 월 구독료<input name="amount" aria-label="월 구독료" type="number" min="0" max="1000000000" step="0.000001" value={editor.amount} onChange={event => { setEditor(previous => ({ ...previous, amount: event.target.value, dirty: true })); setFeedback(''); }} disabled={busy} placeholder="실제 월 금액" /></label>
    <label>통화<select name="currency" aria-label="구독료 통화" value={editor.currency} onChange={event => { setEditor(previous => ({ ...previous, currency: event.target.value, dirty: true })); setFeedback(''); }} disabled={busy}><option value="USD">USD · 달러</option><option value="KRW">KRW · 원</option></select></label>
    <button type="submit" className="button primary" disabled={busy}>{busy ? '저장 중…' : '비용 기준 저장'}</button>
    {changedRemotely && <p className="inline-note" role="status">서버 설정이 변경되었습니다. 입력값을 유지했습니다. 저장하면 현재 입력값을 적용합니다. <button type="button" className="text-button" disabled={busy} onClick={() => { setEditor({ amount: setting.amount == null ? '' : String(setting.amount), currency: setting.currency, basis: setting, dirty: false }); setFeedback(''); }}>서버 값 다시 불러오기</button></p>}
    <p>월마다 저장됩니다. 금액을 비우고 저장하면 해당 월 설정을 해제합니다.</p>
    {feedback && <p className="inline-note" role="status">{feedback}</p>}
  </form>;
}

export function CostPanel({ api, data, loading, error, month, onMonth, accountId, canConfigure, onSaved, onError }: { api: AdminApi; data: MonitoringCosts | null; loading: boolean; error: string; month: string; onMonth: (value: string) => void; accountId: string; canConfigure: boolean; onSaved: () => void; onError: (error: unknown) => void }) {
  const [busy, setBusy] = useState(false);
  const [lastSetting, setLastSetting] = useState<(SubscriptionSetting & { month: string }) | null>(null);
  useEffect(() => { if (data) setLastSetting({ month: data.month, amount: data.subscription.amount, currency: data.subscription.currency }); }, [data]);
  const subscription = data?.subscription;
  // Session and question selections refresh allocations but keep this account's draft mounted.
  const setting = subscription || (lastSetting?.month === month ? lastSetting : null);
  return <details className="cost-panel">
    <summary><span>비용 산정 · {month}</span><strong>{loading && !data ? '계산 중…' : error ? '비용 조회 실패' : money(subscription?.amount, subscription?.currency)}</strong><ChevronDown size={15} /></summary>
    <div className="cost-panel-body">
      <label className="cost-month">산정 월<input aria-label="비용 산정 월" type="month" required min="2000-01" max="2100-12" value={month} disabled={busy} onChange={event => { if (/^\d{4}-(0[1-9]|1[0-2])$/.test(event.target.value)) onMonth(event.target.value); }} /></label>
      {error && <p className="inline-note error" role="alert">{error}</p>}
      {subscription && <>
        {error && <p className="cost-description">기록된 질문 수는 이전 조회 기준입니다. 현재 배분액을 확인할 수 없습니다.</p>}
        <dl className="cost-allocation"><div><dt>기록된 질문 · 전체 CLI</dt><dd>{number(subscription.questionCount)}개 / {number(subscription.sessionCount)}세션</dd></div><div><dt>질문당 구독료 배분</dt><dd>{error ? '조회 실패' : subscription.perQuestion == null && subscription.amount != null ? '배분할 질문 없음' : money(subscription.perQuestion, subscription.currency)}</dd></div>{data.session && <div><dt>선택 세션 배분 · 해당 월 질문 {number(data.session.questionCount)}개</dt><dd>{error ? '조회 실패' : data.session.subscriptionAllocation == null && subscription.amount != null ? '해당 월 질문 없음' : money(data.session.subscriptionAllocation, subscription.currency)}</dd></div>}{data.question && <div><dt>선택 질문 배분</dt><dd>{error ? '조회 실패' : !data.question.inMonth ? '산정 월에 포함되지 않음' : money(data.question.subscriptionAllocation, subscription.currency)}</dd></div>}</dl>
        <p className="cost-description">월 구독료 ÷ 이 계정의 해당 월 기록된 질문 수. 세션에는 질문 수만큼 배분합니다. 산정 시간대는 {data.zone}이며 조회 날짜·CLI 필터와 별도로 계산합니다. 질문 수가 바뀌면 배분액도 달라집니다. 미수집 사용·세금·추가 결제는 자동 반영되지 않습니다.</p>
      </>}
      {canConfigure && setting ? <SubscriptionForm key={`${accountId}:${month}`} api={api} setting={setting} month={month} accountId={accountId} onBusy={setBusy} onSaved={onSaved} onError={onError} /> : !canConfigure && <p className="cost-description">월 구독료 설정은 관리자만 변경할 수 있습니다.</p>}
    </div>
  </details>;
}
