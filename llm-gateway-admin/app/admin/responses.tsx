import type { MonitoringResponse, ResponseAvailability } from './model';
import { sourceLabel, time } from './model';
import { Badge } from './views';
import './responses.css';

function Availability({ value, waiting = false }: { value: ResponseAvailability; waiting?: boolean }) {
  const messages = {
    available: '',
    not_collected: waiting ? '답변 수신 대기 · 본문 미수집. 아직 도착하지 않았거나 답변 수집이 설정되지 않았습니다.' : '답변 본문 미수집. 빈 응답이거나 답변 본문 수집이 설정되지 않았습니다.',
    permission_required: '답변 본문 조회 권한이 필요합니다.',
    purged: '답변 본문이 보관 정책에 따라 삭제되었습니다.',
  };
  return <p className={`response-state response-state-${value}`} role="status">{messages[value]}</p>;
}

export function ResponseList({ responses, availability = 'not_collected', canReadContent, zone, session = false }: { responses: MonitoringResponse[]; availability?: ResponseAvailability; canReadContent: boolean; zone: string; session?: boolean }) {
  return <section className={`response-list ${session ? 'session-response-list' : ''}`} aria-label={session ? '수신된 답변 본문' : '질문에 연결된 답변'}>
    <div className="response-list-heading"><h3>{session ? '수신된 답변' : '답변'}</h3><span>{responses.length}건 · 수신 순서</span></div>
    {!responses.length ? <Availability value={canReadContent ? availability : 'permission_required'} waiting /> : responses.map((item, index) => {
      const state = !canReadContent ? 'permission_required' : item.contentAvailability === 'available' && !item.content?.text ? 'not_collected' : item.contentAvailability;
      return <article className="response-card" key={item.id}>
        <header><strong>답변 {index + 1}{item.sequence != null && <span> · 순서 {item.sequence}</span>}</strong><span>{time(item.emittedAt, zone)}</span></header>
        <div className="response-metadata"><span>{sourceLabel(item.source)}</span><span>{item.model || '모델 미확인'}</span>{session && (item.questionId ? <span className="response-link" title={item.questionId}>질문 연결됨 · {item.questionId}</span> : <Badge value="partial">질문 연결 안 됨</Badge>)}</div>
        {state === 'available' && item.content ? <>{item.content.truncated && <Badge value="partial">길이 제한 · 일부 답변만 수집됨</Badge>}<pre className="response-text">{item.content.text}</pre></> : <Availability value={state} />}
      </article>;
    })}
  </section>;
}
