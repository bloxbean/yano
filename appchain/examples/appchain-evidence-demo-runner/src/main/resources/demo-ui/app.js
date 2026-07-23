'use strict';

const state = {
  catalog: [],
  catalogMeta: {
    page: 1, pageSize: 20, total: 0, totalPages: 1,
    returned: 0, hasPrevious: false, hasNext: false, truncated: false
  },
  catalogToken: null,
  catalogPage: 1,
  catalogPageSize: 20,
  catalogQuery: '',
  catalogInputError: null,
  reports: [],
  reportsToken: null,
  loadToken: null,
  latestItem: null,
  latestToken: null,
  selectedKey: null,
  selectedPayloadText: null,
  refreshing: false,
  refreshQueued: false,
  searchTimer: null,
  toastTimer: null
};

const byId = (id) => document.getElementById(id);
const valueText = (value) => value === null || value === undefined || value === '' ? '—' : String(value);
const evidenceKey = (item) => `${item.evidenceId}:v${item.businessVersion}`;

function create(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function replaceText(id, value) {
  byId(id).textContent = valueText(value);
}

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return valueText(value);
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium', timeStyle: 'medium'
  }).format(date);
}

function formatBytes(value) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

function shortHash(value, length = 14) {
  const text = valueText(value);
  return text.length <= length * 2 + 1 ? text : `${text.slice(0, length)}…${text.slice(-length)}`;
}

function fieldLabel(name) {
  return name.replaceAll(/([A-Z])/g, ' $1').replace(/^./, (letter) => letter.toUpperCase());
}

function displayValue(value) {
  if (Array.isArray(value)) return value.length ? value.join(', ') : 'None';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  return valueText(value);
}

function fillList(id, values) {
  const root = byId(id);
  root.replaceChildren();
  for (const [name, value] of Object.entries(values || {})) {
    const term = create('dt', null, fieldLabel(name));
    const description = create('dd', null, displayValue(value));
    root.append(term, description);
  }
}

function statusClass(value) {
  if (value === 'PASS' || value === 'READY') return 'pass';
  if (value === 'FAIL') return 'fail';
  return 'neutral';
}

function chainView(chain) {
  return chain ? {
    chainId: chain.chainId,
    committedHeight: chain.committedHeight,
    businessStatus: chain.businessStatus,
    memberAgreement: `${chain.membersVerified} members · ${chain.finalityThreshold}-of-${chain.membersVerified}`,
    stateRoot: chain.stateRoot
  } : {};
}

function storageView(storage) {
  return storage ? {
    sha256: storage.sha256,
    size: formatBytes(storage.size),
    objectVersionFingerprint: storage.objectVersionFingerprint,
    ipfsCid: storage.cid,
    objectStateVerified: storage.objectStateVerified,
    ipfsPinVerified: storage.ipfsPinVerified
  } : {};
}

function kafkaView(kafka) {
  return kafka ? {
    topic: kafka.topic,
    partition: kafka.partition,
    offset: kafka.offset,
    eventVerified: kafka.eventVerified
  } : {};
}

function anchorView(anchor) {
  return anchor ? {
    required: anchor.required,
    portableLinkageVerified: anchor.portableLinkageVerified,
    observedAnchoredHeight: anchor.memberObservedAnchoredHeight,
    observedTransaction: anchor.memberObservedTransactionHash,
    datumCommitmentVerified: anchor.memberObservedDatumCommitmentVerified
  } : {};
}

function authorizationView(authorization) {
  if (!authorization) return {};
  const clauses = (authorization.clauses || []).map((clause) =>
    `${clause.clauseId}: ${clause.acceptedCount}/${clause.requiredCount} ${clause.role}`
      + ` · distinct ${String(clause.distinctBy || '').toLowerCase()}`);
  const decisions = (authorization.decisions || []).map((decision) =>
    `${decision.actor} (${decision.role}, ${decision.organization}) → ${decision.clauseId}`);
  return {
    status: authorization.status,
    policy: `${authorization.policyId} · revision ${authorization.policyRevision}`,
    proposer: `${authorization.proposerActor} (${authorization.proposerRole}, ${authorization.proposerOrganization})`,
    satisfiedClauses: clauses,
    acceptedDecisions: decisions,
    payloadDomain: authorization.payloadDomain,
    payloadHash: authorization.payloadHash,
    relayMember: shortHash(authorization.relayMember, 10),
    deadlineHeight: authorization.deadlineHeight
  };
}

function renderAuthorization(cardId, listId, authorization) {
  const card = byId(cardId);
  card.hidden = !authorization;
  if (authorization) fillList(listId, authorizationView(authorization));
  else byId(listId).replaceChildren();
}

function setOutcome(element, value) {
  element.className = `status-pill ${statusClass(value)}`;
  element.textContent = valueText(value);
}

function renderLatest(detail, item) {
  const report = detail.report;
  state.latestItem = item;
  byId('latestEmpty').hidden = true;
  byId('latestContent').hidden = false;
  setOutcome(byId('latestOutcome'), report.outcome);
  replaceText('latestEvidenceId', report.evidenceId);
  replaceText('latestVersion', `v${report.businessVersion}`);
  replaceText('latestStatus', report.chain?.businessStatus);
  replaceText('latestPublished', formatDate(detail.publishedAt));
  replaceText('latestSize', formatBytes(report.storage?.size));
  fillList('latestChain', chainView(report.chain));
  fillList('latestStorage', storageView(report.storage));
  fillList('latestKafka', kafkaView(report.kafka));
  fillList('latestAnchor', anchorView(report.anchor));
  renderAuthorization('latestAuthorizationCard', 'latestAuthorization', report.authorization);
  byId('latestViewButton').disabled = false;
}

function renderNoLatest() {
  state.latestItem = null;
  state.latestToken = null;
  byId('latestEmpty').hidden = false;
  byId('latestContent').hidden = true;
  setOutcome(byId('latestOutcome'), 'Waiting');
  byId('latestViewButton').disabled = true;
}

function renderCatalog() {
  const root = byId('evidenceList');
  root.replaceChildren();
  const total = state.catalogMeta.total;
  replaceText('evidenceCount', `${total} ${total === 1 ? 'record' : 'records'}`);

  if (state.catalog.length === 0) {
    const empty = create('div', 'empty-state compact-empty');
    empty.append(create('strong', null, state.catalogQuery ? 'No matching evidence' : 'No evidence yet'),
      create('span', null, state.catalogQuery
        ? 'Try a different evidence ID or exact version such as v2.'
        : 'Published evidence will appear here.'));
    root.append(empty);
  }

  for (const item of state.catalog) {
    const button = create('button', 'evidence-item');
    button.type = 'button';
    button.setAttribute('aria-label', `Open ${item.evidenceId}, business version ${item.businessVersion}`);

    const heading = create('div', 'evidence-item-heading');
    const identity = create('div');
    identity.append(create('strong', 'evidence-item-title', item.evidenceId),
      create('span', 'evidence-item-date', formatDate(item.publishedAt)));
    const version = create('span', 'version-badge', `v${item.businessVersion}`);
    heading.append(identity, version);

    const metadata = create('div', 'evidence-item-meta');
    metadata.append(
      create('span', `mini-status ${statusClass(item.businessStatus)}`, item.businessStatus || 'Verified'),
      create('span', null, formatBytes(item.size)),
      create('span', 'mono', shortHash(item.sha256, 8)),
      create('span', item.contentAvailable ? 'content-ready' : 'content-pending',
        item.contentAvailable ? 'Document ready' : 'Run verify for document')
    );
    button.append(heading, metadata);
    button.addEventListener('click', () => openEvidence(item));
    root.append(button);
  }

  const meta = state.catalogMeta;
  const first = meta.total === 0 ? 0 : (meta.page - 1) * meta.pageSize + 1;
  const last = meta.total === 0 ? 0 : first + meta.returned - 1;
  byId('previousPageButton').disabled = !meta.hasPrevious;
  byId('nextPageButton').disabled = !meta.hasNext;
  replaceText('pageStatus', meta.total === 0
    ? 'No records'
    : `${first}–${last} of ${meta.total} · Page ${meta.page} of ${meta.totalPages}`);
  const notice = byId('catalogNotice');
  notice.hidden = !state.catalogInputError && !state.catalogMeta.truncated;
  notice.textContent = state.catalogInputError || (state.catalogMeta.truncated
    ? 'The bounded catalog response was truncated; narrow the search to retrieve an exact record.' : '');
}

function renderHistory(reports) {
  const history = byId('history');
  history.replaceChildren();
  if (!reports.length) {
    history.append(create('li', 'activity-empty', 'No scenario activity yet.'));
    return;
  }
  for (const report of reports.slice(0, 12)) {
    const item = create('li', 'activity-item');
    const content = create('div');
    content.append(create('strong', statusClass(report.outcome), `${report.outcome} · ${report.operation || 'LEGACY'}`),
      create('span', null, `${report.evidenceId} · v${report.businessVersion || 'latest'}`),
      create('time', null, formatDate(report.finishedAt)));
    item.append(content);
    if (report.outcome === 'PASS' && report.storage && Number(report.businessVersion) > 0) {
      const open = create('button', 'text-action', 'Open');
      open.type = 'button';
      open.addEventListener('click', () => openEvidence({
        evidenceId: report.evidenceId,
        businessVersion: report.businessVersion
      }));
      item.append(open);
    }
    history.append(item);
  }
}

function renderLoad(report) {
  if (!report) {
    fillList('load', {status: 'No load report yet'});
    return;
  }
  const failures = Object.entries(report.failureCounts || {})
    .map(([code, count]) => `${code}: ${count}`).join(', ') || 'None';
  const stages = Object.entries(report.stages || {})
    .map(([name, value]) => `${name} ${value.succeeded || 0}/${value.attempted || 0}`)
    .join(' · ') || '—';
  fillList('load', {
    outcome: report.outcome,
    mode: report.mode || 'lifecycle',
    requested: report.requested,
    succeeded: report.succeeded,
    failed: report.failed,
    concurrency: report.concurrency,
    maxInFlight: report.maxInFlight,
    maximumObservedInFlight: report.maximumObservedInFlight,
    evidenceCapacityPerBlock: report.evidenceCapacityPerBlock,
    finalityGate: report.finalityGate || '—',
    anchorRequired: report.anchorRequired === true ? 'Yes' : 'No',
    duration: `${report.durationMillis} ms`,
    verifiedEvidencePerSecond: Number(report.successfulPerSecond || 0).toFixed(3),
    appMessagesPerSecond: Number(report.throughput?.appMessagesPerSecond || 0).toFixed(3),
    effectsVerifiedPerSecond: Number(report.throughput?.effectsPerSecond || 0).toFixed(3),
    latencyP95: `${report.latencyMillis?.p95 ?? '—'} ms`,
    stages,
    failures
  });
}

function addProofBadge(root, label, ok, id) {
  const badge = create('span', `proof-badge ${ok ? 'pass' : 'neutral'}`, label);
  if (id) badge.id = id;
  root.append(badge);
  return badge;
}

function renderChecks(checks) {
  const root = byId('checks');
  root.replaceChildren();
  for (const check of checks || []) {
    const item = create('li', `check-item ${statusClass(check.status)}`);
    item.append(create('span', 'check-mark', check.status === 'PASS' ? '✓' : check.status === 'FAIL' ? '!' : '·'),
      create('span', null, fieldLabel(check.name.toLowerCase().replaceAll('_', ' '))));
    root.append(item);
  }
}

function contentUnavailableMessage(reason) {
  return {
    PREVIEW_TOO_LARGE: 'This document is larger than the bounded UI preview limit.',
    NON_JSON_CONTENT: 'This evidence is not a JSON document, so only its verified metadata is shown.',
    INTEGRITY_MISMATCH: 'The local presentation copy failed its integrity check and was not displayed.',
    NOT_MATERIALIZED: 'Run the read-only verify command once to materialize a verified presentation copy.'
  }[reason] || 'The verified document preview is unavailable.';
}

async function sha256Hex(text) {
  if (!globalThis.crypto?.subtle) return null;
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function verifyDisplayedPayload(text, expectedSha256, selectedKey) {
  const badge = byId('browserDigestBadge');
  try {
    const actual = await sha256Hex(text);
    if (state.selectedKey !== selectedKey || !badge) return;
    if (actual === null) {
      badge.textContent = 'Server integrity verified';
      return;
    }
    const matches = actual === expectedSha256;
    badge.className = `proof-badge ${matches ? 'pass' : 'fail'}`;
    badge.textContent = matches ? 'Browser SHA-256 matched' : 'Browser SHA-256 mismatch';
  } catch (_) {
    if (state.selectedKey === selectedKey && badge) badge.textContent = 'Server integrity verified';
  }
}

function renderSelected(detail, selectedKey) {
  const report = detail.report;
  const content = detail.content || {};
  replaceText('detailKicker', `Immutable evidence · business v${report.businessVersion}`);
  replaceText('detailTitle', report.evidenceId);
  replaceText('detailSubtitle', `Published ${formatDate(detail.publishedAt)} · last verified ${formatDate(detail.lastVerifiedAt)}`);

  const badges = byId('detailBadges');
  badges.replaceChildren();
  addProofBadge(badges, `${report.chain?.membersVerified || 0} members agreed`, true);
  addProofBadge(badges, 'Object version verified', content.objectStoreVerified === true);
  addProofBadge(badges, 'IPFS bytes and pin verified', content.ipfsVerified === true);
  if (report.authorization) {
    addProofBadge(badges, 'Role quorum authenticated', report.authorization.status === 'APPROVED');
  }
  addProofBadge(badges, 'Checking displayed SHA-256…', content.integrityVerified === true,
    'browserDigestBadge');

  fillList('selectedChain', chainView(report.chain));
  fillList('selectedStorage', storageView(report.storage));
  fillList('selectedKafka', kafkaView(report.kafka));
  fillList('selectedAnchor', anchorView(report.anchor));
  renderAuthorization('selectedAuthorizationCard', 'selectedAuthorization', report.authorization);
  renderChecks(report.checks);

  const payload = byId('evidencePayload');
  const unavailable = byId('payloadUnavailable');
  const copy = byId('copyPayloadButton');
  if (content.available && typeof content.text === 'string') {
    let rendered = content.text;
    try {
      rendered = JSON.stringify(JSON.parse(content.text), null, 2);
    } catch (_) {
      // The server already validated JSON. Preserve exact text if presentation parsing differs.
    }
    state.selectedPayloadText = content.text;
    payload.textContent = rendered;
    payload.hidden = false;
    unavailable.hidden = true;
    copy.hidden = false;
    replaceText('payloadExplanation',
      `Exact ${formatBytes(content.size)} retrieved from the immutable object version and matched to the pinned IPFS CID. The displayed text is checked against ${shortHash(content.sha256, 10)}.`);
    verifyDisplayedPayload(content.text, content.sha256, selectedKey);
  } else {
    state.selectedPayloadText = null;
    payload.textContent = '';
    payload.hidden = true;
    copy.hidden = true;
    unavailable.replaceChildren(create('strong', null, 'Document preview unavailable'),
      create('span', null, contentUnavailableMessage(content.reason)));
    unavailable.hidden = false;
    replaceText('payloadExplanation',
      'The authenticated metadata and external verification results remain available below.');
    const digestBadge = byId('browserDigestBadge');
    if (digestBadge) digestBadge.textContent = 'Preview not displayed';
  }

  byId('detailLoading').hidden = true;
  byId('detailError').hidden = true;
  byId('detailContent').hidden = false;
}

async function fetchJson(path, optional = false) {
  const response = await fetch(path, {cache: 'no-store'});
  if (optional && response.status === 404) return null;
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

async function openEvidence(item) {
  const selectedKey = evidenceKey(item);
  state.selectedKey = selectedKey;
  state.selectedPayloadText = null;
  byId('detailLoading').hidden = false;
  byId('detailError').hidden = true;
  byId('detailContent').hidden = true;
  replaceText('detailKicker', `Immutable evidence · business v${item.businessVersion}`);
  replaceText('detailTitle', item.evidenceId);
  replaceText('detailSubtitle', 'Loading verified state and external content…');
  const dialog = byId('evidenceDialog');
  if (!dialog.open) dialog.showModal();
  try {
    const detail = await fetchJson(`/api/v1/evidence/${encodeURIComponent(item.evidenceId)}/versions/${item.businessVersion}`);
    if (state.selectedKey !== selectedKey) return;
    renderSelected(detail, selectedKey);
  } catch (_) {
    if (state.selectedKey !== selectedKey) return;
    byId('detailLoading').hidden = true;
    byId('detailContent').hidden = true;
    const error = byId('detailError');
    error.textContent = 'This evidence detail could not be loaded. It may predate the retained demo reports.';
    error.hidden = false;
  }
}

async function refreshLatest(item) {
  if (!item) {
    renderNoLatest();
    return;
  }
  const token = `${evidenceKey(item)}:${item.lastVerifiedAt}`;
  if (state.latestToken === token) return;
  state.latestToken = token;
  try {
    const detail = await fetchJson(`/api/v1/evidence/${encodeURIComponent(item.evidenceId)}/versions/${item.businessVersion}`);
    if (state.latestToken === token) renderLatest(detail, item);
  } catch (_) {
    if (state.latestToken === token) {
      state.latestToken = null;
      byId('latestEmpty').hidden = false;
      byId('latestEmpty').replaceChildren(create('strong', null, 'Latest evidence is temporarily unavailable'),
        create('span', null, 'The next refresh will retry without changing any selected evidence.'));
    }
  }
}

async function refresh() {
  if (document.hidden) return;
  if (state.refreshing) {
    state.refreshQueued = true;
    return;
  }
  state.refreshing = true;
  const requestedPage = state.catalogPage;
  const requestedQuery = state.catalogQuery;
  const catalogParameters = new URLSearchParams({
    page: String(requestedPage),
    pageSize: String(state.catalogPageSize)
  });
  if (requestedQuery) catalogParameters.set('q', requestedQuery);
  try {
    const [catalog, latestCatalog, reports, load] = await Promise.all([
      fetchJson(`/api/v1/evidence?${catalogParameters}`),
      fetchJson('/api/v1/evidence?page=1&pageSize=1'),
      fetchJson('/api/v1/reports'),
      fetchJson('/api/v1/load/latest', true)
    ]);
    const nextCatalog = Array.isArray(catalog.items) ? catalog.items : [];
    const nextCatalogMeta = {
      page: Number(catalog.page || 1),
      pageSize: Number(catalog.pageSize || state.catalogPageSize),
      total: Number(catalog.total || nextCatalog.length),
      totalPages: Number(catalog.totalPages || 1),
      returned: Number(catalog.returned || nextCatalog.length),
      hasPrevious: catalog.hasPrevious === true,
      hasNext: catalog.hasNext === true,
      truncated: catalog.truncated === true
    };
    const catalogToken = JSON.stringify({
      meta: nextCatalogMeta,
      items: nextCatalog.map((item) => [
        item.evidenceId, item.businessVersion, item.lastVerifiedAt,
        item.businessStatus, item.contentAvailable
      ])
    });
    if (state.catalogPage === requestedPage && state.catalogQuery === requestedQuery
        && catalogToken !== state.catalogToken) {
      state.catalogPage = nextCatalogMeta.page;
      state.catalog = nextCatalog;
      state.catalogMeta = nextCatalogMeta;
      state.catalogToken = catalogToken;
      renderCatalog();
    } else if (state.catalogPage !== requestedPage || state.catalogQuery !== requestedQuery) {
      state.refreshQueued = true;
    }

    const nextReports = Array.isArray(reports) ? reports : [];
    const reportsToken = nextReports.map((report) =>
      `${report.scenarioId}:${report.finishedAt}:${report.outcome}`).join('|');
    if (reportsToken !== state.reportsToken) {
      state.reports = nextReports;
      state.reportsToken = reportsToken;
      renderHistory(state.reports);
    }

    const loadToken = load ? `${load.loadId}:${load.finishedAt}:${load.outcome}` : 'none';
    if (loadToken !== state.loadToken) {
      state.loadToken = loadToken;
      renderLoad(load);
    }
    const newest = Array.isArray(latestCatalog.items) ? latestCatalog.items[0] : null;
    await refreshLatest(newest);
    byId('refreshState').textContent = `Live · ${new Intl.DateTimeFormat(undefined, {timeStyle: 'medium'}).format(new Date())}`;
    byId('refreshState').className = 'refresh-state live';
  } catch (_) {
    byId('refreshState').textContent = 'Reconnecting…';
    byId('refreshState').className = 'refresh-state warning';
  } finally {
    state.refreshing = false;
    if (state.refreshQueued) {
      state.refreshQueued = false;
      queueMicrotask(refresh);
    }
  }
}

function showToast(message) {
  const toast = byId('toast');
  toast.textContent = message;
  toast.hidden = false;
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => { toast.hidden = true; }, 2400);
}

byId('evidenceSearch').addEventListener('input', () => {
  clearTimeout(state.searchTimer);
  state.searchTimer = setTimeout(() => {
    const query = byId('evidenceSearch').value.trim().toLowerCase();
    if (query.length > 64 || !/^[a-z0-9-]*$/.test(query)) {
      state.catalogInputError = 'Use up to 64 letters, numbers, or hyphens.';
      renderCatalog();
      return;
    }
    state.catalogInputError = null;
    if (query === state.catalogQuery) {
      renderCatalog();
      return;
    }
    state.catalogQuery = query;
    state.catalogPage = 1;
    state.catalogToken = null;
    refresh();
  }, 250);
});

byId('previousPageButton').addEventListener('click', () => {
  if (!state.catalogMeta.hasPrevious) return;
  state.catalogPage = Math.max(1, state.catalogMeta.page - 1);
  state.catalogToken = null;
  refresh();
});

byId('nextPageButton').addEventListener('click', () => {
  if (!state.catalogMeta.hasNext) return;
  state.catalogPage = state.catalogMeta.page + 1;
  state.catalogToken = null;
  refresh();
});

byId('latestViewButton').addEventListener('click', () => {
  if (state.latestItem) openEvidence(state.latestItem);
});

byId('copyPayloadButton').addEventListener('click', async () => {
  if (state.selectedPayloadText === null) return;
  try {
    await navigator.clipboard.writeText(state.selectedPayloadText);
    showToast('Exact JSON copied');
  } catch (_) {
    showToast('Copy is unavailable in this browser');
  }
});

byId('evidenceDialog').addEventListener('close', () => {
  state.selectedKey = null;
  state.selectedPayloadText = null;
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) refresh();
});

refresh();
setInterval(refresh, 3000);
