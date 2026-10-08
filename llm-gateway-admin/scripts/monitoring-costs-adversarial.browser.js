// Run with Playwright CLI run-code, supplying this file's async(page) expression.
// Prerequisites: authenticated /admin page, usage:read, content:read, collectors:manage.
// Uses only synthetic monitoring responses; never reads real question bodies or saves real costs.
async (page) => {
  const names = [
    'lateUsageUpdatesOpenQuestion', 'freshExplicitMetadataWinsWhilePaused',
    'remoteSavePreservesDraft', 'sessionSwitchPreservesAccountDraft',
    'failedCostReadMarksQuestionAllocation', 'pausedDeferredReadResumesWhenVisible',
  ];
  const report = { fixtureOnly: true, checks: {}, failedChecks: [], cleanup: { passed: false } };
  const fixtureMonth = '2026-02', deferredMonth = '2026-01';
  const accountId = '10000000-0000-4000-8000-000000000001';
  const sessionIds = ['20000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002'];
  const questionIds = ['30000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000002'];
  const stamp = '2026-02-05T03:00:00Z';
  const account = { id: accountId, name: '비용 회귀 fixture', sessionCount: 2, questionCount: 2, lastReceivedAt: stamp };
  const sessions = sessionIds.map((id, index) => ({
    id, accountId, source: 'codex', externalSessionId: `fixture-session-${index + 1}`,
    firstSeenAt: stamp, lastSeenAt: stamp, questionCount: 1, cliVersion: 'fixture',
    providerAccountStatus: 'unknown', usageAvailability: 'missing', unattributedEvents: 0,
  }));
  let late = false, fee = 20, failCosts = false;
  let bodyReads = 0, deferredReads = 0, questionListReads = 0, mutationAttempts = 0, unexpectedReads = 0;
  let routeInstalled = false, visibilityCaptured = false;
  let originalPaused = false, originalMonth = null, originalAccountLabel = null;
  const routePattern = '**/admin/api/monitoring/**';
  const question = (index, fresh = late) => ({
    id: questionIds[index], externalPromptId: `fixture-question-${index + 1}`, source: 'codex',
    startedAt: stamp, model: 'browser-fixture-model', attribution: 'linked',
    usageAvailability: fresh ? 'reported' : 'missing', coverage: 'observed', callCount: 1,
    inputTokens: fresh ? 100 : null, outputTokens: fresh ? 20 : null,
    cacheReadTokens: fresh ? 0 : null, cacheWriteTokens: fresh ? 0 : null,
    totalTokens: fresh ? 120 : null, apiEquivalentCostUsd: fresh ? 0.24 : null,
  });
  const handler = async route => {
    const request = route.request(), url = new URL(request.url());
    const path = url.pathname.slice(url.pathname.indexOf('/admin/api/monitoring/') + '/admin/api/monitoring/'.length);
    const respond = (json, status = 200) => route.fulfill({
      status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(json),
    });
    // A future UI regression must never turn this test into a real backend mutation.
    if (request.method() !== 'GET') {
      mutationAttempts++;
      return respond({ error: { code: 'fixture_mutation_blocked' } }, 405);
    }
    if (path === 'status') return respond({ ready: true, stored: 2, rejected: 0, duplicates: 0, unsupported: 0, unattributed: 0, lastReceivedAt: stamp });
    if (path === 'accounts') return respond({ items: [account], total: 1 });
    if (path === 'sessions') return respond({ items: sessions, total: sessions.length });
    const list = /^sessions\/([^/]+)\/questions$/.exec(path);
    if (list) {
      const index = sessionIds.indexOf(list[1]);
      questionListReads++;
      return index < 0 ? respond({ error: { code: 'fixture_not_found' } }, 404)
        : respond({ items: [question(index)], total: 1 });
    }
    if (/^sessions\/[^/]+\/events$/.test(path)) return respond({ items: [], total: 0 });
    const detail = /^questions\/([^/]+)$/.exec(path);
    if (detail) {
      const index = questionIds.indexOf(detail[1]);
      bodyReads++;
      return index < 0 ? respond({ error: { code: 'fixture_not_found' } }, 404)
        : respond({ question: question(index), content: { text: `안전한 합성 질문 ${index + 1}. 실제 대화가 아닙니다.`, truncated: false }, contentAvailability: 'available' });
    }
    if (path === `accounts/${accountId}/costs`) {
      const month = url.searchParams.get('month');
      if (month === deferredMonth) deferredReads++;
      if (failCosts) return respond({ error: { code: 'fixture_unavailable' } }, 503);
      const amount = month === fixtureMonth ? fee : null;
      const selectedSession = url.searchParams.get('sessionId'), selectedQuestion = url.searchParams.get('questionId');
      return respond({
        month, zone: url.searchParams.get('zone'),
        subscription: { amount, currency: 'USD', allocationBasis: 'recorded_questions', questionCount: 2, sessionCount: 2, perQuestion: amount == null ? null : amount / 2 },
        session: selectedSession ? { id: selectedSession, questionCount: 1, subscriptionAllocation: amount == null ? null : amount / 2, latestObservation: null } : null,
        question: selectedQuestion ? { id: selectedQuestion, inMonth: month === fixtureMonth, subscriptionAllocation: amount == null ? null : amount / 2 } : null,
      });
    }
    unexpectedReads++;
    return respond({ error: { code: 'unexpected_fixture_endpoint' } }, 404);
  };
  const pause = async paused => {
    const button = page.getByRole('button', { name: paused ? '2초 자동 갱신' : '자동 갱신 재개', exact: true });
    if (await button.count()) await button.click();
  };
  const remountMonitoring = async () => {
    // Remount without a browser reload, which would erase local in-memory authentication.
    await page.getByRole('button', { name: '사용량 분석', exact: true }).click();
    await page.getByRole('button', { name: '구독 모니터링', exact: true }).click();
    await page.locator('.monitoring-view').waitFor({ timeout: 5000 });
  };
  const panel = page.locator('.cost-panel'), metadata = page.locator('.question-metadata');
  const amount = page.getByRole('spinbutton', { name: '월 구독료', exact: true });
  const currency = page.getByLabel('구독료 통화', { exact: true });
  const monthInput = page.getByLabel('비용 산정 월', { exact: true });
  const field = label => metadata.locator('.detail-grid>div').filter({
    has: page.locator('span', { hasText: new RegExp(`^${label}$`) }),
  }).locator('strong');
  const open = async locator => {
    await locator.waitFor({ timeout: 5000 });
    if (!await locator.evaluate(element => element.open)) await locator.locator('summary').first().click();
  };
  const until = async predicate => {
    const deadline = Date.now() + 6500;
    do {
      if (await predicate()) return;
      await page.waitForTimeout(50);
    } while (Date.now() < deadline);
    throw new Error('fixture_assertion_timeout');
  };
  const check = async (name, action) => {
    try { report.checks[name] = await action(); }
    catch { report.checks[name] = { passed: false, reason: 'fixture_action_or_assertion_failed' }; }
  };
  const refresh = () => page.getByRole('button', { name: '새로고침', exact: true }).click();
  const selectQuestion = async () => {
    await page.getByRole('button', { name: '질문 1 보기', exact: true }).click();
    await page.locator('.prompt-text').waitFor({ timeout: 5000 });
    await open(metadata);
  };
  try {
    await page.locator('.app-shell').waitFor({ timeout: 5000 });
    if (!await page.locator('.monitoring-view').count()) {
      await page.getByRole('button', { name: '구독 모니터링', exact: true }).click();
    }
    originalPaused = await page.getByRole('button', { name: '자동 갱신 재개', exact: true }).count() > 0;
    if (await monthInput.count()) originalMonth = await monthInput.inputValue();
    const selected = page.locator('.account-option.selected');
    if (await selected.count()) originalAccountLabel = await selected.getAttribute('aria-label');
    await page.evaluate(() => {
      window.__monitoringCostsVisibilityRestore = Object.getOwnPropertyDescriptor(document, 'hidden') || null;
      Object.defineProperty(document, 'hidden', { configurable: true, value: false });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    visibilityCaptured = true;
    await page.route(routePattern, handler);
    routeInstalled = true;
    await remountMonitoring();
    await page.getByRole('button', { name: `${account.name} 계정 선택`, exact: true }).click();
    await open(panel);
    await monthInput.fill(fixtureMonth);
    await amount.waitFor({ timeout: 5000 });
    await until(async () => await amount.inputValue() === '20');
    await page.getByRole('button', { name: 'Codex 세션 fixture-session-1 열기', exact: true }).click();
    await selectQuestion();

    await check(names[0], async () => {
      const initial = await field('전체 토큰').innerText(), readsBefore = bodyReads, listsBefore = questionListReads;
      late = true;
      await until(async () => await field('전체 토큰').innerText() === '120');
      const tokens = await field('전체 토큰').innerText(), apiCost = await field('질문 API 환산액').innerText();
      return { passed: initial !== '120' && tokens === '120' && apiCost.includes('0.24') && questionListReads > listsBefore && bodyReads === readsBefore,
        tokens, apiCost, extraBodyReads: bodyReads - readsBefore };
    });
    await check(names[1], async () => {
      await page.getByRole('button', { name: '질문 닫기', exact: true }).click();
      late = false;
      const previous = questionListReads;
      await refresh();
      await until(async () => questionListReads > previous && await page.getByText('사용량 미수신', { exact: true }).count() > 0);
      await pause(true);
      // A real timestamp ordering assertion: detail response is newer than the paused list.
      await page.waitForTimeout(20);
      late = true;
      const readsBefore = bodyReads;
      await selectQuestion();
      await until(async () => await field('전체 토큰').innerText() === '120');
      return { passed: await field('전체 토큰').innerText() === '120' && bodyReads === readsBefore + 1,
        tokens: await field('전체 토큰').innerText(), explicitBodyReads: bodyReads - readsBefore };
    });
    await check(names[2], async () => {
      await pause(false);
      await amount.fill('123.45'); await currency.selectOption('KRW');
      fee = 40;
      const conflict = page.getByText('서버 설정이 변경되었습니다.', { exact: false });
      await until(async () => await conflict.count() === 1);
      const value = await amount.inputValue(), unit = await currency.inputValue();
      return { passed: value === '123.45' && unit === 'KRW' && await conflict.count() === 1,
        amount: value, currency: unit, conflictNotice: await conflict.count() === 1 };
    });
    await check(names[3], async () => {
      await amount.fill('456.78'); await currency.selectOption('KRW');
      const switchedCosts = page.waitForResponse(response => {
        const url = new URL(response.url());
        return url.pathname === `/admin/api/monitoring/accounts/${accountId}/costs`
          && url.searchParams.get('sessionId') === sessionIds[1] && !url.searchParams.has('questionId');
      }, { timeout: 5000 });
      await page.getByRole('button', { name: 'Codex 세션 fixture-session-2 열기', exact: true }).click();
      await switchedCosts;
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      await page.getByRole('button', { name: '질문 1 보기', exact: true }).waitFor({ timeout: 5000 });
      await until(async () => (await page.locator('.cost-allocation').innerText()).includes('선택 세션 배분'));
      return { passed: await amount.inputValue() === '456.78' && await currency.inputValue() === 'KRW',
        amount: await amount.inputValue(), currency: await currency.inputValue() };
    });
    await check(names[4], async () => {
      await selectQuestion();
      await until(async () => !(await field('질문 구독료 배분').innerText()).includes('계산 중'));
      const before = await field('질문 구독료 배분').innerText();
      failCosts = true;
      await until(async () => (await field('질문 구독료 배분').innerText()).includes('실패'));
      const allocation = await field('질문 구독료 배분').innerText();
      failCosts = false;
      return { passed: !before.includes('실패') && allocation.includes('실패'), allocation };
    });
    await check(names[5], async () => {
      failCosts = false;
      await pause(true);
      await page.evaluate(() => {
        Object.defineProperty(document, 'hidden', { configurable: true, value: true });
        document.dispatchEvent(new Event('visibilitychange'));
      });
      const before = deferredReads;
      await monthInput.fill(deferredMonth);
      await page.waitForTimeout(300);
      const hiddenReads = deferredReads - before;
      await page.evaluate(() => {
        Object.defineProperty(document, 'hidden', { configurable: true, value: false });
        document.dispatchEvent(new Event('visibilitychange'));
      });
      await until(async () => deferredReads > before && !(await panel.locator('summary').first().innerText()).includes('계산 중'));
      return { passed: hiddenReads === 0 && deferredReads > before && await page.getByRole('button', { name: '자동 갱신 재개', exact: true }).count() === 1,
        hiddenReads, visibleReads: deferredReads - before };
    });
  } catch {
    report.setup = { passed: false, reason: 'authenticated_admin_permissions_or_fixture_setup_required' };
  } finally {
    // Only remove this handler, preserving any routes owned by the caller.
    try {
      if (visibilityCaptured) await page.evaluate(() => {
        const original = window.__monitoringCostsVisibilityRestore;
        if (original) Object.defineProperty(document, 'hidden', original);
        else delete document.hidden;
        delete window.__monitoringCostsVisibilityRestore;
        document.dispatchEvent(new Event('visibilitychange'));
      });
      if (routeInstalled) {
        // Stop fixture polling before removing routes so no synthetic ID reaches the backend.
        await pause(true);
        const closeQuestion = page.getByRole('button', { name: '질문 닫기', exact: true });
        if (await closeQuestion.count()) await closeQuestion.click();
        await page.getByRole('button', { name: '사용량 분석', exact: true }).click();
        await page.unroute(routePattern, handler);
        await page.getByRole('button', { name: '구독 모니터링', exact: true }).click();
        await page.locator('.monitoring-view').waitFor({ timeout: 5000 });
        if (originalAccountLabel) {
          const original = page.getByRole('button', { name: originalAccountLabel, exact: true });
          await original.waitFor({ timeout: 5000 }); await original.click();
          await open(panel);
          if (originalMonth) await monthInput.fill(originalMonth);
        }
        await pause(originalPaused);
        const realMetadata = page.waitForResponse(response =>
          new URL(response.url()).pathname === '/admin/api/monitoring/accounts' && response.request().method() === 'GET',
        { timeout: 5000 });
        await refresh();
        const response = await realMetadata;
        report.cleanup = { passed: response.ok(), realMetadataRefreshStatus: response.status() };
      } else report.cleanup = { passed: true, fixtureRoutesNotInstalled: true };
    } catch {
      // Make cleanup failure visible without returning URLs, headers, identity, or live body text.
      if (routeInstalled) await page.unroute(routePattern, handler).catch(() => {});
      report.cleanup = { passed: false, reason: 'state_restore_or_real_metadata_refresh_failed' };
    }
  }
  for (const name of names) {
    if (!report.checks[name]) report.checks[name] = { passed: false, reason: 'not_executed_after_setup_failure' };
  }
  report.failedChecks = names.filter(name => !report.checks[name].passed);
  if (report.setup?.passed === false) report.failedChecks.push('setup');
  if (!report.cleanup.passed) report.failedChecks.push('cleanup');
  if (mutationAttempts || unexpectedReads) report.failedChecks.push('fixtureEndpointIsolation');
  report.fixtureRequests = { bodyReads, questionListReads, deferredReads, mutationAttempts, unexpectedReads };
  report.failed = report.failedChecks.length;
  return report;
}
