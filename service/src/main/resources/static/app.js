// The reconciliation dashboard: the latest runs over Server-Sent Events, and the selected run's breakdown.
// Everything shown that came from an upload (labels, account ids) is written as text, never as HTML.
'use strict';

(() => {
  const KEY_STORAGE = 'reconciliation.apiKey';
  const TYPE_LABELS = {
    MISSING_IN_BANK: 'Missing in bank',
    MISSING_IN_LEDGER: 'Missing in ledger',
    AMOUNT_MISMATCH: 'Amount mismatch',
    DUPLICATE: 'Duplicate',
  };
  const RUN_STATUS = { RECEIVING: 'Waiting for files', MATCHING: 'Matching', COMPLETED: 'Completed' };
  // Workflow statuses take categorical slots 1-3 in fixed order: identity, not severity.
  const WORKFLOW = [
    { key: 'open', label: 'Open', color: '--series-1' },
    { key: 'investigating', label: 'Investigating', color: '--series-2' },
    { key: 'resolved', label: 'Resolved', color: '--series-3' },
  ];

  const integer = new Intl.NumberFormat('en-US');
  const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });
  const percent = new Intl.NumberFormat('en-US', { style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
  const moneyCompact = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: 1 });

  const state = {
    key: readKey(),
    runs: [],
    selectedId: null,
    followLatest: true, // until someone picks a run, show the newest one as it arrives
    breakdownFor: null, // the run summary the shown breakdown was loaded for
    breakdown: null,
    loading: false,
    reloadAgain: false,
    stream: null,
    charts: {},
  };

  const $ = (id) => document.getElementById(id);

  function readKey() {
    try { return sessionStorage.getItem(KEY_STORAGE); } catch { return null; }
  }

  function storeKey(key) {
    try {
      if (key) sessionStorage.setItem(KEY_STORAGE, key); else sessionStorage.removeItem(KEY_STORAGE);
    } catch { /* storage blocked: the key lives for this page only */ }
  }

  /** Builds an element; string children become text nodes, so nothing is ever parsed as HTML. */
  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    if (props) Object.assign(node, props);
    node.append(...children.filter((c) => c !== null && c !== undefined));
    return node;
  }

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function number(n) {
    return n >= 10000 ? compact.format(n) : integer.format(n);
  }

  // ---- Connection --------------------------------------------------------------------------------------

  function setConnection(stateName, text) {
    const pill = $('connection');
    pill.dataset.state = stateName;
    pill.textContent = text;
  }

  function showLocked(locked) {
    $('key-form').hidden = !locked;
    $('sign-out').hidden = locked;
    $('locked').hidden = !locked;
    if (locked) {
      $('run').hidden = true;
      $('run-list').replaceChildren();
      $('no-runs').hidden = true;
    }
  }

  function sleep(ms, signal) {
    return new Promise((resolve) => {
      const timer = setTimeout(resolve, ms);
      signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
    });
  }

  async function connect() {
    disconnect();
    if (!state.key) {
      showLocked(true);
      setConnection('locked', 'Not connected');
      return;
    }
    showLocked(false);
    const controller = new AbortController();
    state.stream = controller;
    let delay = 1000;
    while (!controller.signal.aborted) {
      try {
        setConnection('retrying', 'Connecting…');
        const response = await fetch('/v1/stream', {
          headers: { 'X-API-Key': state.key, Accept: 'text/event-stream' },
          cache: 'no-store',
          signal: controller.signal,
        });
        if (response.status === 401) {
          forgetKey();
          setConnection('refused', 'Key refused');
          return;
        }
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
        setConnection('live', 'Live');
        delay = 1000;
        await readEvents(response.body, onEvent);
      } catch {
        if (controller.signal.aborted) return;
      }
      // The stream ended (instance restarting, proxy timeout) or failed: nginx sends the retry elsewhere.
      setConnection('retrying', 'Reconnecting…');
      await sleep(delay, controller.signal);
      delay = Math.min(delay * 2, 15000);
    }
  }

  function disconnect() {
    if (state.stream) state.stream.abort();
    state.stream = null;
  }

  function forgetKey() {
    state.key = null;
    storeKey(null);
    disconnect();
    destroyCharts();
    state.runs = [];
    state.selectedId = null;
    state.followLatest = true;
    state.breakdown = null;
    state.breakdownFor = null;
    showLocked(true);
  }

  /** A minimal text/event-stream parser: fetch, unlike EventSource, can send the API key header. */
  async function readEvents(body, onEventFn) {
    const reader = body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      buffer += value;
      let boundary;
      while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, '');
        let event = 'message';
        const data = [];
        for (const line of block.split(/\r?\n/)) {
          if (line === '' || line.startsWith(':')) continue;
          const colon = line.indexOf(':');
          const field = colon < 0 ? line : line.slice(0, colon);
          let fieldValue = colon < 0 ? '' : line.slice(colon + 1);
          if (fieldValue.startsWith(' ')) fieldValue = fieldValue.slice(1);
          if (field === 'event') event = fieldValue;
          else if (field === 'data') data.push(fieldValue);
        }
        if (data.length) onEventFn(event, data.join('\n'));
      }
    }
  }

  function onEvent(event, data) {
    if (event !== 'runs') return;
    state.runs = JSON.parse(data).runs;
    if (state.followLatest || !state.runs.some((run) => run.id === state.selectedId)) {
      state.selectedId = state.runs.length ? state.runs[0].id : null;
    }
    renderRunList();
    renderRun();
  }

  async function api(path) {
    const response = await fetch(path, { headers: { 'X-API-Key': state.key }, cache: 'no-store' });
    if (response.status === 401) {
      forgetKey();
      throw new Error('Key refused');
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }

  // ---- Runs list ---------------------------------------------------------------------------------------

  function runProgress(run) {
    const s = run.summary;
    if (run.status === 'COMPLETED') return s.matchRate == null ? 'No records' : `${percent.format(s.matchRate)} matched`;
    if (run.status === 'MATCHING' && s.statementLinesExpected) {
      return `${Math.floor((100 * s.statementLines) / s.statementLinesExpected)}% of lines in`;
    }
    return '';
  }

  function renderRunList() {
    const list = $('run-list');
    $('no-runs').hidden = state.runs.length > 0;
    list.replaceChildren(...state.runs.map((run) => {
      const button = el('button', { type: 'button' },
        el('span', { className: 'run-label' }, run.label),
        el('span', { className: 'run-line' },
          el('span', null, `#${run.id}`),
          el('span', { className: 'status' }, RUN_STATUS[run.status] || run.status)),
        el('span', { className: 'run-line' }, el('span', null, runProgress(run))));
      button.setAttribute('aria-current', String(run.id === state.selectedId));
      button.addEventListener('click', () => {
        state.selectedId = run.id;
        state.followLatest = run.id === state.runs[0].id;
        renderRunList();
        renderRun();
      });
      return el('li', null, button);
    }));
  }

  // ---- Selected run ------------------------------------------------------------------------------------

  function renderRun() {
    const run = state.runs.find((r) => r.id === state.selectedId);
    if (!run) {
      $('run').hidden = true;
      return;
    }
    $('run').hidden = false;
    const s = run.summary;
    $('run-title').textContent = run.label;
    $('run-meta').textContent = `Run ${run.id} · ${RUN_STATUS[run.status] || run.status} · created by ${run.createdBy} `
      + `on ${new Date(run.createdAt).toLocaleString()} · tolerance ${money.format(run.amountToleranceCents / 100)} `
      + `and ${run.dateWindowDays} days`;
    $('run-problem').hidden = !run.matchingProblem;
    $('run-problem').textContent = run.matchingProblem ? `Matching is held up: ${run.matchingProblem}` : '';

    const records = s.ledgerRecords + s.statementLines;
    $('match-rate').textContent = s.matchRate == null ? '–' : percent.format(s.matchRate);
    $('match-rate-detail').textContent = records ? `${integer.format(2 * s.matches)} of ${integer.format(records)} records` : '';
    $('matches').textContent = number(s.matches);
    $('breaks').textContent = number(s.breaks);
    const resolved = (s.breaksByStatus && s.breaksByStatus.RESOLVED) || 0;
    $('resolved').textContent = s.breaks ? `${number(resolved)} of ${number(s.breaks)}` : '–';

    const matching = run.status === 'MATCHING' && s.statementLinesExpected;
    $('progress').hidden = !matching;
    if (matching) {
      const share = Math.min(100, (100 * s.statementLines) / s.statementLinesExpected);
      $('meter-fill').style.width = `${share}%`;
      $('progress').querySelector('.meter').setAttribute('aria-valuenow', String(Math.floor(share)));
      $('progress-text').textContent = `${integer.format(s.statementLines)} of ${integer.format(s.statementLinesExpected)} lines`;
    }

    const version = JSON.stringify([run.id, run.status, s]);
    if (version !== state.breakdownFor) loadBreakdown(run.id, version);
  }

  async function loadBreakdown(runId, version) {
    if (state.loading) {
      state.reloadAgain = true;
      return;
    }
    state.loading = true;
    try {
      const breakdown = await api(`/v1/runs/${runId}/breakdown`);
      if (runId === state.selectedId) {
        state.breakdown = breakdown;
        state.breakdownFor = version;
        renderBreakdown();
      }
    } catch {
      // Shown again on the next change; the connection pill already says when the server is unreachable.
    } finally {
      state.loading = false;
      if (state.reloadAgain) {
        state.reloadAgain = false;
        renderRun();
      }
    }
  }

  function renderBreakdown() {
    const b = state.breakdown;
    if (!b) return;
    const unresolvedExposure = b.byType.reduce((sum, t) => sum + t.unresolvedExposureCents, 0);
    $('exposure').textContent = moneyCompact.format(unresolvedExposure / 100);
    $('exposure').title = money.format(unresolvedExposure / 100);

    renderTypeChart(b.byType);
    renderAgeChart(b.byAge);
    renderTables(b);
  }

  // ---- Charts ------------------------------------------------------------------------------------------

  // A fresh object per axis: callers change one axis's ticks or grid without touching the other's.
  function axisOptions(stacked) {
    const axis = () => ({
      stacked,
      grid: { color: cssVar('--grid'), lineWidth: 1, drawTicks: false },
      border: { color: cssVar('--axis') },
      ticks: { color: cssVar('--muted'), padding: 6, font: { size: 12 } },
    });
    return { x: axis(), y: axis() };
  }

  // Value at the bar tip, in text ink (never the series color). Measured, never clipped: the axis has grace.
  const tipLabels = {
    id: 'tipLabels',
    afterDatasetsDraw(chart) {
      const { ctx } = chart;
      ctx.save();
      ctx.fillStyle = cssVar('--ink-2');
      ctx.font = `12px ${cssVar('--font')}`;
      ctx.textBaseline = 'middle';
      chart.getDatasetMeta(0).data.forEach((bar, i) => {
        ctx.fillText(integer.format(chart.data.datasets[0].data[i]), bar.x + 6, bar.y);
      });
      ctx.restore();
    },
  };

  function upsertChart(id, config) {
    if (state.charts[id]) state.charts[id].destroy();
    state.charts[id] = new Chart($(id), config);
  }

  function destroyCharts() {
    Object.values(state.charts).forEach((chart) => chart.destroy());
    state.charts = {};
  }

  function renderTypeChart(byType) {
    const axes = axisOptions(false);
    axes.x.beginAtZero = true;
    axes.x.grace = '12%';
    axes.x.ticks.precision = 0;
    axes.y.grid = { display: false };
    upsertChart('by-type', {
      type: 'bar',
      data: {
        labels: byType.map((t) => TYPE_LABELS[t.type] || t.type),
        datasets: [{
          label: 'Breaks',
          data: byType.map((t) => t.breaks),
          backgroundColor: cssVar('--series-1'),
          borderRadius: 4,
          borderSkipped: 'start',
          maxBarThickness: 24,
        }],
      },
      options: {
        indexAxis: 'y',
        maintainAspectRatio: false,
        animation: false,
        scales: axes,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (ctx) => {
                const t = byType[ctx.dataIndex];
                return [`${integer.format(t.breaks)} breaks, ${money.format(t.exposureCents / 100)}`,
                  `${integer.format(t.unresolved)} unresolved, ${money.format(t.unresolvedExposureCents / 100)}`];
              },
            },
          },
        },
      },
      plugins: [tipLabels],
    });
  }

  function renderAgeChart(byAge) {
    const axes = axisOptions(true);
    axes.y.beginAtZero = true;
    axes.y.ticks.precision = 0;
    axes.y.ticks.callback = (value) => compact.format(value);
    axes.x.grid = { display: false };
    const surface = cssVar('--surface');
    upsertChart('by-age', {
      type: 'bar',
      data: {
        labels: byAge.map((a) => a.label),
        datasets: WORKFLOW.map((w) => ({
          label: w.label,
          data: byAge.map((a) => a[w.key]),
          backgroundColor: cssVar(w.color),
          // The 2px surface-coloured edge is the gap between stacked segments.
          borderColor: surface,
          borderWidth: { top: 2, right: 0, bottom: 0, left: 0 },
          borderRadius: 4,
          borderSkipped: 'start',
          maxBarThickness: 24,
        })),
      },
      options: {
        maintainAspectRatio: false,
        animation: false,
        interaction: { mode: 'index', intersect: false },
        scales: axes,
        plugins: {
          legend: {
            position: 'top',
            align: 'start',
            labels: { color: cssVar('--ink-2'), boxWidth: 10, boxHeight: 10, useBorderRadius: true, borderRadius: 2 },
          },
          tooltip: { callbacks: { label: (ctx) => `${ctx.dataset.label}: ${integer.format(ctx.raw)}` } },
        },
      },
    });
  }

  // ---- Tables (the same numbers, readable without the charts) -----------------------------------------

  function table(id, headers, rows) {
    const head = el('thead', null, el('tr', null, ...headers.map(([text, numeric]) =>
      el('th', { scope: 'col', className: numeric ? 'num' : '' }, text))));
    const body = el('tbody', null, ...rows.map((cells) => el('tr', null, ...cells.map((cell, i) =>
      el('td', { className: headers[i][1] ? 'num' : '' }, cell)))));
    $(id).replaceChildren(head, body);
  }

  function renderTables(b) {
    table('by-type-table', [['Type'], ['Breaks', true], ['Exposure', true], ['Unresolved', true], ['Unresolved exposure', true]],
      b.byType.map((t) => [TYPE_LABELS[t.type] || t.type, integer.format(t.breaks), money.format(t.exposureCents / 100),
        integer.format(t.unresolved), money.format(t.unresolvedExposureCents / 100)]));
    table('by-age-table', [['Age'], ...WORKFLOW.map((w) => [w.label, true])],
      b.byAge.map((a) => [a.label, ...WORKFLOW.map((w) => integer.format(a[w.key]))]));
    if (b.topUnresolvedAccounts.length) {
      table('accounts-table', [['Account'], ['Unresolved breaks', true], ['Unresolved exposure', true]],
        b.topUnresolvedAccounts.map((a) => [a.accountId, integer.format(a.unresolved),
          money.format(a.unresolvedExposureCents / 100)]));
    } else {
      $('accounts-table').replaceChildren(el('caption', { className: 'muted' }, 'Nothing unresolved.'));
    }
  }

  // ---- Wiring ------------------------------------------------------------------------------------------

  $('key-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const key = $('api-key').value.trim();
    if (!key) return;
    $('api-key').value = '';
    state.key = key;
    storeKey(key);
    connect();
  });
  $('sign-out').addEventListener('click', () => {
    forgetKey();
    setConnection('locked', 'Not connected');
  });
  // Dark mode has its own validated steps: redraw with them when the system theme flips.
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (state.breakdown) renderBreakdown();
  });

  Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
  connect();
})();
