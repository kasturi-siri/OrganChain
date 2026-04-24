'use strict';

// ══════════════════════════════════════════════════════════════
// RBAC CONFIGURATION
// ══════════════════════════════════════════════════════════════
const ROLES = {
  hospital_a: {
    id: 'hospital_a', label: 'Hospital A', node: 'HospitalA', city: 'Hyderabad',
    port: 10006, color: '#0c9488', icon: '🏥',
    credentials: { username: 'hospitalA', password: 'HospA@2024' },
    permissions: new Set(['register_donor','register_recipient','view_donors','view_recipients','view_matches','view_transport','my_records','settings']),
    badge: 'HOSPITAL', dashboardStats: ['donors','recipients','matches','transport']
  },
  hospital_b: {
    id: 'hospital_b', label: 'Hospital B', node: 'HospitalB', city: 'Mumbai',
    port: 10008, color: '#2563eb', icon: '🏥',
    credentials: { username: 'hospitalB', password: 'HospB@2024' },
    permissions: new Set(['register_donor','register_recipient','view_donors','view_recipients','view_matches','view_transport','my_records','settings']),
    badge: 'HOSPITAL', dashboardStats: ['donors','recipients','matches','transport']
  },
  admin: {
    id: 'admin', label: 'Admin', node: 'AdminNode', city: 'Chennai',
    port: 10010, color: '#b45309', icon: '⚕️',
    credentials: { username: 'admin', password: 'Admin@2024' },
    permissions: new Set(['view_donors','view_recipients','trigger_match','confirm_match','reject_match','view_matches','view_transport','audit','settings']),
    badge: 'ADMIN', dashboardStats: ['donors','recipients','matches','transport']
  },
  government: {
    id: 'government', label: 'Government', node: 'Government', city: 'Delhi',
    port: 10012, color: '#7c3aed', icon: '🏛️',
    credentials: { username: 'govt', password: 'Govt@2024' },
    permissions: new Set(['view_donors','view_recipients','view_matches','view_transport','audit']),
    badge: 'GOVT', dashboardStats: ['donors','recipients','matches','transport']
  },
  transporter: {
    id: 'transporter', label: 'Transporter', node: 'Transporter', city: 'Chennai',
    port: 10014, color: '#16a34a', icon: '🚑',
    credentials: { username: 'transporter', password: 'Trans@2024' },
    permissions: new Set(['dispatch_transport','update_transport','view_transport','settings']),
    badge: 'TRANSPORT', dashboardStats: ['transport']
  }
};

const PERM_LABELS = {
  register_donor: 'Register Donor', register_recipient: 'Register Patient',
  view_donors: 'View Donors', view_recipients: 'View Recipients',
  trigger_match: 'Trigger Matching', confirm_match: 'Confirm Match',
  reject_match: 'Reject Match', view_matches: 'View Matches',
  view_transport: 'View Transport', dispatch_transport: 'Dispatch',
  update_transport: 'Update Status', audit: 'Audit Trail',
  my_records: 'My Records', settings: 'Settings'
};

const NAV_ITEMS = [
  { id: 'dashboard',  label: 'Dashboard',   icon: '◈', perm: null },
  { id: 'my-records', label: 'My Records',  icon: '🪪', perm: 'my_records' },
  { id: 'donors',     label: 'Donors',      icon: '❤', perm: 'view_donors' },
  { id: 'recipients', label: 'Recipients',  icon: '👤', perm: 'view_recipients' },
  { id: 'matching',   label: 'Matching',    icon: '🔗', perm: 'view_matches' },
  { id: 'transport',  label: 'Transport',   icon: '🚑', perm: 'view_transport' },
  { id: 'audit',      label: 'Audit Trail', icon: '📋', perm: 'audit' },
  { id: 'settings',   label: 'Settings',    icon: '⚙', perm: 'settings' },
];

const VIABILITY = {
  HEART: 4, LUNG: 6, LIVER: 24, PANCREAS: 24,
  KIDNEY: 36, SMALL_INTESTINE: 8, CORNEA: 336
};

// ══════════════════════════════════════════════════════════════
// STATE
// ══════════════════════════════════════════════════════════════
let state = {
  role: null,
  apiBase: 'http://localhost:8080',
  currentPage: 'dashboard',
  donors: [], recipients: [], matches: [], transports: [],
  notifications: [], timerIntervals: [],
  myDonorRecord: null, myRecipientRecord: null,
  pendingMatchCount: 0,
};

// ══════════════════════════════════════════════════════════════
// INIT
// ══════════════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', () => {
  buildRoleGrid();
  buildCompatChart();
  document.addEventListener('click', e => {
    if (!e.target.closest('#notif-btn') && !e.target.closest('#notif-panel'))
      document.getElementById('notif-panel').classList.remove('open');
  });
});

// ══════════════════════════════════════════════════════════════
// LOGIN — role select → credentials modal → app
// ══════════════════════════════════════════════════════════════
let pendingRoleId = null;

function buildRoleGrid() {
  const grid = document.getElementById('role-grid');
  grid.innerHTML = '';
  Object.values(ROLES).forEach(role => {
    const card = document.createElement('div');
    card.className = 'role-card';
    card.style.setProperty('--role-color', role.color);
    card.onclick = () => openCredModal(role.id);
    const perms = [...role.permissions].slice(0, 3)
      .map(p => `<span class="perm-tag">${PERM_LABELS[p] || p}</span>`).join('');
    card.innerHTML = `
      <div class="role-icon">${role.icon}</div>
      <div class="role-name">${role.label}</div>
      <div class="role-city">${role.city}</div>
      <div class="role-perms">${perms}${role.permissions.size > 3 ? `<span class="perm-tag">+${role.permissions.size - 3} more</span>` : ''}</div>
    `;
    grid.appendChild(card);
  });
}

function openCredModal(roleId) {
  pendingRoleId = roleId;
  const role = ROLES[roleId];
  // Set banner
  const banner = document.getElementById('cred-node-banner');
  banner.querySelector('.cred-node-icon').textContent = role.icon;
  banner.querySelector('.cred-node-icon').style.background = role.color + '18';
  banner.querySelector('.cred-node-name').textContent = role.label;
  banner.querySelector('.cred-node-sub').textContent = role.city;
  // Pre-fill username hint (not password for security feel)
  document.getElementById('cred-username').value = role.credentials.username;
  document.getElementById('cred-password').value = '';
  document.getElementById('cred-error').textContent = '';
  document.getElementById('cred-modal').classList.add('open');
  setTimeout(() => document.getElementById('cred-password').focus(), 100);
}

function submitCredentials() {
  const role = ROLES[pendingRoleId];
  if (!role) return;
  const user = document.getElementById('cred-username').value.trim();
  const pass = document.getElementById('cred-password').value;
  const errEl = document.getElementById('cred-error');

  if (user !== role.credentials.username || pass !== role.credentials.password) {
    errEl.textContent = 'Incorrect username or password. Please try again.';
    document.getElementById('cred-password').value = '';
    document.getElementById('cred-password').focus();
    return;
  }

  closeModal('cred-modal');
  state.role = role;
  state.apiBase = 'http://localhost:8080';
  initApp();
}

// Allow pressing Enter in password field
document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('cred-password')?.addEventListener('keydown', e => {
    if (e.key === 'Enter') submitCredentials();
  });
});

function initApp() {
  document.getElementById('login-page').style.display = 'none';
  document.getElementById('app').classList.add('active');
  buildSidebar();
  populateUserBadge();
  navigate('dashboard');
  addNotif('Signed in', `Welcome, ${state.role.label}`, 'success');
}

function logout() {
  state.timerIntervals.forEach(clearInterval);
  state.timerIntervals = [];
  pendingRoleId = null;
  state.role = null;
  state.myDonorRecord = null;
  state.myRecipientRecord = null;
  document.getElementById('app').classList.remove('active');
  document.getElementById('login-page').style.display = 'flex';
  document.querySelectorAll('.role-card').forEach(c => c.classList.remove('selected'));
}

// ══════════════════════════════════════════════════════════════
// SIDEBAR & NAVIGATION
// ══════════════════════════════════════════════════════════════
function buildSidebar() {
  const nav = document.getElementById('sidebar-nav');
  nav.innerHTML = '<div class="nav-section-label">Navigation</div>';
  NAV_ITEMS.forEach(item => {
    if (item.perm && !state.role.permissions.has(item.perm)) return;
    const el = document.createElement('div');
    el.className = 'nav-item';
    el.id = `nav-${item.id}`;
    el.onclick = () => navigate(item.id);
    el.innerHTML = `<span class="nav-icon">${item.icon}</span> ${item.label}`;
    if (item.id === 'matching') {
      el.innerHTML += `<span class="nav-badge" id="nav-badge-matching" style="display:none">0</span>`;
    }
    nav.appendChild(el);
  });
}

function populateUserBadge() {
  const r = state.role;
  const badge = document.getElementById('user-badge');
  badge.textContent = r.badge;
  badge.style.background = r.color + '1a';
  badge.style.color = r.color;
  badge.style.border = `1px solid ${r.color}44`;
  document.getElementById('user-name').textContent = r.label;
  document.getElementById('user-location').textContent = r.city;
  // Topbar chip
  const chip = document.getElementById('topbar-user-chip');
  if (chip) chip.textContent = `${r.icon} ${r.label}`;
}

function navigate(pageId) {
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  const page = document.getElementById(`page-${pageId}`);
  if (!page) return;
  page.classList.add('active');
  const navEl = document.getElementById(`nav-${pageId}`);
  if (navEl) navEl.classList.add('active');
  state.currentPage = pageId;
  document.getElementById('topbar-title').textContent =
    NAV_ITEMS.find(i => i.id === pageId)?.label || pageId;

  if (pageId === 'dashboard')  loadDashboard();
  if (pageId === 'my-records') loadMyRecords();
  if (pageId === 'donors')     loadDonors();
  if (pageId === 'recipients') loadRecipients();
  if (pageId === 'matching')   loadMatching();
  if (pageId === 'transport')  loadTransport();
  if (pageId === 'audit')      buildAuditTrail();
}

function refreshCurrentPage() { navigate(state.currentPage); }

// ══════════════════════════════════════════════════════════════
// API HELPERS
// ══════════════════════════════════════════════════════════════
async function api(method, path, body) {
  const opts = { method, headers: { 'Content-Type': 'application/json' } };
  if (body) opts.body = JSON.stringify(body);
  try {
    const res = await fetch(state.apiBase + path, opts);
    const json = await res.json();
    return { ok: res.ok, status: res.status, data: json };
  } catch (e) {
    return { ok: false, error: e.message, data: null };
  }
}

// ══════════════════════════════════════════════════════════════
// DASHBOARD — only show stats relevant to the role
// ══════════════════════════════════════════════════════════════
async function loadDashboard() {
  document.getElementById('last-refresh-time').textContent = 'Refreshing…';
  const perms = state.role.permissions;

  // Only fetch what this role can see
  const calls = await Promise.all([
    perms.has('view_donors')    ? api('GET', '/api/donor/available')  : Promise.resolve({ ok: false }),
    perms.has('view_recipients')? api('GET', '/api/recipient/waiting'): Promise.resolve({ ok: false }),
    perms.has('view_matches')   ? api('GET', '/api/match/list')       : Promise.resolve({ ok: false }),
    perms.has('view_transport') ? api('GET', '/api/transport/list')   : Promise.resolve({ ok: false }),
  ]);

  const [donors, recipients, matches, transports] = calls;
  const dList = donors.ok && donors.data?.data ? donors.data.data : [];
  const rList = recipients.ok && recipients.data?.data ? recipients.data.data : [];
  const allMatches = matches.ok && matches.data?.data ? matches.data.data : [];
  const allTransports = transports.ok && transports.data?.data ? transports.data.data : [];
  const pending = allMatches.filter(m => m.status === 'PENDING_CONFIRMATION');
  const active  = allTransports.filter(t => ['DISPATCHED','IN_TRANSIT'].includes(t.status));

  // Show/hide stat cards based on role permissions
  const statDonors = document.getElementById('stat-card-donors');
  const statRecip  = document.getElementById('stat-card-recipients');
  const statPend   = document.getElementById('stat-card-pending');
  const statTrans  = document.getElementById('stat-card-transport');

  if (statDonors) statDonors.style.display = perms.has('view_donors') ? '' : 'none';
  if (statRecip)  statRecip.style.display  = perms.has('view_recipients') ? '' : 'none';
  if (statPend)   statPend.style.display   = perms.has('view_matches') ? '' : 'none';
  if (statTrans)  statTrans.style.display  = perms.has('view_transport') ? '' : 'none';

  document.getElementById('stat-donors').textContent     = dList.length;
  document.getElementById('stat-recipients').textContent = rList.length;
  document.getElementById('stat-pending').textContent    = pending.length;
  document.getElementById('stat-transport').textContent  = active.length;
  document.getElementById('last-refresh-time').textContent = new Date().toLocaleTimeString('en-IN', {hour:'2-digit', minute:'2-digit'});

  state.donors = dList; state.recipients = rList;
  state.matches = allMatches; state.transports = allTransports;

  // Match badge
  const badge = document.getElementById('nav-badge-matching');
  if (badge) { badge.textContent = pending.length; badge.style.display = pending.length > 0 ? 'flex' : 'none'; }

  // Sections visible by role
  const recentMatchesCard = document.getElementById('dashboard-recent-matches');
  const viabilityCard     = document.getElementById('dashboard-viability');
  const compatCard        = document.getElementById('dashboard-compat');

  if (recentMatchesCard) recentMatchesCard.style.display = perms.has('view_matches') ? '' : 'none';
  if (viabilityCard)     viabilityCard.style.display     = perms.has('view_transport') ? '' : 'none';
  if (compatCard)        compatCard.style.display        = (perms.has('view_donors') || perms.has('view_recipients')) ? '' : 'none';

  renderRecentMatches(allMatches);
  renderViabilityTimers(active);
}

function renderRecentMatches(matches) {
  const el = document.getElementById('recent-matches-list');
  if (!el) return;
  const recent = matches.slice(0, 5);
  if (!recent.length) {
    el.innerHTML = '<div class="empty-state"><div class="empty-icon">🔗</div><p>No matches yet</p></div>';
    return;
  }
  el.innerHTML = recent.map(m => `
    <div class="audit-entry">
      <div style="padding-top:4px">${getMatchBadge(m.status)}</div>
      <div class="audit-content">
        <div class="audit-action">${organIcon(m.organType)} ${m.organType} · Score ${m.matchScore.toFixed(1)}</div>
        <div class="audit-meta">${m.donorHospital} → ${m.recipientHospital}</div>
      </div>
      <div class="text-sm">${timeAgo(m.matchedAt)}</div>
    </div>
  `).join('');
}

function renderViabilityTimers(active) {
  const el = document.getElementById('viability-list');
  if (!el) return;
  state.timerIntervals.forEach(clearInterval);
  state.timerIntervals = [];

  if (!active.length) {
    el.innerHTML = '<div class="empty-state"><div class="empty-icon">✅</div><p>No active transports</p></div>';
    document.getElementById('critical-count-badge').style.display = 'none';
    return;
  }

  el.innerHTML = active.map(t => {
    const wh = VIABILITY[t.organType] || 24;
    return `
      <div class="flex items-center gap-2 mb-3" style="border-bottom:1px solid var(--border);padding-bottom:0.75rem">
        <span style="font-size:1.2rem">${organIcon(t.organType)}</span>
        <div>
          <div style="font-size:0.85rem;font-weight:600;color:var(--text)">${t.organType} · ${t.originHospital} → ${t.destinationHospital}</div>
          <div class="text-sm">${wh}h viability window</div>
        </div>
        <span class="ml-auto viability-timer" id="timer-${t.linearId.replace(/[^a-z0-9]/gi,'_')}">—</span>
      </div>`;
  }).join('');

  active.forEach(t => {
    const wh = VIABILITY[t.organType] || 24;
    const expiry = new Date(t.dispatchTime).getTime() + wh * 3600000;
    const timerId = `timer-${t.linearId.replace(/[^a-z0-9]/gi,'_')}`;
    const iv = setInterval(() => {
      const el = document.getElementById(timerId);
      if (!el) { clearInterval(iv); return; }
      const rem = expiry - Date.now();
      if (rem <= 0) { el.textContent = 'EXPIRED'; el.className = 'viability-timer timer-critical'; return; }
      const h = Math.floor(rem/3600000), m = Math.floor((rem%3600000)/60000), s = Math.floor((rem%60000)/1000);
      el.textContent = `${h}h ${m}m ${s}s`;
      el.className = 'viability-timer ' + (h < 1 ? 'timer-critical' : h < 4 ? 'timer-warning' : 'timer-ok');
    }, 1000);
    state.timerIntervals.push(iv);
  });

  const crit = active.filter(t => {
    const wh = VIABILITY[t.organType] || 24;
    return (new Date(t.dispatchTime).getTime() + wh * 3600000 - Date.now()) < 3600000;
  }).length;
  const badge = document.getElementById('critical-count-badge');
  badge.textContent = `${crit} CRITICAL`;
  badge.style.display = crit > 0 ? 'inline-flex' : 'none';
}

// ══════════════════════════════════════════════════════════════
// MY RECORDS — hospitals can view their own registered records
// ══════════════════════════════════════════════════════════════
async function loadMyRecords() {
  const el = document.getElementById('my-records-content');
  el.innerHTML = '<div class="loading-row"><span class="spinner"></span> Loading your records…</div>';

  const [donorsRes, recipientsRes] = await Promise.all([
    api('GET', '/api/donor/list'),
    api('GET', '/api/recipient/list'),
  ]);

  const nodeName = state.role.node; // e.g. "HospitalA"
  const myDonors = (donorsRes.ok && donorsRes.data?.data)
    ? donorsRes.data.data.filter(d => d.registeredBy && d.registeredBy.includes(nodeName))
    : [];
  const myRecipients = (recipientsRes.ok && recipientsRes.data?.data)
    ? recipientsRes.data.data.filter(r => r.registeredBy && r.registeredBy.includes(nodeName))
    : [];

  state.myDonorRecord = myDonors;
  state.myRecipientRecord = myRecipients;

  let html = '';

  // ── MY DONORS ──
  html += `
    <div class="my-records-banner">
      <div class="banner-icon">❤️</div>
      <div class="banner-text">
        <h3>Donors Registered by ${state.role.label}</h3>
        <p>${myDonors.length} record${myDonors.length !== 1 ? 's' : ''} found on the ledger</p>
      </div>
    </div>`;

  if (!myDonors.length) {
    html += `<div class="empty-state"><div class="empty-icon">❤</div><p>No donors registered by your node yet</p></div>`;
  } else {
    html += `<div class="table-wrap"><table>
      <thead><tr>
        <th>Organ</th><th>Blood Type</th><th>Age</th><th>Location</th>
        <th>Type</th><th>Status</th><th>Registered</th>
      </tr></thead>
      <tbody>
        ${myDonors.map(d => `
          <tr>
            <td>${organIcon(d.organType)} ${d.organType}</td>
            <td><span class="badge badge-blue">${d.bloodType.replace('_',' ')}</span></td>
            <td>${d.age} yrs</td>
            <td>${d.location}</td>
            <td>${d.isDeceased ? '<span class="badge badge-amber">Deceased</span>' : '<span class="badge badge-gray">Living</span>'}</td>
            <td>${getDonorStatusBadge(d.status)}</td>
            <td class="text-sm">${fmtDate(d.registrationTime)}</td>
          </tr>`).join('')}
      </tbody>
    </table></div>`;
  }

  html += `<div class="divider" style="margin:1.5rem 0"></div>`;

  // ── MY RECIPIENTS ──
  html += `
    <div class="my-records-banner" style="background:linear-gradient(135deg,#eff6ff,#faf5ff);border-color:#bfdbfe">
      <div class="banner-icon">👤</div>
      <div class="banner-text">
        <h3>Recipients Registered by ${state.role.label}</h3>
        <p>${myRecipients.length} record${myRecipients.length !== 1 ? 's' : ''} found on the ledger</p>
      </div>
    </div>`;

  if (!myRecipients.length) {
    html += `<div class="empty-state"><div class="empty-icon">👤</div><p>No recipients registered by your node yet</p></div>`;
  } else {
    html += `<div class="table-wrap"><table>
      <thead><tr>
        <th>Organ Needed</th><th>Blood Type</th><th>Age</th><th>Location</th>
        <th>Condition</th><th>Paired</th><th>Status</th><th>Registered</th>
      </tr></thead>
      <tbody>
        ${myRecipients.map(r => `
          <tr>
            <td>${organIcon(r.organNeeded)} ${r.organNeeded}</td>
            <td><span class="badge badge-blue">${r.bloodType.replace('_',' ')}</span></td>
            <td>${r.age} yrs</td>
            <td>${r.location}</td>
            <td>
              <div style="display:flex;align-items:center;gap:6px">
                <div style="width:44px;background:var(--border);border-radius:99px;height:6px;overflow:hidden">
                  <div style="width:${r.conditionScore*10}%;height:100%;background:${r.conditionScore>=8?'var(--red)':r.conditionScore>=5?'var(--amber)':'var(--green)'}"></div>
                </div>
                <span class="mono">${r.conditionScore}/10</span>
              </div>
            </td>
            <td>${r.hasPairedDonor ? '<span class="badge badge-purple">Paired</span>' : '—'}</td>
            <td>${getRecipientStatusBadge(r.status)}</td>
            <td class="text-sm">${fmtDate(r.registrationTime)}</td>
          </tr>`).join('')}
      </tbody>
    </table></div>`;
  }

  el.innerHTML = html;
}

// ══════════════════════════════════════════════════════════════
// DONORS
// ══════════════════════════════════════════════════════════════
async function loadDonors() {
  setTableLoading('donor-tbody', 10);
  const res = await api('GET', '/api/donor/list');
  if (!res.ok || !res.data?.data) {
    renderDonorRows([]);
    showToast('error', 'Failed to load donors', res.error || 'Network error');
    return;
  }
  state.donors = res.data.data;
  document.getElementById('donor-count').textContent = state.donors.length;
  renderDonorRows(state.donors);
  populateDonorSelect();
  const btn = document.getElementById('btn-register-donor');
  if (btn) btn.style.display = state.role.permissions.has('register_donor') ? '' : 'none';
}

function renderDonorRows(donors) {
  const tbody = document.getElementById('donor-tbody');
  if (!donors.length) {
    tbody.innerHTML = `<tr><td colspan="10"><div class="empty-state"><div class="empty-icon">❤</div><p>No donors found</p></div></td></tr>`;
    return;
  }
  tbody.innerHTML = donors.map(d => `
    <tr>
      <td class="id-cell" title="${d.linearId}">${shortId(d.linearId)}</td>
      <td>${organIcon(d.organType)} ${d.organType}</td>
      <td><span class="badge badge-blue">${d.bloodType.replace('_',' ')}</span></td>
      <td>${d.age} yrs</td>
      <td>${d.location}</td>
      <td>${d.isDeceased ? '<span class="badge badge-amber">Deceased</span>' : '<span class="badge badge-gray">Living</span>'}</td>
      <td>${getDonorStatusBadge(d.status)}</td>
      <td class="text-sm">${d.registeredBy}</td>
      <td class="text-sm">${fmtDate(d.registrationTime)}</td>
      <td>${d.status === 'AVAILABLE' && state.role.permissions.has('trigger_match')
        ? `<button class="btn btn-sm btn-amber" onclick="quickTrigger('${d.linearId}')">⚡ Match</button>` : '—'}</td>
    </tr>`).join('');
}

function filterDonors() {
  const q = document.getElementById('donor-search').value.toLowerCase();
  const status = document.getElementById('donor-status-filter').value;
  const organ  = document.getElementById('donor-organ-filter').value;
  renderDonorRows(state.donors.filter(d =>
    (!q || d.linearId.toLowerCase().includes(q) || d.location.toLowerCase().includes(q)) &&
    (!status || d.status === status) && (!organ || d.organType === organ)
  ));
}

async function registerDonor() {
  const payload = {
    name: document.getElementById('d-name').value.trim(),
    contact: document.getElementById('d-contact').value.trim(),
    bloodType: document.getElementById('d-blood').value,
    organType: document.getElementById('d-organ').value,
    age: parseInt(document.getElementById('d-age').value),
    weightKg: parseFloat(document.getElementById('d-weight').value),
    heightCm: parseFloat(document.getElementById('d-height').value),
    location: document.getElementById('d-location').value.trim(),
    isDeceased: document.getElementById('d-deceased').checked,
  };
  if (!payload.name || !payload.bloodType || !payload.organType || !payload.location || isNaN(payload.age)) {
    showToast('warning', 'Validation', 'Please fill all required fields');
    return;
  }
  const res = await api('POST', '/api/donor/register', payload);
  if (res.ok && res.data?.success) {
    showToast('success', 'Donor Registered', `${payload.organType} donor added to ledger`);
    addNotif('New Donor', `${payload.organType} donor registered from ${payload.location}`, 'success');
    closeModal('donor-modal');
    loadDonors();
  } else {
    showToast('error', 'Registration Failed', res.data?.message || 'Please check the server logs');
  }
}

function openDonorModal() { document.getElementById('donor-modal').classList.add('open'); }

// ══════════════════════════════════════════════════════════════
// RECIPIENTS
// ══════════════════════════════════════════════════════════════
async function loadRecipients() {
  setTableLoading('recipient-tbody', 11);
  const res = await api('GET', '/api/recipient/list');
  if (!res.ok || !res.data?.data) {
    renderRecipientRows([]);
    showToast('error', 'Failed to load recipients', res.error || 'Network error');
    return;
  }
  state.recipients = res.data.data;
  document.getElementById('recipient-count').textContent = state.recipients.length;
  renderRecipientRows(state.recipients);
  const btn = document.getElementById('btn-register-recipient');
  if (btn) btn.style.display = state.role.permissions.has('register_recipient') ? '' : 'none';
}

function renderRecipientRows(recipients) {
  const tbody = document.getElementById('recipient-tbody');
  if (!recipients.length) {
    tbody.innerHTML = `<tr><td colspan="11"><div class="empty-state"><div class="empty-icon">👤</div><p>No recipients found</p></div></td></tr>`;
    return;
  }
  tbody.innerHTML = recipients.map(r => `
    <tr>
      <td class="id-cell" title="${r.linearId}">${shortId(r.linearId)}</td>
      <td>${organIcon(r.organNeeded)} ${r.organNeeded}</td>
      <td><span class="badge badge-blue">${r.bloodType.replace('_',' ')}</span></td>
      <td>${r.age} yrs</td>
      <td>${r.location}</td>
      <td>
        <div style="display:flex;align-items:center;gap:6px">
          <div style="width:44px;background:var(--border);border-radius:99px;height:6px;overflow:hidden">
            <div style="width:${r.conditionScore*10}%;height:100%;background:${r.conditionScore>=8?'var(--red)':r.conditionScore>=5?'var(--amber)':'var(--green)'}"></div>
          </div>
          <span class="mono">${r.conditionScore}/10</span>
        </div>
      </td>
      <td class="mono">#${r.serialNumber}</td>
      <td>${r.hasPairedDonor ? '<span class="badge badge-purple">Paired</span>' : '—'}</td>
      <td>${getRecipientStatusBadge(r.status)}</td>
      <td class="text-sm">${r.registeredBy}</td>
      <td>—</td>
    </tr>`).join('');
}

function filterRecipients() {
  const q = document.getElementById('recipient-search').value.toLowerCase();
  const status = document.getElementById('recipient-status-filter').value;
  const organ  = document.getElementById('recipient-organ-filter').value;
  renderRecipientRows(state.recipients.filter(r =>
    (!q || r.linearId.toLowerCase().includes(q) || r.location.toLowerCase().includes(q)) &&
    (!status || r.status === status) && (!organ || r.organNeeded === organ)
  ));
}

async function registerRecipient() {
  const payload = {
    name: document.getElementById('r-name').value.trim(),
    contact: document.getElementById('r-contact').value.trim(),
    bloodType: document.getElementById('r-blood').value,
    organNeeded: document.getElementById('r-organ').value,
    age: parseInt(document.getElementById('r-age').value),
    weightKg: parseFloat(document.getElementById('r-weight').value),
    heightCm: parseFloat(document.getElementById('r-height').value),
    location: document.getElementById('r-location').value.trim(),
    conditionScore: parseInt(document.getElementById('r-condition').value),
    serialNumber: parseInt(document.getElementById('r-serial').value),
    hasPairedDonor: document.getElementById('r-paired').checked,
  };
  if (!payload.name || !payload.bloodType || !payload.organNeeded || !payload.location) {
    showToast('warning', 'Validation', 'Please fill all required fields');
    return;
  }
  const res = await api('POST', '/api/recipient/register', payload);
  if (res.ok && res.data?.success) {
    showToast('success', 'Recipient Registered', `${payload.organNeeded} recipient added to waitlist`);
    addNotif('New Recipient', `Patient waiting for ${payload.organNeeded} in ${payload.location}`, 'info');
    closeModal('recipient-modal');
    loadRecipients();
  } else {
    showToast('error', 'Registration Failed', res.data?.message || 'Please check the server logs');
  }
}

function openRecipientModal() { document.getElementById('recipient-modal').classList.add('open'); }

// ══════════════════════════════════════════════════════════════
// MATCHING
// ══════════════════════════════════════════════════════════════
async function loadMatching() {
  setTableLoading('match-tbody', 10);
  const triggerPanel = document.getElementById('trigger-match-panel');
  const actionCol    = document.getElementById('match-action-col');
  if (state.role.permissions.has('trigger_match')) {
    triggerPanel.style.display = '';
    await loadDonors();
    populateDonorSelect();
  } else {
    triggerPanel.style.display = 'none';
  }
  if (state.role.permissions.has('confirm_match') || state.role.permissions.has('reject_match')) {
    if (actionCol) actionCol.style.display = '';
  }
  await loadMatches();
}

async function loadMatches() {
  const res = await api('GET', '/api/match/list');
  if (!res.ok || !res.data?.data) {
    document.getElementById('match-tbody').innerHTML =
      `<tr><td colspan="10"><div class="empty-state"><p>Could not load matches</p></div></td></tr>`;
    return;
  }
  state.matches = res.data.data;
  document.getElementById('match-count').textContent = state.matches.length;
  filterMatches();
  const pending = state.matches.filter(m => m.status === 'PENDING_CONFIRMATION').length;
  const badge = document.getElementById('nav-badge-matching');
  if (badge) { badge.textContent = pending; badge.style.display = pending > 0 ? 'flex' : 'none'; }
}

function filterMatches() {
  const status = document.getElementById('match-status-filter').value;
  renderMatchRows(status ? state.matches.filter(m => m.status === status) : state.matches);
}

function renderMatchRows(matches) {
  const tbody = document.getElementById('match-tbody');
  if (!matches.length) {
    tbody.innerHTML = `<tr><td colspan="10"><div class="empty-state"><div class="empty-icon">🔗</div><p>No matches found</p></div></td></tr>`;
    return;
  }
  const canAct = state.role.permissions.has('confirm_match') || state.role.permissions.has('reject_match');
  tbody.innerHTML = matches.map(m => `
    <tr>
      <td class="id-cell" title="${m.linearId}">${shortId(m.linearId)}</td>
      <td>${organIcon(m.organType)} ${m.organType}</td>
      <td>
        <span class="mono">${m.matchScore.toFixed(1)}</span>
        <button class="btn btn-sm btn-ghost" style="margin-left:4px;padding:1px 4px" onclick="showScoreBreakdown(${m.matchScore},'${m.organType}')">📊</button>
      </td>
      <td><span class="badge ${m.crossMatchResult === 'POSITIVE' ? 'badge-teal' : 'badge-red'}">${m.crossMatchResult}</span></td>
      <td class="text-sm">${m.donorHospital}</td>
      <td class="text-sm">${m.recipientHospital}</td>
      <td>${getMatchBadge(m.status)}</td>
      <td class="text-sm">${fmtDate(m.matchedAt)}</td>
      <td class="text-sm" style="max-width:140px;overflow:hidden;text-overflow:ellipsis">${m.rejectionReason || '—'}</td>
      <td>${canAct && m.status === 'PENDING_CONFIRMATION' ? `
        <div class="flex gap-2">
          ${state.role.permissions.has('confirm_match') ? `<button class="btn btn-sm btn-primary" onclick="confirmMatch('${m.linearId}')">✓ Confirm</button>` : ''}
          ${state.role.permissions.has('reject_match')  ? `<button class="btn btn-sm btn-danger"  onclick="openRejectModal('${m.linearId}')">✗ Reject</button>`  : ''}
        </div>` : '—'}</td>
    </tr>`).join('');
}

function populateDonorSelect() {
  const sel = document.getElementById('trigger-donor-select');
  if (!sel) return;
  const avail = state.donors.filter(d => d.status === 'AVAILABLE');
  sel.innerHTML = '<option value="">— Select Available Donor —</option>' +
    avail.map(d => `<option value="${d.linearId}">${organIcon(d.organType)} ${d.organType} · ${d.bloodType.replace('_',' ')} · ${d.location}</option>`).join('');
}

async function triggerMatching() {
  const donorId = document.getElementById('trigger-donor-select').value;
  if (!donorId) { showToast('warning', 'Select a donor', ''); return; }
  const res = await api('POST', `/api/match/trigger/${encodeURIComponent(donorId)}`);
  if (res.ok && res.data?.success) {
    showToast('success', 'Match Found', 'Algorithm successfully found a compatible recipient');
    addNotif('Match Found', 'Organ matching algorithm completed', 'success');
    loadMatches();
  } else {
    showToast('error', 'No Match Found', res.data?.message || 'No compatible recipient found');
  }
}

async function quickTrigger(donorId) {
  navigate('matching');
  setTimeout(() => {
    const sel = document.getElementById('trigger-donor-select');
    if (sel) sel.value = donorId;
  }, 300);
}

async function confirmMatch(matchId) {
  const res = await api('POST', `/api/match/confirm/${encodeURIComponent(matchId)}`);
  if (res.ok && res.data?.success) {
    showToast('success', 'Match Confirmed', 'Transport can now be dispatched');
    addNotif('Match Confirmed', `Match ${shortId(matchId)} confirmed`, 'success');
    loadMatches();
  } else {
    showToast('error', 'Failed', res.data?.message || 'Error confirming match');
  }
}

function openRejectModal(matchId) {
  document.getElementById('reject-match-id').value = matchId;
  document.getElementById('reject-reason').value = '';
  document.getElementById('reject-modal').classList.add('open');
}

async function confirmReject() {
  const matchId = document.getElementById('reject-match-id').value;
  const reason  = document.getElementById('reject-reason').value.trim();
  if (!reason) { showToast('warning', 'Reason required', 'Please provide a rejection reason'); return; }
  const res = await api('POST', `/api/match/reject/${encodeURIComponent(matchId)}`, { reason });
  if (res.ok && res.data?.success) {
    showToast('info', 'Match Rejected', reason);
    closeModal('reject-modal');
    loadMatches();
  } else {
    showToast('error', 'Failed', res.data?.message || 'Error rejecting match');
  }
}

function showScoreBreakdown(score, organType) {
  const max = 110;
  document.getElementById('score-modal-content').innerHTML = `
    <div class="mb-3">
      <div class="stat-label">Total Match Score</div>
      <div style="font-family:var(--font-head);font-size:2rem;font-weight:800;color:var(--teal)">${score.toFixed(1)}</div>
      <div class="text-sm">${organIcon(organType)} ${organType} · out of ~110 pts maximum</div>
    </div>
    <div class="score-breakdown" style="margin-top:1rem">
      <div class="score-row"><span class="score-row-label">Location Match</span><div class="score-bar-bg"><div class="score-bar-fill" style="width:${15/max*100}%"></div></div><span class="score-row-val">max 15</span></div>
      <div class="score-row"><span class="score-row-label">Paired Donor (KPE)</span><div class="score-bar-bg"><div class="score-bar-fill" style="width:${20/max*100}%"></div></div><span class="score-row-val">max 20</span></div>
      <div class="score-row"><span class="score-row-label">Size compatibility</span><div class="score-bar-bg"><div class="score-bar-fill" style="width:${15/max*100}%"></div></div><span class="score-row-val">max 15</span></div>
      <div class="score-row"><span class="score-row-label">Age compatibility</span><div class="score-bar-bg"><div class="score-bar-fill" style="width:${10/max*100}%"></div></div><span class="score-row-val">max 10</span></div>
      <div class="score-row"><span class="score-row-label">Urgency (cond×5)</span><div class="score-bar-bg"><div class="score-bar-fill" style="width:${50/max*100}%;background:var(--amber)"></div></div><span class="score-row-val">max 50</span></div>
    </div>
    <div class="divider"></div>
    <p class="form-hint">Hard gates applied: blood-type compatibility (pre-filter) and cross-match test (post-score). Notary prevents double assignment.</p>`;
  document.getElementById('score-modal').classList.add('open');
}

// ══════════════════════════════════════════════════════════════
// TRANSPORT
// ══════════════════════════════════════════════════════════════
async function loadTransport() {
  document.getElementById('transport-cards').innerHTML =
    '<div class="loading-row"><span class="spinner"></span> Loading transports…</div>';
  document.getElementById('transport-empty').style.display = 'none';

  const dispPanel = document.getElementById('dispatch-panel');
  dispPanel.style.display = state.role.permissions.has('dispatch_transport') ? '' : 'none';
  if (state.role.permissions.has('dispatch_transport')) await loadConfirmedMatches();

  const res = await api('GET', '/api/transport/list');
  if (!res.ok || !res.data?.data) {
    document.getElementById('transport-cards').innerHTML = '';
    document.getElementById('transport-empty').style.display = '';
    showToast('error', 'Failed', 'Could not load transports');
    return;
  }
  state.transports = res.data.data;
  document.getElementById('transport-count').textContent = state.transports.length;
  renderTransportCards(state.transports);
}

async function loadConfirmedMatches() {
  const res = await api('GET', '/api/match/list');
  const sel = document.getElementById('dispatch-match-select');
  if (!res.ok || !res.data?.data) { sel.innerHTML = '<option>No confirmed matches</option>'; return; }
  const confirmed = res.data.data.filter(m => m.status === 'CONFIRMED');
  sel.innerHTML = '<option value="">— Select Confirmed Match —</option>' +
    confirmed.map(m => `<option value="${m.linearId}">${organIcon(m.organType)} ${m.organType} · ${m.donorHospital} → ${m.recipientHospital}</option>`).join('');
}

function renderTransportCards(transports) {
  const container = document.getElementById('transport-cards');
  const empty = document.getElementById('transport-empty');
  state.timerIntervals.forEach(clearInterval);
  state.timerIntervals = [];

  if (!transports.length) { container.innerHTML = ''; empty.style.display = ''; return; }
  empty.style.display = 'none';

  container.innerHTML = transports.map(t => {
    const wh = VIABILITY[t.organType] || 24;
    const progress = { DISPATCHED:20, IN_TRANSIT:60, DELIVERED:100, FAILED:50 }[t.status] || 0;
    const tid = `ttimer-${t.linearId.replace(/[^a-z0-9]/gi,'_')}`;
    return `
      <div class="card" style="position:relative">
        <div style="position:absolute;top:12px;right:12px">${getTransportStatusBadge(t.status)}</div>
        <div style="font-size:1.5rem;margin-bottom:0.5rem">${organIcon(t.organType)}</div>
        <div style="font-family:var(--font-head);font-weight:700;font-size:1rem;margin-bottom:0.2rem;color:var(--text)">${t.organType}</div>
        <div class="text-sm mb-3">${wh}h viability window</div>
        <div class="transport-track">
          <div class="track-node"><div class="track-dot filled"></div><span class="track-label">${t.originHospital}</span></div>
          <div class="track-line"><div class="track-line-fill" style="width:${progress}%"></div></div>
          <div class="track-node"><div class="track-dot ${progress===100?'filled':''}"></div><span class="track-label">${t.destinationHospital}</span></div>
        </div>
        <div class="flex items-center gap-2 mt-3">
          <span class="text-sm">Viability remaining:</span>
          <span class="viability-timer ml-auto" id="${tid}">—</span>
        </div>
        <div class="text-sm mt-2">Dispatched: ${fmtDate(t.dispatchTime)}</div>
        ${t.deliveredAt ? `<div class="text-sm">Delivered: ${fmtDate(t.deliveredAt)}</div>` : ''}
        ${state.role.permissions.has('update_transport') && ['DISPATCHED','IN_TRANSIT'].includes(t.status) ? `
          <div class="divider"></div>
          <button class="btn btn-sm btn-secondary" onclick="openTransportUpdate('${t.linearId}')">Update Status</button>` : ''}
      </div>`;
  }).join('');

  transports.forEach(t => {
    if (!['DISPATCHED','IN_TRANSIT'].includes(t.status)) return;
    const wh = VIABILITY[t.organType] || 24;
    const expiry = new Date(t.dispatchTime).getTime() + wh * 3600000;
    const id = `ttimer-${t.linearId.replace(/[^a-z0-9]/gi,'_')}`;
    const iv = setInterval(() => {
      const el = document.getElementById(id);
      if (!el) { clearInterval(iv); return; }
      const rem = expiry - Date.now();
      if (rem <= 0) { el.textContent = 'EXPIRED'; el.className = 'viability-timer timer-critical'; return; }
      const h = Math.floor(rem/3600000), m = Math.floor((rem%3600000)/60000), s = Math.floor((rem%60000)/1000);
      el.textContent = `${h}h ${m}m ${s}s`;
      el.className = 'viability-timer ' + (h<1?'timer-critical':h<4?'timer-warning':'timer-ok');
    }, 1000);
    state.timerIntervals.push(iv);
  });
}

async function dispatchTransport() {
  const matchId = document.getElementById('dispatch-match-select').value;
  if (!matchId) { showToast('warning', 'Select a match', ''); return; }
  const res = await api('POST', `/api/transport/dispatch/${encodeURIComponent(matchId)}`);
  if (res.ok && res.data?.success) {
    showToast('success', 'Transport Dispatched', 'Organ is on the way');
    addNotif('Transport Dispatched', 'Organ transport initiated', 'info');
    loadTransport();
  } else {
    showToast('error', 'Dispatch Failed', res.data?.message || 'Error');
  }
}

function openTransportUpdate(transportId) {
  document.getElementById('update-transport-id').value = transportId;
  document.getElementById('transport-update-modal').classList.add('open');
}

async function submitTransportUpdate() {
  const transportId = document.getElementById('update-transport-id').value;
  const newStatus   = document.getElementById('transport-new-status').value;
  const res = await api('POST', `/api/transport/update/${encodeURIComponent(transportId)}`, { newStatus });
  if (res.ok && res.data?.success) {
    showToast('success', 'Status Updated', `Transport marked as ${newStatus.replace('_',' ')}`);
    closeModal('transport-update-modal');
    loadTransport();
  } else {
    showToast('error', 'Update Failed', res.data?.message || 'Error');
  }
}

// ══════════════════════════════════════════════════════════════
// AUDIT TRAIL
// ══════════════════════════════════════════════════════════════
async function buildAuditTrail() {
  const el = document.getElementById('audit-entries');
  el.innerHTML = '<div class="loading-row"><span class="spinner"></span> Reconstructing audit trail…</div>';

  const [donors, recipients, matches, transports] = await Promise.all([
    api('GET', '/api/donor/list'), api('GET', '/api/recipient/list'),
    api('GET', '/api/match/list'), api('GET', '/api/transport/list'),
  ]);

  const events = [];
  const dList = donors.ok && donors.data?.data ? donors.data.data : [];
  dList.forEach(d => events.push({ time: d.registrationTime, color: '#0c9488', icon: '❤',
    action: `Donor Registered — ${d.organType}`,
    meta: `${d.bloodType.replace('_',' ')} · ${d.location} · ${d.registeredBy}`, id: d.linearId }));

  const rList = recipients.ok && recipients.data?.data ? recipients.data.data : [];
  rList.forEach(r => events.push({ time: r.registrationTime, color: '#2563eb', icon: '👤',
    action: `Recipient Registered — ${r.organNeeded}`,
    meta: `${r.bloodType.replace('_',' ')} · ${r.location} · Condition ${r.conditionScore}/10`, id: r.linearId }));

  const mList = matches.ok && matches.data?.data ? matches.data.data : [];
  mList.forEach(m => {
    events.push({ time: m.matchedAt, color: '#b45309', icon: '🔗',
      action: `Match Created — ${m.organType} · Score ${m.matchScore.toFixed(1)}`,
      meta: `${m.donorHospital} → ${m.recipientHospital}`, id: m.linearId });
    if (m.resolvedAt) events.push({ time: m.resolvedAt,
      color: m.status === 'CONFIRMED' ? '#16a34a' : '#dc2626',
      icon: m.status === 'CONFIRMED' ? '✓' : '✗',
      action: `Match ${m.status} — ${m.organType}`,
      meta: m.rejectionReason ? `Reason: ${m.rejectionReason}` : 'Approved', id: m.linearId });
  });

  const tList = transports.ok && transports.data?.data ? transports.data.data : [];
  tList.forEach(t => {
    events.push({ time: t.dispatchTime, color: '#7c3aed', icon: '🚑',
      action: `Transport Dispatched — ${t.organType}`,
      meta: `${t.originHospital} → ${t.destinationHospital}`, id: t.linearId });
    if (t.deliveredAt) events.push({ time: t.deliveredAt, color: '#16a34a', icon: '✅',
      action: `Organ Delivered — ${t.organType}`,
      meta: `Delivered to ${t.destinationHospital}`, id: t.linearId });
  });

  events.sort((a, b) => new Date(b.time) - new Date(a.time));

  if (!events.length) {
    el.innerHTML = '<div class="empty-state"><div class="empty-icon">📋</div><p>No ledger events found</p></div>';
    return;
  }

  el.innerHTML = events.map(e => `
    <div class="audit-entry">
      <div style="display:flex;flex-direction:column;align-items:center;gap:0;margin-top:3px">
        <div style="width:24px;height:24px;border-radius:50%;background:${e.color}18;border:2px solid ${e.color};display:flex;align-items:center;justify-content:center;font-size:0.7rem;flex-shrink:0">${e.icon}</div>
        <div style="width:1px;flex:1;background:var(--border);margin-top:4px;min-height:16px"></div>
      </div>
      <div class="audit-content" style="padding-bottom:0.75rem">
        <div class="audit-action">${e.action}</div>
        <div class="audit-meta">${e.meta}</div>
        <div style="display:flex;gap:0.5rem;align-items:center;margin-top:4px">
          <span class="audit-id">${shortId(e.id)}</span>
          <span class="text-sm">${fmtDate(e.time)}</span>
          <span style="font-size:0.7rem;color:var(--text-3)">${timeAgo(e.time)}</span>
        </div>
      </div>
    </div>`).join('');
}

// ══════════════════════════════════════════════════════════════
// SETTINGS
// ══════════════════════════════════════════════════════════════
function saveSettings() {
  const newUrl = document.getElementById('settings-api-url').value.replace(/\/$/, '');
  state.apiBase = newUrl;
  showToast('success', 'Settings Saved', 'API base URL updated');
}

async function testConnection() {
  const res = await api('GET', '/api/donor/list');
  if (res.ok) showToast('success', 'Connection OK', 'Server is reachable');
  else showToast('error', 'Connection Failed', res.error || `HTTP ${res.status}`);
}

// ══════════════════════════════════════════════════════════════
// NOTIFICATIONS
// ══════════════════════════════════════════════════════════════
function addNotif(title, body, type = 'info') {
  state.notifications.unshift({ title, body, type, time: new Date() });
  renderNotifList();
  document.getElementById('notif-dot').classList.add('active');
}

function renderNotifList() {
  const list = document.getElementById('notif-list');
  if (!state.notifications.length) { list.innerHTML = '<div class="notif-empty">No notifications</div>'; return; }
  list.innerHTML = state.notifications.slice(0, 20).map(n => `
    <div class="notif-item">
      <div class="notif-item-title">${n.title}</div>
      <div class="text-sm">${n.body}</div>
      <div class="notif-item-time">${timeAgo(n.time)}</div>
    </div>`).join('');
}

function toggleNotifPanel() {
  document.getElementById('notif-panel').classList.toggle('open');
  document.getElementById('notif-dot').classList.remove('active');
}

function clearNotifs() { state.notifications = []; renderNotifList(); }

// ══════════════════════════════════════════════════════════════
// TOAST
// ══════════════════════════════════════════════════════════════
function showToast(type, title, body) {
  const icons = { success: '✓', error: '✕', warning: '⚠', info: 'ℹ' };
  const container = document.getElementById('toast-container');
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.innerHTML = `
    <span style="font-size:1rem;color:var(--${type==='success'?'green':type==='error'?'red':type==='warning'?'amber':'blue'})">${icons[type]||'ℹ'}</span>
    <div class="toast-msg">
      ${title ? `<div class="toast-title">${title}</div>` : ''}
      ${body  ? `<div class="toast-body">${body}</div>`   : ''}
    </div>`;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0'; toast.style.transform = 'translateX(16px)';
    toast.style.transition = 'all 0.3s ease';
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}

// ══════════════════════════════════════════════════════════════
// MODAL HELPERS
// ══════════════════════════════════════════════════════════════
function closeModal(id) { document.getElementById(id).classList.remove('open'); }
document.addEventListener('click', e => {
  if (e.target.classList.contains('modal-overlay')) e.target.classList.remove('open');
});

// ══════════════════════════════════════════════════════════════
// BLOOD TYPE COMPAT CHART
// ══════════════════════════════════════════════════════════════
function buildCompatChart() {
  const types = ['O-','O+','A-','A+','B-','B+','AB-','AB+'];
  const compat = { 'O-':['O-','O+','A-','A+','B-','B+','AB-','AB+'], 'O+':['O+','A+','B+','AB+'], 'A-':['A-','A+','AB-','AB+'], 'A+':['A+','AB+'], 'B-':['B-','B+','AB-','AB+'], 'B+':['B+','AB+'], 'AB-':['AB-','AB+'], 'AB+':['AB+'] };
  const el = document.getElementById('compat-chart');
  if (!el) return;
  let html = '<div class="compat-grid"><div class="compat-cell compat-hdr">D→R</div>';
  types.forEach(t => html += `<div class="compat-cell compat-hdr">${t}</div>`);
  types.forEach(rec => {
    html += `<div class="compat-cell compat-hdr">${rec}</div>`;
    types.forEach(don => {
      const ok = compat[don]?.includes(rec);
      html += `<div class="compat-cell ${ok?'compat-yes':'compat-no'}">${ok?'✓':'·'}</div>`;
    });
  });
  html += '</div><p class="form-hint" style="margin-top:0.5rem">D = Donor blood type (column header), R = Recipient blood type (row header)</p>';
  el.innerHTML = html;
}

// ══════════════════════════════════════════════════════════════
// UTILITIES
// ══════════════════════════════════════════════════════════════
function shortId(id) {
  if (!id) return '—';
  const part = id.split(',')[0].replace(/[{}]/g, '');
  return part.length > 12 ? part.slice(0, 8) + '…' : part;
}

function fmtDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleDateString('en-IN', { day:'2-digit', month:'short', year:'numeric' }) +
    ' ' + d.toLocaleTimeString('en-IN', { hour:'2-digit', minute:'2-digit' });
}

function timeAgo(iso) {
  const diff = Date.now() - new Date(iso).getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff/60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff/3600000)}h ago`;
  return `${Math.floor(diff/86400000)}d ago`;
}

function organIcon(type) {
  return { KIDNEY:'🫘', LIVER:'🫀', HEART:'❤️', LUNG:'🫁', PANCREAS:'🧬', CORNEA:'👁️', SMALL_INTESTINE:'🌀' }[type] || '🫀';
}

function getDonorStatusBadge(s) {
  return `<span class="badge ${{ AVAILABLE:'badge-teal', ASSIGNED:'badge-amber', EXPIRED:'badge-red' }[s]||'badge-gray'}">${s}</span>`;
}
function getRecipientStatusBadge(s) {
  return `<span class="badge ${{ WAITING:'badge-blue', MATCHED:'badge-amber', TRANSPLANTED:'badge-green', REMOVED:'badge-red' }[s]||'badge-gray'}">${s}</span>`;
}
function getMatchBadge(s) {
  return `<span class="badge ${{ PENDING_CONFIRMATION:'badge-amber', CONFIRMED:'badge-green', REJECTED:'badge-red' }[s]||'badge-gray'}">${s.replace('_',' ')}</span>`;
}
function getTransportStatusBadge(s) {
  return `<span class="badge ${{ DISPATCHED:'badge-blue', IN_TRANSIT:'badge-amber', DELIVERED:'badge-green', FAILED:'badge-red' }[s]||'badge-gray'}">${s.replace('_',' ')}</span>`;
}
function setTableLoading(tbodyId, cols) {
  document.getElementById(tbodyId).innerHTML =
    `<tr><td colspan="${cols}"><div class="loading-row"><span class="spinner"></span> Loading…</div></td></tr>`;
}
