// Synthetic enterprise fixture: run with ADMIN_URL and optional PLAYWRIGHT_MODULE.
// Catches accidental body prefetches, layout-triggered rereads, and lost selections.
import assert from 'node:assert/strict';
const { mkdir } = await import('node:fs/promises');
const screenshots = process.env.SCREENSHOT_DIR;
if (screenshots) await mkdir(screenshots, { recursive: true });
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
page.setDefaultTimeout(7000);
const stamp = '2026-10-08T01:00:00Z';
const accounts = [
  { id: 'inbox-account-a', name: '읽기 검증 A', sessionCount: 2, questionCount: 2 },
  { id: 'inbox-account-b', name: '읽기 검증 B', sessionCount: 1, questionCount: 1 },
];
const sessions = ['a1', 'a2', 'b1'].map((id, index) => ({
  id: `inbox-session-${id}`, accountId: accounts[index < 2 ? 0 : 1].id,
  externalSessionId: `inbox-${id}`, source: 'claude_code', questionCount: 1,
  firstSeenAt: stamp, lastSeenAt: stamp, responseRevision: '1',
}));
const questions = sessions.map((session, index) => ({
  id: `inbox-question-${index + 1}`, sessionId: session.id, source: 'claude_code',
  startedAt: stamp, model: 'inbox-fixture-model', attribution: 'linked',
  usageAvailability: 'missing', responseRevision: '1',
}));
const prompt = id => `보호된 합성 질문 본문 ${id}`;
const answer = id => `보호된 합성 답변 본문 ${id}\n${'긴본문검증'.repeat(80)}`;
const response = question => ({
  id: `answer-${question.id}`, questionId: question.id, source: 'claude_code',
  emittedAt: stamp, sequence: 1, model: 'inbox-fixture-model', attribution: 'linked',
  content: { text: answer(question.id), truncated: false }, contentAvailability: 'available',
});
let questionReads = 0, responseReads = 0;
const failures = [], unexpected = [], checks = [];
page.on('pageerror', error => failures.push(error.message));
await page.route('**/admin/auth/**', route => route.fulfill({ json: { mode: 'enterprise' } }));
await page.route('**/admin/api/**', async route => {
  const request = route.request(), url = new URL(request.url());
  const path = url.pathname.replace('/admin/api/', '');
  const send = json => route.fulfill({ json });
  if (request.method() !== 'GET') {
    unexpected.push(`${request.method()} ${path}`);
    return route.fulfill({ status: 405 });
  }
  if (path === 'session') return send({ authenticated: true, mode: 'enterprise', tenantId: 'inbox-fixture', subject: 'inbox-fixture', permissions: ['usage:read', 'content:read'] });
  if (path === 'monitoring/status') return send({ ready: true, stored: 3, lastReceivedAt: stamp });
  if (path === 'monitoring/accounts') return send({ items: accounts, total: accounts.length });
  if (path === 'monitoring/sessions') {
    const selectedId = url.searchParams.get('sessionId');
    const items = sessions.filter(item => item.accountId === url.searchParams.get('accountId') && (!selectedId || item.id === selectedId));
    return send({ items, total: items.length });
  }
  const sessionList = /^monitoring\/sessions\/([^/]+)\/(questions|events|responses)$/.exec(path);
  if (sessionList) {
    const items = questions.filter(item => item.sessionId === sessionList[1]);
    if (sessionList[2] === 'questions') return send({ items, total: items.length });
    if (sessionList[2] === 'events') return send({ items: [], total: 0 });
    responseReads++;
    return send({ items: items.map(response), total: items.length });
  }
  if (path.startsWith('monitoring/questions/')) {
    questionReads++;
    const question = questions.find(item => item.id === path.split('/').at(-1));
    assert.ok(question, 'only fixture question IDs may be read');
    return send({ question, content: { text: prompt(question.id), truncated: false }, contentAvailability: 'available', responses: [response(question)], responseAvailability: 'available' });
  }
  if (/^monitoring\/accounts\/[^/]+\/costs$/.test(path)) return send({
    month: url.searchParams.get('month'), zone: 'Asia/Seoul',
    subscription: { amount: null, currency: 'USD', allocationBasis: 'recorded_questions', questionCount: 2, sessionCount: 2, perQuestion: null },
    session: null, question: null,
  });
  unexpected.push(path);
  return route.fulfill({ status: 404, json: { error: { code: 'unexpected_inbox_fixture_endpoint' } } });
});
const until = async (predicate, message) => {
  const deadline = Date.now() + 7000;
  while (Date.now() < deadline) { if (await predicate()) return; await page.waitForTimeout(50); }
  assert.fail(message);
};
const reader = page.locator('.question-reader');
const chooseAccount = name => page.getByRole('button', { name: `${name} 계정 선택`, exact: true }).click();
const chooseSession = name => page.getByRole('button', { name: `Claude Code 세션 ${name} 열기`, exact: true }).click();
const readQuestion = async id => {
  await page.getByRole('button', { name: '질문 1 보기', exact: true }).click();
  await until(async () => (await reader.innerText()).includes(answer(id)), 'selected question and answer must load');
};
try {
  await page.goto(process.env.ADMIN_URL || 'http://127.0.0.1:3000/admin');
  await page.getByRole('button', { name: `${accounts[0].name} 계정 선택`, exact: true }).waitFor();
  for (const account of accounts) assert.equal(await page.getByRole('button', { name: `${account.name} 계정 선택`, exact: true }).isVisible(), true);
  await chooseAccount(accounts[0].name);
  await chooseSession('inbox-a1');
  await page.getByRole('button', { name: '질문 1 보기', exact: true }).waitFor();
  assert.equal(questionReads, 0, 'metadata navigation must not read protected questions');
  assert.equal(responseReads, 0, 'metadata navigation must not read session answer bodies');
  assert.equal(await page.locator('.prompt-text, .response-text').count(), 0);
  await readQuestion(questions[0].id);
  assert.equal(questionReads, 1, 'explicit selection must perform one protected read');
  checks.push('protected bodies load only after explicit question selection');
  if (screenshots) await page.screenshot({ path: `${screenshots}/inbox-desktop.png`, fullPage: true });
  const entry = page.getByRole('button', { name: '집중해서 읽기', exact: true });
  assert.equal(await entry.count(), 1, 'selected question must offer the focus reader control');
  await entry.click();
  await page.locator('.monitoring-view.is-focused').waitFor();
  assert.equal(await page.locator('.session-rail').isVisible(), false);
  assert.equal(await page.locator('.question-rail').isVisible(), false);
  assert.equal(await reader.isVisible(), true);
  if (screenshots) await page.screenshot({ path: `${screenshots}/focus-desktop.png`, fullPage: true });
  assert.ok((await reader.innerText()).includes(answer(questions[0].id)));
  await page.getByRole('button', { name: '탐색으로 돌아가기', exact: true }).click();
  assert.equal(await page.locator('.monitoring-view.is-focused').count(), 0);
  assert.equal(await page.locator('.session-rail').isVisible(), true);
  assert.equal(await page.locator('.question-rail').isVisible(), true);
  assert.equal(questionReads, 1, 'focus toggles must not reread protected bodies');
  assert.equal(responseReads, 0);
  checks.push('focus hides rails and preserves selected bodies without an audit read');
  await entry.click();
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('.monitoring-view.is-focused').count(), 0);
  assert.equal(await entry.evaluate(element => document.activeElement === element), true);
  assert.equal(questionReads, 1, 'Escape must not reread protected bodies');
  checks.push('Escape exits focus and restores focus to the entry control');
  for (const selector of ['.session-rail', '.question-rail']) {
    const text = await page.locator(selector).innerText();
    assert.ok(!text.includes(prompt(questions[0].id)) && !text.includes(answer(questions[0].id)), 'navigation must not expose protected body excerpts');
  }
  checks.push('navigation shows metadata without protected body excerpts');
  await chooseSession('inbox-a2');
  assert.equal(await page.locator('.prompt-text, .response-text').count(), 0, 'switching sessions must clear protected bodies');
  assert.equal(questionReads, 1);
  await readQuestion(questions[1].id);
  await page.getByRole('button', { name: '계정 변경', exact: true }).click();
  await chooseAccount(accounts[1].name);
  assert.equal(await page.locator('.prompt-text, .response-text').count(), 0, 'switching accounts must clear protected bodies');
  assert.equal(questionReads, 2);
  await chooseSession('inbox-b1');
  await readQuestion(questions[2].id);
  assert.equal(questionReads, 3);
  checks.push('session and account switches clear prior bodies without automatic reads');
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    for (const focused of [false, true]) {
      if (focused) await entry.click();
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, `${width}px ${focused ? 'focus' : 'inbox'} must fit the viewport`);
      assert.equal(await reader.evaluate(element => element.scrollWidth <= element.clientWidth + 1), true, `${width}px reader must contain long answer text`);
      if (screenshots) await page.screenshot({ path: `${screenshots}/${focused ? 'focus' : 'inbox'}-${width}.png`, fullPage: true });
      if (focused) await page.getByRole('button', { name: '탐색으로 돌아가기', exact: true }).click();
    }
  }
  await entry.click();
  await page.getByRole('button', { name: '질문 닫기', exact: true }).click();
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('.monitoring-view.is-focused').count(), 0);
  const firstQuestion = page.getByRole('button', { name: '질문 1 보기', exact: true });
  assert.equal(await firstQuestion.evaluate(element => document.activeElement === element), true, 'after the question is closed, Escape must restore focus to an enabled question selector');
  checks.push('focus exit after question closure restores an enabled navigation target');
  assert.equal(questionReads, 3, 'responsive layout changes must not reread bodies');
  assert.equal(responseReads, 0);
  checks.push('390px and 320px inbox and focus layouts fit without body rereads');
  assert.deepEqual(unexpected, []);
  assert.deepEqual(failures, []);
  console.log(JSON.stringify({ passed: true, checks, questionReads, responseReads, isolatedApi: true }, null, 2));
} finally { await browser.close(); }
