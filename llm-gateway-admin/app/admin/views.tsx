'use client';

import { Activity, ArrowDownLeft, ArrowUpRight, BarChart3, Clock3, Database, FileSearch, KeyRound, ShieldCheck, TriangleAlert } from 'lucide-react';
import type { Audit, Collector, Content, Filters, Ingestion, Overview, Paged, RequestDetail, RequestItem, Stats, MonitoringAccount } from './model';
import { billingLabel, costIncomplete, display, duration, equivalentIncomplete, number, outcomeLabel, sourceLabel, time, usd } from './model';

export function Badge({ value, children }: { value?: string; children?: React.ReactNode }) {
  const tone = ['success', 'reported', 'ok', 'active'].includes(value || '') ? 'good' : ['http_error', 'upstream_error', 'error', 'revoked'].includes(value || '') ? 'bad' : ['partial', 'missing', 'cancelled', 'stale', 'starting'].includes(value || '') ? 'warn' : 'neutral';
  return <span className={`badge ${tone}`}>{children || outcomeLabel(value)}</span>;
}
export function Empty({ title, detail }: { title: string; detail: string }) {
  return <div className="empty"><FileSearch size={28} strokeWidth={1.5} /><strong>{title}</strong><span>{detail}</span></div>;
}
export function Panel({ title, detail, children, action }: { title: string; detail?: string; children: React.ReactNode; action?: React.ReactNode }) {
  return <section className="panel"><div className="panel-header"><div><h2>{title}</h2>{detail && <p>{detail}</p>}</div>{action}</div>{children}</section>;
}
function Metric({ label, value, detail, icon: Icon, featured = false, warning = false }: { label: string; value: string; detail: string; icon: typeof Activity; featured?: boolean; warning?: boolean }) {
  return <div className={`metric ${featured ? 'featured' : ''}`}><div className="metric-heading"><span>{label}</span><Icon size={17} strokeWidth={1.7} /></div><strong>{value}</strong><small className={warning ? 'warning-text' : ''}>{detail}</small></div>;
}
function estimated(s: Stats) { return costIncomplete(s) && !Number(s.costUsd) ? '미확인' : usd(s.costUsd); }
function equivalent(s: Stats) { return equivalentIncomplete(s) && !Number(s.apiEquivalentCostUsd) ? '미확인' : usd(s.apiEquivalentCostUsd); }

export function OverviewView({ data, source }: { data: Overview; source: string }) {
  const s = data.totals;
  if (!s) return <Empty title="요약을 표시할 수 없습니다" detail="API 응답에 totals가 없습니다." />;
  const gatewayGroups = source === 'all' ? (data.bySource || []).filter(group => group.source === 'gateway') : [s];
  const gatewayIncomplete = gatewayGroups.some(costIncomplete);
  const cost = !Number(s.requests) || !gatewayGroups.length ? '—' : gatewayIncomplete && !Number(s.costUsd) ? '미확인' : usd(s.costUsd);
  const days = data.byDay || [];
  const max = Math.max(1, ...days.map(day => Number(day.requests) || 0));
  return <div className="view-stack">
    <div className="metric-grid">
      {source !== 'claude_code' && <Metric label={source === 'all' ? 'API 키 비용 소계' : '알려진 추정 비용'} value={cost} detail={gatewayIncomplete ? `불완전한 소계 · 단가 미확인 ${number(s.unpricedRequests || 0)}건` : 'USD · 등록된 단가 기준'} icon={ArrowUpRight} featured warning={gatewayIncomplete} />}
      {source !== 'gateway' && <Metric label="API 단가 환산액" value={!Number(s.requests) ? '—' : equivalent(s)} detail="구독 청구액이 아닌 참고값" icon={ArrowDownLeft} featured warning={equivalentIncomplete(s)} />}
      <Metric label="관측된 요청" value={number(s.requests || 0)} detail={`오류 ${number(s.errors || 0)} · 취소 ${number(s.cancelled || 0)}`} icon={Activity} />
      <Metric label="사용 토큰" value={number(s.totalTokens || 0)} detail={`입력 ${number(s.inputTokens || 0)} · 출력 ${number(s.outputTokens || 0)}`} icon={BarChart3} />
      <Metric label="응답 완료 · p95" value={duration(s.p95TotalMs)} detail="전체 소요 시간의 95백분위" icon={Clock3} />
    </div>
    {source === 'all' && <div className="inline-note"><TriangleAlert size={16} />Gateway와 Claude Code에 같은 작업이 기록될 수 있습니다. 비용과 환산액을 합산하지 않습니다.</div>}
    {source !== 'gateway' && <div className="inline-note"><TriangleAlert size={16} />환산액은 자기 신고된 사용량을 API 요율로 계산한 참고값입니다. 구독 결제 금액이나 잔여 한도가 아닙니다.</div>}
    {source !== 'gateway' && !!data.bySource?.length && <Panel title="소스별 기록" detail="API 키 비용과 환산액을 나누어 확인합니다."><div className="table-wrap"><table><thead><tr><th>소스 / 신고 모드</th><th className="right">요청</th><th className="right">API 키 비용</th><th className="right">API 환산액</th></tr></thead><tbody>{data.bySource.map((group, i) => <tr key={`${group.source}-${group.billingMode}-${i}`}><td><strong>{sourceLabel(group.source)}</strong><small>{billingLabel(group.billingMode)}</small></td><td className="right">{number(group.requests)}</td><td className="right">{group.source === 'gateway' ? estimated(group) : '해당 없음'}</td><td className="right">{group.source === 'claude_code' ? equivalent(group) : '해당 없음'}</td></tr>)}</tbody></table></div></Panel>}
    {!Number(s.requests) ? <Empty title="선택한 기간에 요청이 없습니다" detail="날짜와 트래픽 필터를 확인하거나 적재 상태를 살펴보세요." /> : <div className="overview-columns">
      <Panel title="일별 요청 흐름" detail="선택한 시간대 기준 · 요청 수" action={<span className="header-chip">{days.length}일 관측</span>}>
        {days.length ? <div className="chart"><div className="bars" role="img" aria-label={days.map(day => `${day.day}: ${day.requests}건`).join(', ')}>{days.map((day, i) => <div className="bar-column" key={`${day.day}-${i}`} title={`${day.day} · ${number(day.requests)}건`}><span style={{ height: `${Math.max(2, (Number(day.requests) / max) * 100)}%` }} className={i === days.length - 1 ? 'last' : ''} /></div>)}</div><div className="chart-axis"><span>{days[0].day}</span><span>최대 {number(max)}건</span><span>{days[days.length - 1].day}</span></div><p className="chart-foot">캐시 쓰기 {number(s.cacheWriteTokens)} · 읽기 {number(s.cacheReadTokens)} 토큰</p></div> : <Empty title="일별 데이터가 없습니다" detail="요청이 적재되면 이곳에 흐름이 나타납니다." />}
      </Panel>
      <Panel title="모델별 사용량" detail={source === 'claude_code' ? 'API 단가 환산액 · 구독 청구액 아님' : '모델마다 알려진 비용을 표시합니다.'}>
        <div className="table-wrap"><table><thead><tr><th>모델</th><th className="right">요청</th><th className="right">비용 · USD</th></tr></thead><tbody>{(data.byModel || []).map((model, i) => <tr key={`${model.provider}-${model.model}-${i}`}><td><strong>{display(model.model)}</strong><small>{display(model.provider)}</small></td><td className="right">{number(model.requests)}</td><td className="right">{source === 'claude_code' ? equivalent(model) : estimated(model)}{source === 'all' && <small>환산 {equivalent(model)}</small>}{source !== 'claude_code' && costIncomplete(model) && <small className="warning-text">불완전한 소계</small>}</td></tr>)}</tbody></table></div>{data.byModelTruncated && <p className="panel-note">모델 표는 최대 1,000개 그룹을 표시합니다. 전체 합계에는 모두 포함됩니다.</p>}
      </Panel>
    </div>}
  </div>;
}

export function RequestsView({ data, offset, loading, onPage, onDetail, zone }: { data: Paged<RequestItem>; offset: number; loading: boolean; onPage: (offset: number) => void; onDetail: (id: string) => void; zone: string }) {
  const items = data.items || [];
  return <Panel title="요청 기록" detail="최근 요청부터 표시합니다. 요청 ID를 선택하면 상세 내용을 볼 수 있습니다." action={<span className="header-chip">총 {number(data.total || 0)}건</span>}>
    {items.length ? <div className="table-wrap"><table className="requests-table"><thead><tr><th>요청 / 시각</th><th>소스 · 공급자 · 모델</th><th>결과</th><th className="right">토큰</th><th className="right">비용 / 환산액</th><th className="right">소요 시간</th><th>키 / 세션</th></tr></thead><tbody>{items.map((r, i) => <tr key={`${r.requestId}-${i}`}><td><button className="link-button mono" onClick={() => onDetail(r.requestId)}>{r.requestId}</button><small>{time(r.startedAt, zone)}</small></td><td><Badge value="neutral">{sourceLabel(r.source)}</Badge><strong>{display(r.model)}</strong><small>{display(r.provider)} · {billingLabel(r.billingMode)}</small></td><td><Badge value={r.outcome} /><small>{r.status == null ? 'HTTP 상태 미제공' : `HTTP ${r.status}`}</small></td><td className="right">{number(r.totalTokens)}</td><td className="right">{usd(r.source === 'claude_code' ? r.apiEquivalentCostUsd : r.costUsd)}<small>{r.source === 'claude_code' ? 'API 환산 · 청구액 아님' : 'API 키 비용'}</small>{['missing', 'partial'].includes(r.usageAvailability || '') && <small className="warning-text">사용량 {r.usageAvailability === 'missing' ? '미확인' : '부분'}</small>}</td><td className="right">{duration(r.totalMs)}</td><td className="mono">{display(r.keyId)}<small>{display(r.sessionId)}</small></td></tr>)}</tbody></table></div> : <Empty title="일치하는 요청이 없습니다" detail="필터는 정확히 일치하는 값으로 검색합니다. 날짜와 트래픽도 확인하세요." />}
    <div className="pagination"><span>{items.length ? `${number(offset + 1)}–${number(offset + items.length)} / ${number(data.total)}` : '0건'}</span><div><button className="button ghost" disabled={loading || offset === 0} onClick={() => onPage(Math.max(0, offset - 25))}>← 이전</button><button className="button ghost" disabled={loading || offset + 25 >= data.total || offset + 25 > 100000} onClick={() => onPage(offset + 25)}>다음 →</button></div></div>
  </Panel>;
}

export function DetailView({ data, canReadContent, zone }: { data: RequestDetail; canReadContent: boolean; zone: string }) {
  const r = data.request || {} as RequestItem;
  const telemetry = r.source === 'claude_code';
  const fields: [string, string][] = [
    ['사용량 소스', sourceLabel(r.source)], ['인증 / 청구 모드', billingLabel(r.billingMode)], ['Query source / Agent', `${display(r.querySource)} / ${display(r.agentType)}`],
    ['요청 ID', display(r.requestId)], ['공급자 / 모델', `${display(r.provider)} / ${display(r.model)}`], ['HTTP 상태 / 결과', `${display(r.status)} / ${outcomeLabel(r.outcome)}`],
    ['시각', time(r.timestamp || r.ts, zone)], ['메서드 / 경로', `${display(r.method)} ${display(r.path)}`], ['트래픽 / 스트림', `${display(r.traffic)} / ${r.stream == null ? '미제공' : r.stream ? 'SSE' : '일반'}`],
    ['키 ID', display(r.keyId)], ['세션 ID', display(r.sessionId)], ['공급자 요청 ID', display(r.upstreamRequestId)],
    ['일반 입력 / 출력 토큰', `${number(r.promptTokens)} / ${number(r.completionTokens)}`], [telemetry ? '캐시 쓰기 · 합계' : '캐시 쓰기 · 5분 / 1시간', telemetry ? number(r.cacheWriteTokens) : `${number(r.cacheWriteTokens)} / ${number(r.cacheWrite1hTokens)}`],
    ['캐시 읽기 / 전체 토큰', `${number(r.cacheReadTokens)} / ${number(r.totalTokens)}`], [telemetry ? 'API 단가 환산액' : 'API 키 추정 비용', usd(telemetry ? r.apiEquivalentCostUsd : r.costUsd)],
    ['완료 / 첫 응답 바이트', `${duration(r.totalMs)} / ${duration(r.ttftMs)}`], ['사용량 상태', display(r.usageAvailability)],
  ];
  return <><div className="detail-grid">{fields.map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}</div>
    {telemetry && <div className="inline-note">Claude Code의 인증 방식과 환산액은 자기 신고값입니다. 구독 청구액과 합산하지 않습니다.</div>}
    {(r.upstreamError || r.upstreamErrorReason) && <div className="inline-note warning-text">오류: {display(r.upstreamError)} · 분류: {display(r.upstreamErrorReason)} · 재시도 대기: {r.retryAfterSeconds == null ? '미확인' : `${r.retryAfterSeconds}초`}</div>}
    <p className="panel-note">첫 응답 바이트는 첫 생성 토큰 시간과 다릅니다. 본문 수집 범위와 토큰 사용량 상태도 별개입니다.</p>
    {canReadContent ? <div className="content-pair"><ContentPanel label="REQUEST · 요청" content={data.contents?.request} telemetry={telemetry} icon={<ArrowUpRight size={16} />} /><ContentPanel label="RESPONSE · 응답" content={data.contents?.response} telemetry={telemetry} icon={<ArrowDownLeft size={16} />} /></div> : <div className="inline-note">현재 역할은 요청 메타데이터만 조회할 수 있습니다. 요청·응답 본문 권한이 없습니다.</div>}
  </>;
}
function ContentPanel({ label, content, telemetry, icon }: { label: string; content?: Content; telemetry: boolean; icon: React.ReactNode }) {
  return <section className="content-panel"><div className="content-heading">{icon}<strong>{label}</strong></div>{telemetry ? <Empty title="본문 수집 대상 아님" detail="사용량 텔레메트리는 대화 본문을 수집하지 않습니다." /> : !content ? <Empty title="내용 기록 없음" detail="기록되지 않았거나 아직 적재되지 않았습니다." /> : <><div className="content-flags"><Badge value="neutral">{content.detail || '내용 기록'}</Badge>{content.truncated && <Badge value="partial">기록 한도 초과</Badge>}{content.partial && <Badge value="partial">부분 내용</Badge>}</div><pre>{content.text || '수집된 텍스트가 없습니다. 생략된 블록이 있을 수 있습니다.'}</pre></>}</section>;
}

export function IngestionView({ data, zone }: { data: Ingestion; zone: string }) {
  const statusNames: Record<string, string> = { ok: '정상', error: '적재 오류', stale: '상태 갱신 지연', unknown: '상태 미확인', starting: '시작 중' };
  return <div className="view-stack"><div className="metric-grid three"><Metric label="로그 수집 상태" value={data.enabled === false ? '비활성' : statusNames[data.status || ''] || '상태 미확인'} detail={data.running === true ? '수집 작업 실행 중' : data.running === false ? '수집 작업 대기 / 중지' : '작업 상태 미확인'} icon={Database} featured warning={['error', 'stale', 'unknown'].includes(data.status || '')} /><Metric label="마지막 성공" value={time(data.lastSuccessAt, zone)} detail="성공한 마지막 적재 작업" icon={ShieldCheck} /><Metric label="거부한 로그 줄" value={number(data.rejectedLines || 0)} detail="형식이나 크기 제한을 넘은 기록" icon={TriangleAlert} /></div><p className="section-note">마지막 상태 관측 {time(data.observedAt, zone)} · 상태 갱신 지연은 최신 실행 상태를 확인할 수 없다는 뜻입니다.</p>{data.lastError && <div className="inline-note warning-text">최근 오류 코드: {display(data.lastError)}</div>}
    {!!data.sources?.length && <Panel title="수집 소스 상태" detail="각 소스가 마지막으로 보고한 상태와 시각입니다."><div className="table-wrap"><table><thead><tr><th>소스</th><th>보고 상태</th><th>마지막 관측</th><th>마지막 성공</th></tr></thead><tbody>{data.sources.map((s, i) => <tr key={`${s.sourceId}-${i}`}><td>{display(s.sourceId)}</td><td><Badge value={s.stale ? 'stale' : s.state}>{s.stale ? '상태 갱신 지연' : statusNames[s.state || ''] || '상태 미확인'}</Badge></td><td>{time(s.observedAt, zone)}</td><td>{time(s.lastSuccessAt, zone)}</td></tr>)}</tbody></table></div></Panel>}
    <Panel title="파일별 진행 지점" detail="오프셋은 적재 완료 위치입니다. 파일 전체 크기나 남은 시간을 뜻하지 않습니다.">{data.files?.length ? <div className="table-wrap"><table><thead><tr><th>소스</th><th>파일</th><th className="right">완료 오프셋</th><th>업데이트</th></tr></thead><tbody>{data.files.map((f, i) => <tr key={`${f.sourceId}-${f.fileName}-${i}`}><td>{display(f.sourceId)}</td><td className="mono">{display(f.fileName)}</td><td className="right">{number(f.offsetBytes)} B</td><td>{time(f.updatedAt, zone)}</td></tr>)}</tbody></table></div> : <Empty title="아직 체크포인트가 없습니다" detail="소스 파일이 존재하는지, 적재 작업이 시작되었는지 확인하세요." />}</Panel>
  </div>;
}

export function CollectorsView({ data, offset, loading, onPage, onRevoke, onCreate, zone, accounts, accountPage }: { data: Paged<Collector>; offset: number; loading: boolean; onPage: (n: number) => void; onRevoke: (item: Collector) => void; onCreate: (name: string, days: number, billingMode: string, ownerAccountId: string, registrationId: string) => Promise<boolean>; zone: string; accounts: MonitoringAccount[]; accountPage: React.ReactNode }) {
  return <div className="view-stack"><Panel title="수집 키 발급" detail="사용량 전송 전용 키입니다. 발급 후 평문은 한 번만 표시됩니다."><form className="collector-form" onSubmit={async e => { e.preventDefault(); const form = e.currentTarget; const values = new FormData(form); if (await onCreate(String(values.get('name') || '').trim(), Number(values.get('days')), String(values.get('billingMode')), String(values.get('ownerAccountId') || ''), String(values.get('registrationId') || '').trim())) form.reset(); }}><label>소유 계정<select name="ownerAccountId" required defaultValue=""><option value="" disabled>계정을 선택하세요</option>{accounts.map(account => <option key={account.id} value={account.id}>{account.name}</option>)}</select></label><label>등록 ID · 선택<input name="registrationId" placeholder="키 교체 시 기존 등록 ID 재사용" maxLength={120} /></label><label>키 이름<input name="name" required maxLength={80} placeholder="예: developer-laptop" /></label><label>유효 기간 · 일<input name="days" type="number" min="1" max="365" defaultValue="30" required /></label><label>신고 모드<select name="billingMode" defaultValue="subscription_declared"><option value="subscription_declared">구독 · 자기 신고</option><option value="api_key_declared">API 키 · 자기 신고</option><option value="unknown">미확인</option></select></label><button className="button primary" type="submit"><KeyRound size={16} /> 수집 키 발급</button></form><p className="panel-note">계정을 찾을 수 없으면 계정 페이지를 넘기거나 새 계정을 등록하세요. 등록 ID는 생략하면 새로 생성됩니다.</p>{accountPage}</Panel>
    <Panel title="조직 수집 키" detail="폐기한 키는 이후 전송에 사용할 수 없습니다. 평문 키는 다시 조회할 수 없습니다." action={<span className="header-chip">총 {number(data.total || 0)}개</span>}>{data.items?.length ? <div className="table-wrap"><table><thead><tr><th>이름 / 접두부</th><th>소유 계정 / 등록 ID</th><th>신고 모드</th><th>상태</th><th>생성 / 생성자</th><th>만료 / 마지막 사용</th><th>관리</th></tr></thead><tbody>{data.items.map(item => <tr key={item.id}><td><strong>{display(item.name)}</strong><small className="mono">{display(item.prefix)}</small></td><td>{accounts.find(account => account.id === item.ownerAccountId)?.name || item.ownerAccountId || '계정 미연결'}<small className="mono">{display(item.registrationId)}</small></td><td>{billingLabel(item.billingMode)}</td><td><Badge value={item.revokedAt ? 'revoked' : item.expiresAt && Date.parse(item.expiresAt) <= Date.now() ? 'stale' : 'active'}>{item.revokedAt ? '폐기됨' : item.expiresAt && Date.parse(item.expiresAt) <= Date.now() ? '만료됨' : '사용 가능'}</Badge></td><td>{time(item.createdAt, zone)}<small>{display(item.createdBy)}</small></td><td>{time(item.expiresAt, zone)}<small>마지막 사용 {time(item.lastUsedAt, zone)}</small></td><td>{item.revokedAt ? '폐기 완료' : <button className="button ghost compact" onClick={() => onRevoke(item)}>폐기</button>}</td></tr>)}</tbody></table></div> : <Empty title="발급된 수집 키가 없습니다" detail="조직의 사용량을 전송할 키를 발급하세요." />}<Pagination data={data} offset={offset} loading={loading} onPage={onPage} unit="개" /></Panel>
  </div>;
}
const auditActions: Record<string, string> = {
  'collector.create': '수집 키 발급', 'collector.revoke': '수집 키 폐기', 'collector.authenticate': '수집 키 인증',
  'content.read': '본문 열람', 'access.denied': '접근 거부', 'monitoring.cost.configure': '비용 기준 설정', 'monitoring.account.create': '모니터링 계정 등록',
};
export function AuditView({ data, offset, loading, onPage, zone }: { data: Paged<Audit>; offset: number; loading: boolean; onPage: (n: number) => void; zone: string }) {
  return <Panel title="감사 기록" detail="누가 본문을 열람하고 관리 작업을 수행했는지 확인합니다. 질문 원문은 구독 모니터링에서 볼 수 있습니다." action={<span className="header-chip">총 {number(data.total || 0)}건</span>}>
    {data.items?.length ? <div className="table-wrap"><table><thead><tr><th>시각</th><th>작업자</th><th>작업</th><th>대상</th><th>결과</th></tr></thead><tbody>{data.items.map((item, i) => <tr key={`${item.time}-${i}`}><td>{time(item.time, zone)}</td><td>{display(item.actor)}</td><td><strong>{auditActions[item.action || ''] || display(item.action)}</strong><small className="mono">{display(item.action)}</small></td><td className="mono">{display(item.target)}</td><td><Badge value={item.outcome}>{item.outcome === 'success' ? '성공' : item.outcome === 'denied' ? '거부됨' : display(item.outcome)}</Badge></td></tr>)}</tbody></table></div> : <Empty title="감사 기록이 없습니다" detail="본문 열람과 기록된 관리 작업이 표시됩니다." />}
    <Pagination data={data} offset={offset} loading={loading} onPage={onPage} unit="건" />
  </Panel>;
}
function Pagination<T>({ data, offset, loading, onPage, unit }: { data: Paged<T>; offset: number; loading: boolean; onPage: (n: number) => void; unit: string }) {
  return <div className="pagination"><span>{data.items?.length ? `${number(offset + 1)}–${number(offset + data.items.length)} / ${number(data.total)}${unit}` : `총 ${number(data.total || 0)}${unit}`}</span><div><button className="button ghost" disabled={loading || offset === 0} onClick={() => onPage(Math.max(0, offset - 25))}>← 이전</button><button className="button ghost" disabled={loading || offset + 25 >= data.total || offset + 25 > 100000} onClick={() => onPage(offset + 25)}>다음 →</button></div></div>;
}
