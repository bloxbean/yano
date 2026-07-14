(() => {
  'use strict';

  const PAGE_SIZE = 100;
  const MAX_LOADED_BUNDLES = 500;
  const REFRESH_MS = 5000;
  const REQUEST_TIMEOUT_MS = 4500;
  const KEY_STORAGE = 'yano-plugin-operations-session';
  const PREFIX_DISCOVERY_PATH = '/ui/plugins/api-prefix.json';

  const byId = id => document.getElementById(id);
  const authPanel = byId('authPanel');
  const dashboard = byId('dashboard');
  const keyForm = byId('keyForm');
  const keyInput = byId('apiKey');
  const connectKey = byId('connectKey');
  const authMessage = byId('authMessage');
  const forgetKey = byId('forgetKey');
  const search = byId('search');
  const loadMore = byId('loadMore');

  let apiPrefix = null;
  let apiRoot = null;
  let key = '';
  let activeRequest = null;
  let refreshTimer = null;
  let nextAfter = null;
  let bundles = [];
  let expandedBundleId = null;

  function validateApiPrefix(candidate) {
    if (!candidate || candidate.length > 256 || candidate.trim() !== candidate
        || !candidate.startsWith('/')
        || (candidate.length > 1 && candidate.endsWith('/'))
        || candidate.startsWith('//') || /[\\?#;\u0000-\u001f\u007f]/.test(candidate)) {
      return null;
    }
    if (candidate.includes('//') || candidate.includes('%') || candidate.includes('+')) return null;
    const pathSegments = candidate.split('/').filter(Boolean);
    if ((candidate !== '/' && !pathSegments.length)
        || pathSegments.some(segment => segment === '.' || segment === '..')
        || pathSegments.some(segment => !/^[A-Za-z0-9._~-]+$/.test(segment))) {
      return null;
    }
    try {
      const parsed = new URL(candidate, location.origin);
      if (parsed.origin !== location.origin || parsed.username || parsed.password
          || parsed.search || parsed.hash || parsed.pathname.includes('//')) {
        return null;
      }
      return parsed.pathname === candidate ? candidate : null;
    } catch (ignored) {
      return null;
    }
  }

  async function discoverApiPrefix() {
    const response = await fetch(PREFIX_DISCOVERY_PATH, {
      redirect: 'error',
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' }
    });
    const responseUrl = new URL(response.url);
    if (!response.ok || response.redirected || responseUrl.origin !== location.origin
        || responseUrl.pathname !== PREFIX_DISCOVERY_PATH
        || responseUrl.search || responseUrl.hash) {
      throw new Error('prefix discovery failed');
    }
    const discovery = await response.json();
    const trustedPrefix = validateApiPrefix(
      discovery && typeof discovery === 'object' ? discovery.apiPrefix : null);
    if (!trustedPrefix) throw new Error('prefix discovery returned an invalid value');
    return trustedPrefix;
  }

  function readSessionKey() {
    if (!apiPrefix) return '';
    try {
      const encoded = sessionStorage.getItem(KEY_STORAGE);
      if (!encoded) return '';
      const stored = JSON.parse(encoded);
      return stored && stored.prefix === apiPrefix && typeof stored.key === 'string'
        ? stored.key : '';
    } catch (ignored) {
      return '';
    }
  }

  function saveSessionKey(value) {
    if (!apiPrefix) {
      key = '';
      return;
    }
    key = value;
    try {
      if (value) sessionStorage.setItem(
        KEY_STORAGE, JSON.stringify({ prefix: apiPrefix, key: value }));
      else sessionStorage.removeItem(KEY_STORAGE);
    } catch (ignored) {
      // The in-memory copy remains usable when session storage is unavailable.
    }
  }

  function text(id, value) {
    byId(id).textContent = value == null || value === '' ? '-' : String(value);
  }

  function clear(element) {
    element.replaceChildren();
  }

  function node(tag, className, value) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value != null) element.textContent = String(value);
    return element;
  }

  function first(object, names, fallback) {
    if (!object) return fallback;
    for (const name of names) {
      if (object[name] !== undefined && object[name] !== null) return object[name];
    }
    return fallback;
  }

  function normalizedList(value) {
    if (Array.isArray(value)) return value;
    if (value && typeof value === 'object') {
      return Object.entries(value).map(([id, item]) => {
        if (item && typeof item === 'object') return { id, ...item };
        return { id, value: item };
      });
    }
    return [];
  }

  function stateKind(value) {
    const state = String(value || 'UNKNOWN').toUpperCase();
    if (state === 'UP' || state === 'ACTIVE') return 'good';
    if (state === 'DEGRADED' || state === 'STALE' || state === 'ACTIVATING') return 'warning';
    if (state === 'DOWN' || state === 'FAILED') return 'danger';
    return 'neutral';
  }

  function badge(value) {
    const label = String(value || 'UNKNOWN').toUpperCase();
    return node('span', `badge ${stateKind(label)}`, label);
  }

  function formatInstant(value) {
    if (value == null || value === '') return '-';
    const numeric = typeof value === 'number' ? value : Number(value);
    const date = Number.isFinite(numeric)
      ? new Date(numeric < 100000000000 ? numeric * 1000 : numeric)
      : new Date(String(value));
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
  }

  async function request(path, signal) {
    if (!apiPrefix || !apiRoot || !key) throw new Error('trusted API prefix unavailable');
    const response = await fetch(path, {
      cache: 'no-store',
      credentials: 'same-origin',
      redirect: 'error',
      headers: { Accept: 'application/json', 'X-API-Key': key },
      signal
    });
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    return response.json();
  }

  function showAuthentication(message) {
    stopRefresh();
    clear(byId('totals'));
    clear(byId('bundleList'));
    bundles = [];
    nextAfter = null;
    expandedBundleId = null;
    dashboard.hidden = true;
    forgetKey.hidden = true;
    authPanel.hidden = false;
    authMessage.textContent = message || '';
    keyInput.value = '';
    if (!keyInput.disabled) keyInput.focus();
  }

  function setCredentialEntryEnabled(enabled) {
    keyInput.disabled = !enabled;
    connectKey.disabled = !enabled;
  }

  function showDashboard() {
    authPanel.hidden = true;
    dashboard.hidden = false;
    forgetKey.hidden = false;
  }

  function stopRefresh() {
    if (refreshTimer) window.clearTimeout(refreshTimer);
    refreshTimer = null;
    if (activeRequest) activeRequest.abort();
    activeRequest = null;
  }

  function scheduleRefresh() {
    if (refreshTimer) window.clearTimeout(refreshTimer);
    refreshTimer = null;
    if (!document.hidden && key) {
      refreshTimer = window.setTimeout(() => refresh(false), REFRESH_MS);
    }
  }

  function aggregateCounts(summary) {
    const counts = first(summary, ['totals', 'counts', 'aggregateCounts', 'stateCounts'], {});
    const health = first(summary, ['healthCounts'], {});
    const result = new Map();
    for (const [name, value] of Object.entries(counts || {})) result.set(name, value);
    for (const [name, value] of Object.entries(health || {})) result.set(name, value);
    for (const name of ['selectedBundles', 'observedContributions',
      'observedActiveContributions', 'staleSources', 'failedContributions']) {
      if (summary[name] !== undefined) result.set(name, summary[name]);
    }
    return result;
  }

  function renderSummary(summary) {
    text('fingerprint', first(summary, ['catalogFingerprint', 'fingerprint'], '-'));
    text('generation', first(summary, ['generation'], '-'));
    text('capturedAt', formatInstant(first(
      summary, ['capturedAtEpochMillis', 'capturedAt', 'capturedAtMillis'], null)));
    text('updatedAt', `Updated ${new Date().toLocaleTimeString()}`);

    const counts = aggregateCounts(summary);
    const totals = byId('totals');
    clear(totals);
    const entries = Array.from(counts.entries()).sort(([left], [right]) => left.localeCompare(right));
    if (!entries.length) {
      totals.append(node('div', 'empty-panel', 'No aggregate counters are present in this snapshot.'));
    } else {
      for (const [name, value] of entries) {
        const card = node('article', 'total');
        card.append(node('strong', '', Number(value).toLocaleString('en-US')));
        card.append(node('span', '', String(name).replace(/([a-z])([A-Z])/g, '$1 $2')));
        totals.append(card);
      }
    }

    const failing = Number(counts.get('DOWN') || counts.get('down')
      || counts.get('failedBundles') || counts.get('failedContributions') || 0);
    const degraded = Number(counts.get('DEGRADED') || counts.get('degraded')
      || counts.get('degradedBundles') || counts.get('staleSources') || 0);
    const unknown = Number(counts.get('UNKNOWN') || counts.get('unknown') || 0);
    const overall = failing > 0 ? 'DOWN'
      : degraded > 0 ? 'DEGRADED'
        : unknown > 0 ? 'UNKNOWN' : 'UP';
    const overallBadge = byId('overallBadge');
    overallBadge.textContent = overall;
    overallBadge.className = `badge ${stateKind(overall)}`;
  }

  function contributionLabel(contribution) {
    const kind = first(contribution, ['kind'], 'unknown');
    const name = first(contribution, ['name', 'id'], 'unnamed');
    const trust = first(contribution, ['trustTier'], '');
    const lifecycleObserved = first(contribution, ['lifecycleObserved'], false) === true;
    const lifecycle = first(contribution, ['lifecycle', 'lifecycleState'], 'UNKNOWN');
    const health = first(contribution, ['health', 'healthState'], 'UNKNOWN');
    const scopes = normalizedList(first(contribution, ['instances'], []))
      .map(instance => first(instance, ['scope'], 'unknown')).join(', ');
    const failure = first(first(contribution, ['failure'], {}), ['code'], 'NONE');
    const runtimeState = lifecycleObserved
      ? `${lifecycle}/${health}` : 'CATALOG VALID · LIFECYCLE NOT OBSERVED';
    return `${kind} · ${name}${trust ? ` · ${trust}` : ''} · ${runtimeState}`
      + `${scopes ? ` · ${scopes}` : ''}${failure !== 'NONE' ? ` · ${failure}` : ''}`;
  }

  function metricLabel(metric) {
    const id = first(metric, ['id', 'metricId', 'name'], 'metric');
    const type = String(first(metric, ['type'], '')).toUpperCase();
    if (type === 'TIMER') {
      return `${id} (TIMER): ${first(metric, ['count'], 0)} calls / `
        + `${first(metric, ['totalNanos'], 0)} ns`;
    }
    return `${id}${type ? ` (${type})` : ''}: ${first(metric, ['value', 'total'], '-')}`;
  }

  function healthCheckLabel(check) {
    const id = first(check, ['id'], 'check');
    const status = String(first(check, ['status'], 'UNKNOWN')).toUpperCase();
    const stale = first(check, ['stale'], false) === true ? ' · STALE' : '';
    const description = first(check, ['description'], '');
    return `${id}: ${status}${stale}${description ? ` · ${description}` : ''}`;
  }

  function operationLabel(count) {
    return `${first(count, ['operation'], 'UNKNOWN')} · `
      + `${first(count, ['outcome'], 'UNKNOWN')}: ${first(count, ['total'], 0)}`;
  }

  function detail(title, values, formatter) {
    const section = node('section', 'detail');
    section.append(node('h4', '', title));
    const list = node('ul');
    if (!values.length) {
      list.append(node('li', 'empty', 'None'));
    } else {
      for (const value of values) list.append(node('li', 'mono', formatter(value)));
    }
    section.append(list);
    return section;
  }

  function renderBundles() {
    const query = search.value.trim().toLocaleLowerCase();
    const list = byId('bundleList');
    clear(list);
    const visible = bundles.filter(bundle => {
      if (!query) return true;
      const contributions = normalizedList(first(bundle, ['contributions'], []));
      return String(first(bundle, ['id', 'bundleId'], '')).toLocaleLowerCase().includes(query)
        || contributions.some(item => contributionLabel(item).toLocaleLowerCase().includes(query));
    });

    if (!visible.length) {
      list.append(node('div', 'empty-panel', query ? 'No bundles match the search.' : 'No bundles are present.'));
    }

    for (const bundle of visible) {
      const id = first(bundle, ['id', 'bundleId'], 'unknown');
      const lifecycle = first(bundle, ['lifecycle', 'lifecycleState', 'state'], 'UNKNOWN');
      const healthField = first(bundle, ['health', 'healthState'], null);
      const healthValue = healthField && typeof healthField === 'object'
        ? first(healthField, ['state', 'status'], 'UNKNOWN') : (healthField || 'UNKNOWN');
      const article = node('article', 'bundle');
      const head = node('div', 'bundle-head');
      const title = node('div', 'bundle-title');
      title.append(node('h3', 'mono', id));
      const contributionCount = Number(first(bundle, ['contributionCount'], 0));
      const observedContributionCount = Number(
        first(bundle, ['observedContributionCount'], 0));
      const inventoryCounts = `${contributionCount} contributions `
        + `(${observedContributionCount}/${contributionCount} lifecycle observed) · `
        + `${first(bundle, ['metricCount'], 0)} metrics`;
      const metadata = [first(bundle, ['version'], null), first(bundle, ['source'], null), inventoryCounts]
        .filter(Boolean).join(' · ');
      title.append(node('p', '', metadata || 'Catalog metadata unavailable'));
      const actions = node('div', 'bundle-actions');
      const badges = node('div', 'badges');
      badges.append(badge(lifecycle), badge(healthValue));
      if (first(bundle, ['metricsStale', 'stale'], false)) badges.append(badge('STALE'));
      const detailsButton = node('button', 'details-button',
        expandedBundleId === id ? 'Hide details' : 'Details');
      detailsButton.type = 'button';
      detailsButton.addEventListener('click', () => toggleBundleDetail(id, detailsButton));
      actions.append(badges, detailsButton);
      head.append(title, actions);
      article.append(head);

      if (expandedBundleId === id) {
        const contributions = normalizedList(first(bundle, ['contributions'], []));
        const healthChecks = normalizedList(first(bundle, ['healthChecks'], []));
        const metrics = normalizedList(first(bundle, ['metrics', 'metricSeries'], []));
        const operations = normalizedList(first(bundle, ['operationCounts'], []));
        const failures = [];
        const failure = first(bundle, ['failure'], null);
        if (failure && first(failure, ['code'], 'NONE') !== 'NONE') failures.push(failure);
        const details = node('div', 'detail-grid');
        details.append(
          detail('Contributions', contributions, contributionLabel),
          detail('Health checks', healthChecks, healthCheckLabel),
          detail('Cached metrics', metrics, metricLabel),
          detail('Operations', operations, operationLabel),
          detail('Host failures', failures, item => first(item, ['code', 'id'], String(item)))
        );
        if (failures.length) details.lastElementChild.classList.add('failure');
        article.append(details);
      }
      list.append(article);
    }

    const capped = bundles.length >= MAX_LOADED_BUNDLES;
    loadMore.hidden = !nextAfter || Boolean(query) || capped;
    byId('pageStatus').textContent = `${visible.length} shown · ${bundles.length} loaded`
      + (capped ? ` · dashboard cap reached (${MAX_LOADED_BUNDLES})` : '');
  }

  function pageItems(page) {
    return normalizedList(first(page, ['items', 'bundles'], []));
  }

  async function loadBundlePages(targetCount, signal) {
    const target = Math.min(MAX_LOADED_BUNDLES, Math.max(PAGE_SIZE, targetCount));
    const items = [];
    const ids = new Set();
    const cursors = new Set();
    let after = null;

    do {
      const cursor = after ? `&after=${encodeURIComponent(after)}` : '';
      const page = await request(`${apiRoot}/bundles?limit=${PAGE_SIZE}${cursor}`, signal);
      for (const item of pageItems(page)) {
        const id = first(item, ['id', 'bundleId'], '');
        if (!ids.has(id) && items.length < MAX_LOADED_BUNDLES) {
          ids.add(id);
          items.push(item);
        }
      }
      const candidate = first(page, ['nextAfter', 'nextCursor'], null);
      if (!candidate || cursors.has(candidate) || items.length >= MAX_LOADED_BUNDLES) {
        after = null;
      } else {
        cursors.add(candidate);
        after = candidate;
      }
    } while (after && items.length < target);

    return { items, nextAfter: after };
  }

  async function toggleBundleDetail(id, button) {
    if (expandedBundleId === id) {
      expandedBundleId = null;
      renderBundles();
      return;
    }
    stopRefresh();
    const controller = new AbortController();
    activeRequest = controller;
    let timedOut = false;
    const timeout = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, REQUEST_TIMEOUT_MS);
    button.disabled = true;
    button.textContent = 'Loading…';
    try {
      const response = await request(
        `${apiRoot}/bundles/${encodeURIComponent(id)}`, controller.signal);
      const detailBundle = first(response, ['bundle'], null);
      if (!detailBundle) throw new Error('detail unavailable');
      bundles = bundles.map(item => first(item, ['id', 'bundleId'], '') === id
        ? { ...item, ...detailBundle } : item);
      expandedBundleId = id;
      renderBundles();
    } catch (error) {
      if (error.status === 401 || error.status === 403) {
        saveSessionKey('');
        showAuthentication('The API key is no longer authorized.');
      } else if (error.status === 503) {
        saveSessionKey('');
        showAuthentication('Plugin operations authentication is not configured on this node.');
      } else if (timedOut) byId('pageStatus').textContent = 'Bundle detail timed out';
      else if (error.name !== 'AbortError') {
        byId('pageStatus').textContent = `Bundle detail failed (${error.message})`;
      }
    } finally {
      window.clearTimeout(timeout);
      if (activeRequest === controller) activeRequest = null;
      scheduleRefresh();
    }
  }

  async function refresh(resetPage) {
    stopRefresh();
    if (!key) {
      showAuthentication('Enter a full API key to view plugin inventory.');
      return;
    }
    const controller = new AbortController();
    activeRequest = controller;
    let timedOut = false;
    const timeout = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, REQUEST_TIMEOUT_MS);
    byId('refreshState').textContent = 'Refreshing…';
    try {
      const summary = await request(apiRoot, controller.signal);
      const loadedPageTarget = resetPage ? PAGE_SIZE : Math.min(
        MAX_LOADED_BUNDLES,
        Math.max(PAGE_SIZE, Math.ceil(bundles.length / PAGE_SIZE) * PAGE_SIZE)
      );
      const pageSet = await loadBundlePages(loadedPageTarget, controller.signal);
      let items = pageSet.items;
      if (expandedBundleId && items.some(
        item => first(item, ['id', 'bundleId'], '') === expandedBundleId)) {
        const detailResponse = await request(
          `${apiRoot}/bundles/${encodeURIComponent(expandedBundleId)}`, controller.signal);
        const detailBundle = first(detailResponse, ['bundle'], null);
        if (detailBundle) {
          items = items.map(item => first(item, ['id', 'bundleId'], '') === expandedBundleId
            ? { ...item, ...detailBundle } : item);
        }
      } else if (expandedBundleId) {
        expandedBundleId = null;
      }
      if (controller.signal.aborted) return;
      bundles = items;
      nextAfter = pageSet.nextAfter;
      renderSummary(summary);
      renderBundles();
      showDashboard();
      byId('refreshState').textContent = 'Cache read complete';
    } catch (error) {
      if (error.name === 'AbortError') {
        if (timedOut) byId('refreshState').textContent = 'Refresh timed out';
        return;
      }
      if (error.status === 401 || error.status === 403) {
        saveSessionKey('');
        showAuthentication(error.status === 403
          ? 'This key is topic-scoped. An unscoped full key is required.'
          : 'The API key is missing or invalid.');
        return;
      }
      if (error.status === 503) {
        saveSessionKey('');
        showAuthentication('Plugin operations authentication is not configured on this node.');
        return;
      }
      byId('refreshState').textContent = `Refresh failed (${error.message})`;
    } finally {
      window.clearTimeout(timeout);
      if (activeRequest === controller) activeRequest = null;
      scheduleRefresh();
    }
  }

  async function loadNextPage() {
    if (!nextAfter || activeRequest) return;
    stopRefresh();
    const controller = new AbortController();
    activeRequest = controller;
    let timedOut = false;
    const timeout = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, REQUEST_TIMEOUT_MS);
    loadMore.disabled = true;
    try {
      const path = `${apiRoot}/bundles?limit=${PAGE_SIZE}&after=${encodeURIComponent(nextAfter)}`;
      const page = await request(path, controller.signal);
      const existing = new Set(bundles.map(item => first(item, ['id', 'bundleId'], '')));
      for (const item of pageItems(page)) {
        if (bundles.length >= MAX_LOADED_BUNDLES) break;
        const id = first(item, ['id', 'bundleId'], '');
        if (!existing.has(id)) {
          bundles.push(item);
          existing.add(id);
        }
      }
      nextAfter = bundles.length >= MAX_LOADED_BUNDLES
        ? null : first(page, ['nextAfter', 'nextCursor'], null);
      renderBundles();
    } catch (error) {
      if (error.status === 401 || error.status === 403) {
        saveSessionKey('');
        showAuthentication('The API key is no longer authorized.');
      } else if (error.status === 503) {
        saveSessionKey('');
        showAuthentication('Plugin operations authentication is not configured on this node.');
      } else if (timedOut) byId('pageStatus').textContent = 'Page request timed out';
      else if (error.name !== 'AbortError') {
        byId('pageStatus').textContent = `Page failed (${error.message})`;
      }
    } finally {
      window.clearTimeout(timeout);
      if (activeRequest === controller) activeRequest = null;
      loadMore.disabled = false;
      scheduleRefresh();
    }
  }

  keyForm.addEventListener('submit', event => {
    event.preventDefault();
    if (!apiPrefix || !apiRoot) return;
    const candidate = keyInput.value;
    if (!candidate) return;
    saveSessionKey(candidate);
    keyInput.value = '';
    authMessage.textContent = 'Authenticating…';
    refresh(true);
  });

  forgetKey.addEventListener('click', () => {
    saveSessionKey('');
    showAuthentication('The session key was removed.');
  });

  search.addEventListener('input', renderBundles);
  loadMore.addEventListener('click', loadNextPage);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopRefresh();
    else if (key) refresh(false);
  });
  window.addEventListener('pagehide', stopRefresh);

  async function initialize() {
    setCredentialEntryEnabled(false);
    showAuthentication('Verifying the host-provided plugin API prefix…');
    try {
      apiPrefix = await discoverApiPrefix();
      apiRoot = apiPrefix === '/'
        ? '/plugin-operations' : `${apiPrefix}/plugin-operations`;
      byId('apiBase').textContent = apiPrefix;
      key = readSessionKey();
      setCredentialEntryEnabled(true);
      if (key) refresh(true);
      else showAuthentication('Enter a full API key to view plugin inventory.');
    } catch (ignored) {
      apiPrefix = null;
      apiRoot = null;
      key = '';
      byId('apiBase').textContent = 'API prefix not verified';
      setCredentialEntryEnabled(false);
      showAuthentication(
        'The host plugin API prefix could not be verified. Credentials are disabled.');
    }
  }

  initialize();
})();
