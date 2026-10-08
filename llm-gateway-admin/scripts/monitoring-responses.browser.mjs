// Synthetic API fixture only. Run against the existing dev server without restarting it:
// PLAYWRIGHT_MODULE=/tmp/response-monitoring-browser/node_modules/playwright/index.mjs node scripts/monitoring-responses.browser.mjs
import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const stamp = '2026-10-08T01:00:00Z';
const account = { id: 'fixture-account', name: '응답 검증 fixture', sessionCount: 1, questionCount: 4 };
const session = { id: 'fixture-session', accountId: account.id, externalSessionId: 'answer-fixture', source: 'claude_code', questionCount: 4, firstSeenAt: stamp, lastSeenAt: stamp };
const questions = ['multi', 'waiting', 'purged', 'restricted'].map(id => ({ id, source: 'claude_code', startedAt: stamp, model: 'fixture-model', attribution: 'linked' }));
const response = (id, questionId, text, availability = 'available', truncated = false) => ({ id, questionId, source: 'claude_code', emittedAt: stamp, sequence: Number(id.replace(/\D/g, '')) || 1, model: 'fixture-model', attribution: questionId ? 'linked' : 'unattributed', content: text == null ? null : { text, truncated }, contentAvailability: availability });
const malicious = '<img src=x onerror="window.fixtureExecuted=true"><script>window.fixtureExecuted=true</script>';
let failMetadata = false, failQuestionMetadata = false, questionRemoved = false, questionListReads = 0, offPage = false, bodyPurged = false, sessionAbsent = false, sessionPolls = 0, selectedPolls = 0;
let late = false, extraSessionResponse = false, viewer = false, delayPurged = false, purgedStarted = false, failResponses = false, questionReads = 0, responseReads = 0;
let questionPages = false, delayQuestionList = false, questionListStarted = false;
const pageTwoQuestion = { id: 'page-two', source: 'claude_code', startedAt: stamp, model: 'fixture-model', attribution: 'linked', responseRevision: '1' };
const checks = [];
const allResponses = () => [response('r1', 'multi', malicious), response('r2', 'multi', '긴 답변 ' + '가'.repeat(1000), 'available', true), ...(late ? [response('r3', 'multi', '늦게 수신된 답변')] : []), response('r4', 'purged', null, 'purged'), response('r5', 'restricted', null, 'permission_required'), response('r6', null, '질문 연결 없이 수신된 답변'), response('r7', null, null, 'not_collected'), ...(extraSessionResponse ? [response('r8', null, '세션에 새로 수신된 답변')] : [])];
const currentQuestions = () => questions.filter(item => !questionRemoved || item.id !== 'multi').map(item => ({ ...item, responseRevision: item.id === 'multi' && late ? '2' : '1' }));
const failures = [], unexpected = [];
page.on('pageerror', e => failures.push(e.message));
await page.route('**/admin/auth/**', route => route.fulfill({ json: { mode: 'enterprise' } }));
await page.route('**/admin/api/**', async route => {
  const request = route.request(), url = new URL(request.url()), path = url.pathname.replace('/admin/api/', '');
  const send = json => route.fulfill({ json });
  if (request.method() !== 'GET') { unexpected.push(request.method() + ' ' + path); return route.fulfill({ status: 405 }); }
  if (path === 'session') return send({ authenticated: true, mode: 'enterprise', tenantId: 'fixture', subject: 'fixture', permissions: viewer ? ['usage:read'] : ['usage:read', 'content:read'] });
  if (path === 'monitoring/status') return send({ ready: true, stored: late ? 9 : 8, lastReceivedAt: late ? '2026-10-08T01:00:02Z' : stamp });
  if (path === 'monitoring/accounts') return send({ items: [account], total: 1 });
  if (path === 'monitoring/sessions') {
    const metadata = { ...session, responseRevision: questionRemoved ? '5' : bodyPurged ? '4' : extraSessionResponse ? '3' : late ? '2' : '1' };
    if (url.searchParams.has('sessionId')) {
      selectedPolls++;
      if (failMetadata) return route.fulfill({ status: 503, json: { error: { code: 'fixture_metadata_retry' } } });
      assert.equal(url.searchParams.get('sessionId'), session.id);
      assert.equal(url.searchParams.get('accountId'), account.id);
      assert.equal(url.searchParams.get('limit'), '1');
      assert.equal(url.searchParams.has('offset'), false);
      return send({ items: sessionAbsent ? [] : [metadata], total: sessionAbsent ? 0 : 1 });
    }
    sessionPolls++;
    return send({ items: offPage ? Array.from({ length: 25 }, (_, i) => ({ ...session, id: `other-${i}`, externalSessionId: `other-${i}`, responseRevision: '1' })) : [metadata], total: offPage ? 26 : 1 });
  }
  if (path.endsWith('/questions') && path.includes('/sessions/')) {
    questionListReads++;
    if (failQuestionMetadata) return route.fulfill({ status: 503, json: { error: { code: 'fixture_question_metadata_retry' } } });
    const offset = Number(url.searchParams.get('offset'));
    const items = questionPages ? offset ? [pageTwoQuestion] : [...currentQuestions(), ...Array.from({ length: 21 }, (_, i) => ({ ...pageTwoQuestion, id: `filler-${i}` }))] : currentQuestions();
    const total = questionPages ? 26 : items.length;
    if (!offset && delayQuestionList) { questionListStarted = true; await new Promise(resolve => setTimeout(resolve, 700)); }
    return send({ items, total });
  }
  if (path.endsWith('/events')) return send({ items: [], total: 0 });
  if (path.endsWith('/responses')) {
    responseReads++;
    if (failResponses) return route.fulfill({ status: 503, json: { error: { code: 'fixture_retry' } } });
    const offset = Number(url.searchParams.get('offset')), limit = Number(url.searchParams.get('limit'));
    assert.equal(limit, 25); assert.ok(Number.isInteger(offset) && offset >= 0);
    const items = allResponses().map(item => bodyPurged ? { ...item, content: null, contentAvailability: 'purged' } : viewer ? { ...item, content: null, contentAvailability: 'permission_required' } : item);
    // Supply a second page to verify the real paging boundary rather than only button presence.
    return send({ items: offset ? [response('r26', null, viewer ? null : '답변 두 번째 페이지', viewer ? 'permission_required' : 'available')] : items, total: 26 });
  }
  if (path.startsWith('monitoring/questions/')) {
    questionReads++;
    const id = path.split('/').at(-1), items = allResponses().filter(item => item.questionId === id);
    if (id === 'purged' && delayPurged) { purgedStarted = true; await new Promise(resolve => setTimeout(resolve, 700)); }
    return send({ question: currentQuestions().find(item => item.id === id) || (id === pageTwoQuestion.id ? pageTwoQuestion : null), content: { text: `합성 질문 원문 ${id}`, truncated: false }, contentAvailability: 'available', responses: items, responseAvailability: id === 'waiting' ? 'not_collected' : id === 'purged' ? 'purged' : id === 'restricted' ? 'permission_required' : 'available' });
  }
  if (path.endsWith('/costs')) return send({ month: url.searchParams.get('month'), zone: 'Asia/Seoul', subscription: { amount: 20, currency: 'USD', allocationBasis: 'recorded_questions', questionCount: 4, sessionCount: 1, perQuestion: 5 }, session: { id: session.id, questionCount: 4, subscriptionAllocation: 20, latestObservation: null }, question: url.searchParams.has('questionId') ? { id: url.searchParams.get('questionId'), inMonth: true, subscriptionAllocation: 5 } : null });
  unexpected.push(path); return route.fulfill({ status: 404, json: { error: { code: 'fixture_unknown' } } });
});
const until = async predicate => {
  const deadline = Date.now() + 7000;
  while (Date.now() < deadline) { if (await predicate()) return; await page.waitForTimeout(80); }
  throw new Error('browser fixture assertion timed out');
};
const enter = async () => {
  await page.goto(process.env.ADMIN_URL || 'http://127.0.0.1:3000/admin');
  await page.getByRole('button', { name: '응답 검증 fixture 계정 선택', exact: true }).click();
  await page.getByRole('button', { name: 'Claude Code 세션 answer-fixture 열기', exact: true }).click();
};
const questionView = page.locator('.question-reader');
try {
  await enter();
  await page.getByRole('button', { name: '질문 1 보기', exact: true }).click();
  await until(async () => await questionView.locator('.response-card').count() === 2);
  assert.ok((await questionView.innerText()).includes(malicious));
  assert.equal(await questionView.locator('img, script').count(), 0);
  assert.equal(await page.evaluate(() => window.fixtureExecuted), undefined);
  assert.ok((await questionView.innerText()).includes('길이 제한'));
  checks.push('multiple responses, escaped markup, truncation');
  const unchangedQuestionReads = questionReads;
  await page.waitForTimeout(2300);
  assert.equal(questionReads, unchangedQuestionReads);
  checks.push('unchanged question metadata avoids additional content audits');
  late = true;
  await until(async () => (await questionView.innerText()).includes('늦게 수신된 답변'));
  checks.push('active question late response refresh');
  failQuestionMetadata = true;
  await page.locator('.question-rail [role="alert"]').waitFor();
  questionRemoved = true;
  const failedQuestionPolls = questionListReads;
  await until(async () => questionListReads > failedQuestionPolls);
  assert.ok((await questionView.innerText()).includes('합성 질문 원문 multi'));
  assert.ok((await questionView.innerText()).includes('늦게 수신된 답변'));
  assert.ok(!(await questionView.innerText()).includes('삭제'));
  checks.push('failed question metadata retains selected bodies without claiming purge');
  const readsBeforeQuestionRemoval = questionReads;
  failQuestionMetadata = false;
  await until(async () => await page.getByRole('button', { name: '질문 4 보기', exact: true }).count() === 0);
  await until(async () => await questionView.locator('.prompt-text, .response-text').count() === 0);
  assert.ok(!(await questionView.innerText()).includes('합성 질문 원문 multi'));
  assert.ok(!(await questionView.innerText()).includes('늦게 수신된 답변'));
  assert.equal(questionReads, readsBeforeQuestionRemoval, 'successful question disappearance must clear protected bodies without an extra audited read');
  checks.push('removed selected question hides question and answers while session remains');
  questionRemoved = false;
  await page.getByRole('button', { name: '질문 4 보기', exact: true }).waitFor();
  questionPages = true;
  const questionRail = page.getByRole('complementary', { name: '세션 질문 목록', exact: true });
  await until(async () => await questionRail.getByRole('button', { name: '다음 페이지', exact: true }).isEnabled());
  await page.getByRole('button', { name: '질문 1 보기', exact: true }).click();
  await until(async () => (await questionView.innerText()).includes('합성 질문 원문 multi'));
  delayQuestionList = true;
  await until(async () => questionListStarted);
  await questionRail.getByRole('button', { name: '다음 페이지', exact: true }).click();
  assert.equal(await questionView.locator('.prompt-text, .response-text').count(), 0);
  await page.getByRole('button', { name: '질문 26 보기', exact: true }).click();
  await until(async () => (await questionView.innerText()).includes('합성 질문 원문 page-two'));
  await page.waitForTimeout(900);
  assert.ok((await questionView.innerText()).includes('합성 질문 원문 page-two'), 'a delayed previous-page list must not clear a newer question selection');
  delayQuestionList = false;
  questionPages = false;
  await questionRail.getByRole('button', { name: '이전 페이지', exact: true }).click();
  assert.equal(await questionView.locator('.prompt-text, .response-text').count(), 0);
  await page.getByRole('button', { name: '질문 4 보기', exact: true }).waitFor();
  checks.push('question page changes clear selection and delayed old metadata cannot clear a newer selection');
  for (const [index, expected] of [[2, '대기'], [3, '삭제'], [4, '권한']]) {
    await page.getByRole('button', { name: `질문 ${index} 보기`, exact: true }).click();
    await until(async () => (await questionView.innerText()).includes(expected));
  }
  checks.push('not collected, purged, permission states');
  delayPurged = true;
  await page.getByRole('button', { name: '질문 3 보기', exact: true }).click();
  await until(async () => purgedStarted);
  await page.getByRole('button', { name: '질문 4 보기', exact: true }).click();
  await until(async () => (await questionView.innerText()).includes('합성 질문 원문 restricted'));
  await page.waitForTimeout(900);
  assert.ok((await questionView.innerText()).includes('합성 질문 원문 restricted'));
  assert.ok(!(await questionView.innerText()).includes('합성 질문 원문 purged'));
  checks.push('delayed previous question cannot restore stale content');
  await page.getByRole('button', { name: '답변 기록', exact: true }).click();
  const answers = page.getByRole('region', { name: '세션 답변 목록', exact: true });
  await until(async () => (await answers.innerText()).includes('질문 연결 안 됨'));
  assert.ok((await answers.innerText()).includes('본문 미수집'));
  await answers.getByRole('button', { name: '다음 페이지', exact: true }).click();
  await until(async () => (await answers.innerText()).includes('답변 두 번째 페이지'));
  await answers.getByRole('button', { name: '이전 페이지', exact: true }).click();
  await until(async () => (await answers.innerText()).includes('질문 연결 없이 수신된 답변'));
  const unchangedResponseReads = responseReads;
  await page.waitForTimeout(2300);
  assert.equal(responseReads, unchangedResponseReads);
  offPage = true;
  const pollsBeforeOffPage = sessionPolls;
  await until(async () => sessionPolls >= pollsBeforeOffPage + 2);
  assert.equal(responseReads, unchangedResponseReads, 'leaving the current list page must not re-read unchanged bodies');
  extraSessionResponse = true;
  await until(async () => (await answers.innerText()).includes('세션에 새로 수신된 답변'));
  checks.push('off-page active session new response and unchanged revision avoids audits');
  bodyPurged = true;
  await until(async () => await answers.locator('.response-text').count() === 0);
  assert.ok((await answers.innerText()).includes('삭제'));
  const purgedReads = responseReads;
  const metadataPolls = selectedPolls;
  await until(async () => selectedPolls >= metadataPolls + 2);
  assert.equal(responseReads, purgedReads);
  bodyPurged = false;
  await until(async () => (await answers.innerText()).includes('세션에 새로 수신된 답변'));
  sessionAbsent = true;
  await until(async () => (await page.locator('.session-inspector').innerText()).includes('선택한 세션을 조회할 수 없습니다'));
  assert.equal(await page.locator('.session-inspector .response-text').count(), 0);
  sessionAbsent = false;
  await until(async () => (await answers.innerText()).includes('세션에 새로 수신된 답변'));
  checks.push('off-page body purge and absent session hide stale bodies');
  failMetadata = true;
  const readsBeforeMetadataFailure = responseReads;
  await page.getByRole('button', { name: '세션 다시 조회', exact: true }).waitFor();
  assert.equal(responseReads, readsBeforeMetadataFailure);
  failMetadata = false;
  await page.getByRole('button', { name: '세션 다시 조회', exact: true }).click();
  await until(async () => await page.getByRole('button', { name: '세션 다시 조회', exact: true }).count() === 0);
  checks.push('selected metadata failure keeps revision stable and retries');
  failResponses = true;
  await page.getByRole('button', { name: '새로고침', exact: true }).click();
  await answers.getByRole('button', { name: '답변 다시 조회', exact: true }).waitFor();
  failResponses = false;
  await answers.getByRole('button', { name: '답변 다시 조회', exact: true }).click();
  await until(async () => await answers.getByRole('button', { name: '답변 다시 조회', exact: true }).count() === 0);
  checks.push('answer endpoint failure retries');
  const readsBefore = responseReads;
  await page.getByRole('button', { name: '2초 자동 갱신', exact: true }).click();
  bodyPurged = true;
  const pausedPolls = selectedPolls;
  await page.waitForTimeout(2300);
  assert.equal(responseReads, readsBefore);
  assert.equal(selectedPolls, pausedPolls);
  await page.getByRole('button', { name: '새로고침', exact: true }).click();
  await until(async () => await answers.locator('.response-text').count() === 0);
  assert.ok((await answers.innerText()).includes('삭제'));
  checks.push('session unlinked responses, pagination, pause');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(100);
  assert.ok(await answers.evaluate(element => element.scrollWidth <= element.clientWidth + 1));
  await page.screenshot({ path: process.env.SCREENSHOT_PATH || '/tmp/response-monitoring-mobile.png', fullPage: true });
  checks.push('390px answer layout');
  offPage = false;
  bodyPurged = false;
  viewer = true;
  await enter();
  await page.getByRole('button', { name: '질문 1 보기', exact: true }).click();
  const readsBeforeViewer = questionReads;
  await until(async () => (await questionView.innerText()).includes('권한'));
  assert.equal(await questionView.locator('.response-text').count(), 0);
  await page.getByRole('button', { name: '답변 기록', exact: true }).click();
  await until(async () => (await answers.innerText()).includes('권한'));
  assert.equal(await answers.locator('.response-text').count(), 0);
  assert.equal(questionReads, readsBeforeViewer);
  checks.push('viewer no body text or question content read');
  assert.deepEqual(unexpected, []);
  assert.deepEqual(failures, []);
  console.log(JSON.stringify({ passed: true, checks, questionReads, responseReads, isolatedApi: true }, null, 2));
} finally { await browser.close(); }
