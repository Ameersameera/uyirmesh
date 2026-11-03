/* UyirMesh base dashboard. Plain JavaScript, no internet or libraries needed. */
"use strict";

const COLORS = { Critical: "#d32f2f", High: "#ef6c00", Medium: "#ffa000", Low: "#2e7d32" };
const KIND = { TRAPPED: "Trapped", MEDICAL: "Medical", FIRE: "Fire", FLOOD: "Flood", NEED: "Needs supplies" };
const STATUS = { NEW: "New", ASSIGNED: "Team assigned", ON_THE_WAY: "On the way", RESCUED: "Rescued", CANCELLED: "Cancelled", UNABLE: "Team unable" };
const TABS = [["cases", "Cases"], ["map", "Map"], ["teams", "Teams"], ["alerts", "Alerts"], ["network", "Network"],
  ["charts", "Charts"], ["replay", "Replay"], ["shelters", "Shelters"], ["people", "People"], ["log", "Log"]];

let S = null;            // latest state from the phone
let tab = "cases";
let selected = null;     // selected case id
let replayT = null;      // replay time (ms) or null
let mapZoom = 1;
let polling = null;
let chatTeam = "";

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmtTime = (t) => (t ? new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "-");
const fmtDateTime = (t) => (t ? new Date(t).toLocaleString([], { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) : "-");
function ago(t) {
  if (!t) return "-";
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 60) return s + "s ago";
  if (s < 3600) return Math.round(s / 60) + " min ago";
  if (s < 86400) return Math.round(s / 3600) + " h ago";
  return Math.round(s / 86400) + " d ago";
}
function dist(lat1, lon1, lat2, lon2) {
  const R = 6371008.8, r = Math.PI / 180;
  const dp = (lat2 - lat1) * r, dl = (lon2 - lon1) * r;
  const h = Math.sin(dp / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(dl / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}
const fmtDist = (m) => (m < 1000 ? Math.round(m) + " m" : (m / 1000).toFixed(m < 10000 ? 1 : 0) + " km");

async function api(path, body) {
  const opts = body ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), credentials: "same-origin" } : { credentials: "same-origin" };
  const r = await fetch(path, opts);
  if (r.status === 401) { showLogin(); throw new Error("login"); }
  return r.json();
}

/* ---------------- login ---------------- */

function showLogin() {
  if (polling) clearInterval(polling);
  polling = null;
  $("app").classList.add("hidden");
  $("login").classList.remove("hidden");
  $("password").focus();
}

$("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("loginError").textContent = "";
  try {
    const r = await fetch("/api/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password: $("password").value }), credentials: "same-origin" });
    const j = await r.json();
    if (!r.ok) { $("loginError").textContent = j.error || "Login failed"; return; }
    $("password").value = "";
    start();
  } catch (err) { $("loginError").textContent = "Cannot reach the base phone"; }
});

$("logoutBtn").addEventListener("click", async () => { try { await api("/api/logout"); } catch (e) {} showLogin(); });
$("printBtn").addEventListener("click", printReport);
$("autoAssign").addEventListener("change", (e) => api("/api/autoassign", { on: e.target.checked }).then(refresh));

function start() {
  $("login").classList.add("hidden");
  $("app").classList.remove("hidden");
  buildTabs();
  refresh();
  if (polling) clearInterval(polling);
  polling = setInterval(refresh, 3000);
}

async function refresh() {
  try {
    S = await api("/api/state");
    render();
    $("updated").textContent = "Updated " + fmtTime(S.now) + " (refreshes every 3 s)";
  } catch (e) {
    if (e.message !== "login") $("updated").textContent = "Connection lost - retrying… (is the base phone's hotspot on?)";
  }
}

function buildTabs() {
  $("tabs").innerHTML = TABS.map(([id, name]) => `<button data-tab="${id}" class="${id === tab ? "active" : ""}">${name}</button>`).join("");
  $("tabs").querySelectorAll("button").forEach((b) => b.addEventListener("click", () => { tab = b.dataset.tab; buildTabs(); render(); }));
}

/* ---------------- render ---------------- */

const openCases = () => S.cases.filter((c) => ["NEW", "ASSIGNED", "ON_THE_WAY", "UNABLE"].includes(c.status));

function render() {
  if (!S) return;
  $("baseName").textContent = S.base.name;
  $("drillBadge").classList.toggle("hidden", !S.base.drill);
  $("fingerprint").textContent = "Base key " + S.base.fingerprint + " · UyirMesh " + S.base.version;
  $("autoAssign").checked = !!S.base.autoAssign;
  const open = openCases();
  const crit = open.filter((c) => c.label === "Critical").length;
  $("headStats").innerHTML = [
    ["Open SOS", open.length], ["Critical", crit], ["Rescued", S.cases.filter((c) => c.status === "RESCUED").length],
    ["Phones nearby", S.mesh.peers.length], ["Teams", S.teams.length],
  ].map(([l, v]) => `<span class="stat">${l} <b>${v}</b></span>`).join("");
  const keepScroll = window.scrollY;
  const main = $("main");
  const active = document.activeElement;
  // Do not redraw while the user is typing in a form on the page.
  if (active && main.contains(active) && (active.tagName === "INPUT" || active.tagName === "TEXTAREA" || active.tagName === "SELECT")) {
    if (tab === "cases" || tab === "map") drawMapIfAny();
    return;
  }
  const fn = { cases: renderCases, map: renderMap, teams: renderTeams, alerts: renderAlerts, network: renderNetwork, charts: renderCharts, replay: renderReplay, shelters: renderShelters, people: renderPeople, log: renderLog }[tab];
  main.innerHTML = fn();
  bindAfterRender();
  window.scrollTo(0, keepScroll);
}

function prioBadge(c) { return `<span class="badge ${c.label}">${c.label} ${c.score}</span>`; }
function statusBadge(s) { return `<span class="badge blue">${STATUS[s] || s}</span>`; }

function renderCases() {
  const list = S.cases.slice().sort((a, b) => {
    const ao = ["NEW", "ASSIGNED", "ON_THE_WAY", "UNABLE"].includes(a.status) ? 1 : 0;
    const bo = ["NEW", "ASSIGNED", "ON_THE_WAY", "UNABLE"].includes(b.status) ? 1 : 0;
    return bo - ao || b.score - a.score;
  });
  const rows = list.map((c) => `<tr class="row ${c.id === selected ? "sel" : ""}" data-case="${c.id}">
      <td>${prioBadge(c)}</td><td>${statusBadge(c.status)}${c.drill ? ' <span class="badge grey">drill</span>' : ""}${c.duplicateOf ? ' <span class="badge purple">dup?</span>' : ""}</td>
      <td><b>${esc(KIND[c.kind] || c.kind)}</b> · ${c.people} ppl${c.injured ? " · injured" : ""}<div class="muted">${esc(c.name)}</div></td>
      <td>${esc(c.team) || "-"}${c.eta > 0 ? " · " + c.eta + " min" : ""}</td><td>${c.hops}</td><td>${ago(c.firstAt)}</td></tr>`).join("");
  return `<div class="grid two">
    <div class="card"><h2>SOS cases (${list.length}) - most urgent first</h2>
      ${list.length ? `<table><tr><th>Priority</th><th>Status</th><th>Situation</th><th>Team</th><th>Hops</th><th>Received</th></tr>${rows}</table>` : '<div class="empty">No SOS yet. When a phone sends an SOS it appears here within seconds.</div>'}
    </div>
    <div class="card" id="detail">${renderDetail()}</div>
  </div>`;
}

function renderDetail() {
  const c = S.cases.find((x) => x.id === selected);
  if (!c) return '<div class="empty">Select a case to see details, AI reasons, voice notes and actions.</div>';
  const maxP = Math.max(1, ...c.reasons.map((r) => r.points));
  const reasons = c.reasons.map((r) => `<div class="reason"><span class="lbl">${esc(r.label)}</span><div class="bar" style="width:${Math.round(160 * r.points / maxP)}px"></div><b>+${r.points}</b></div>`).join("");
  const proba = c.proba && c.proba.length === 4 ? `<div class="muted">P(Low / Medium / High / Critical) = ${c.proba.map((p) => p.toFixed(2)).join(" / ")}</div>` : "";
  const media = c.media.map((m) => m.mime.startsWith("audio")
    ? `<div>🎙 Voice note (${Math.round(m.durationMs / 1000)} s, ${fmtTime(m.at)})<audio controls preload="none" src="/api/media.wav?f=${encodeURIComponent(m.file)}"></audio><a class="muted" href="/api/media?f=${encodeURIComponent(m.file)}">download .amr</a></div>`
    : `<div>📷 Photo (${fmtTime(m.at)})<img class="media" src="/api/media?f=${encodeURIComponent(m.file)}"></div>`).join("");
  const teams = S.teams.map((t) => `<button class="small ${c.team === t ? "green" : "blue"}" data-assign="${esc(t)}">${esc(t)}</button>`).join(" ") || '<span class="muted">No teams yet - add a rescuer on the base phone.</span>';
  const path = ["📱 victim " + c.victim].concat(c.path.map((p) => "↪ " + p.substring(0, 6))).concat(["🏠 base"]).join("  ");
  const events = c.events.slice().reverse().map((e) => `<div><span class="t">${fmtTime(e.at)}</span>${esc(e.text)}</div>`).join("");
  return `<h2>${prioBadge(c)} ${statusBadge(c.status)} Case ${c.id.substring(0, 6)}</h2>
    <div>${esc(c.summary)}</div>
    <h3>Why this priority (AI)</h3><div class="muted">Random Forest score ${c.modelScore} + waiting time + repeats</div>${reasons}${proba}
    <h3>Victim</h3><div class="kv">
      <div>Name</div><div>${esc(c.name) || "-"}</div><div>Phone</div><div>${esc(c.phone) || "-"}</div>
      <div>Blood</div><div>${esc(c.blood) || "-"}</div><div>Medical</div><div style="color:#d32f2f">${esc(c.medical) || "-"}</div>
      <div>Battery</div><div>${c.battery >= 0 ? c.battery + "%" : "?"}${c.lowBattery ? " (last position)" : ""}</div>
      <div>Location</div><div>${c.lat != null ? c.lat.toFixed(5) + ", " + c.lon.toFixed(5) + " ±" + Math.round(c.accuracy || 0) + " m" : "no GPS yet"}</div>
      <div>Sent by</div><div>${esc(c.trigger.toLowerCase())} · ${c.repeats + 1} time(s)</div>
      <div>Packet path</div><div>${esc(path)} (${c.hops} hops)</div>
      ${c.note ? `<div>Note</div><div>"${esc(c.note)}"</div>` : ""}
    </div>
    ${media ? "<h3>Voice notes and photos</h3>" + media : ""}
    <h3>Assign team</h3><div class="row-actions">${teams}</div>
    <h3>Status</h3><div class="row-actions">
      <button class="small orange" data-status="ON_THE_WAY">On the way</button>
      <button class="small green" data-status="RESCUED">Rescued</button>
      <button class="small grey" data-status="NEW">Back to new</button></div>
    <h3>Message to victim (signed)</h3><div class="row-actions"><input id="msgText" placeholder="e.g. Boat coming from the north side"><button class="small" id="msgSend">Send</button></div>
    <h3>Timeline</h3><div class="timeline">${events}</div>`;
}

/* ---------------- map ---------------- */

function renderMap() {
  const hs = S.hotspots.map((h) => `<div>🔥 ${h.count} SOS within ${fmtDist(h.radius)}</div>`).join("") || '<span class="muted">No hotspots (3+ SOS within 250 m).</span>';
  return `<div class="grid two"><div class="card"><h2>Offline radar map ${replayT ? "" : "(live)"}</h2>
      <canvas id="map" width="900" height="900"></canvas>
      <div class="legend"><span style="--c:#d32f2f">Critical</span><span style="--c:#ef6c00">High</span><span style="--c:#ffa000">Medium</span><span style="--c:#2e7d32">Low</span><span style="--c:#1565c0">Rescue team</span><span style="--c:#00897b">Shelter</span><span style="--c:#7b1fa2">Base</span></div>
      <div class="row-actions"><button class="small ghost" id="zoomIn">Zoom in</button><button class="small ghost" id="zoomOut">Zoom out</button><label class="toggle"><input type="checkbox" id="heat" checked> Heat map</label></div>
    </div><div class="card" id="detail"><h3>Hotspots (DBSCAN)</h3>${hs}<hr>${renderDetail()}</div></div>`;
}

let mapPoints = [];
function drawMap(canvas, opts) {
  const ctx = canvas.getContext("2d");
  const W = canvas.width, H = canvas.height;
  const t = opts.time;
  const cases = S.cases.filter((c) => c.lat != null && (t == null ? true : c.firstAt <= t));
  const visibleCases = cases.filter((c) => {
    if (t == null) return ["NEW", "ASSIGNED", "ON_THE_WAY", "UNABLE"].includes(c.status);
    return !(c.rescuedAt && c.rescuedAt <= t) && !(c.cancelledAt && c.cancelledAt <= t);
  });
  const rescuers = t == null ? S.rescuers.filter((r) => r.lat != null && Date.now() - r.lastSeen < 30 * 60000) : [];
  const shelters = S.shelters.filter((s) => s.lat != null);
  const zones = S.alerts.filter((a) => a.lat != null && (a.kind === "DANGER" || a.kind === "EVACUATE") && (!a.validUntil || a.validUntil > Date.now()));
  let center = S.base.lat != null ? [S.base.lat, S.base.lon] : null;
  const pts = visibleCases.map((c) => [c.lat, c.lon]).concat(rescuers.map((r) => [r.lat, r.lon]), shelters.map((s) => [s.lat, s.lon]));
  if (!center && pts.length) center = [pts.reduce((a, p) => a + p[0], 0) / pts.length, pts.reduce((a, p) => a + p[1], 0) / pts.length];
  ctx.clearRect(0, 0, W, H);
  ctx.fillStyle = "#f3edec"; ctx.beginPath(); ctx.arc(W / 2, H / 2, W * 0.48, 0, 2 * Math.PI); ctx.fill();
  mapPoints = [];
  if (!center) { ctx.fillStyle = "#6b6261"; ctx.font = "20px sans-serif"; ctx.fillText("Waiting for GPS positions…", W / 2 - 130, H / 2); return; }
  const far = Math.max(150, ...pts.map((p) => dist(center[0], center[1], p[0], p[1])));
  const steps = [100, 200, 300, 500, 1000, 2000, 3000, 5000, 10000, 20000, 50000, 100000];
  const range = (steps.find((s) => s >= far * 1.15) || far * 1.15) / mapZoom;
  const R = W * 0.46;
  const proj = (lat, lon) => {
    const x = (lon - center[1]) * Math.PI / 180 * 6371008.8 * Math.cos((lat + center[0]) / 2 * Math.PI / 180);
    const y = (lat - center[0]) * Math.PI / 180 * 6371008.8;
    return [W / 2 + x / range * R, H / 2 - y / range * R];
  };
  ctx.strokeStyle = "rgba(0,0,0,.15)"; ctx.setLineDash([8, 8]); ctx.fillStyle = "#6b6261"; ctx.font = "16px sans-serif";
  for (let k = 1; k <= 4; k++) {
    ctx.beginPath(); ctx.arc(W / 2, H / 2, R * k / 4, 0, 2 * Math.PI); ctx.stroke();
    ctx.fillText(fmtDist(range * k / 4), W / 2 + 6, H / 2 - R * k / 4 + 18);
  }
  ctx.setLineDash([]);
  ctx.fillStyle = "#d32f2f"; ctx.font = "bold 20px sans-serif"; ctx.fillText("N", W / 2 - 6, H / 2 - R - 8);
  if (opts.heat) {
    for (const c of visibleCases) {
      const [x, y] = proj(c.lat, c.lon);
      const g = ctx.createRadialGradient(x, y, 0, x, y, 70);
      g.addColorStop(0, "rgba(255,23,68,.35)"); g.addColorStop(1, "rgba(255,23,68,0)");
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, 70, 0, 2 * Math.PI); ctx.fill();
    }
  }
  for (const z of zones) {
    const [x, y] = proj(z.lat, z.lon); const r = Math.max(10, (z.radius || 100) / range * R);
    ctx.fillStyle = "rgba(211,47,47,.12)"; ctx.strokeStyle = "#d32f2f"; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.arc(x, y, r, 0, 2 * Math.PI); ctx.fill(); ctx.stroke();
  }
  if (t == null) for (const h of S.hotspots) {
    const [x, y] = proj(h.lat, h.lon); const r = Math.max(16, (h.radius + 40) / range * R);
    ctx.strokeStyle = "#ef6c00"; ctx.setLineDash([6, 4]); ctx.lineWidth = 3; ctx.beginPath(); ctx.arc(x, y, r, 0, 2 * Math.PI); ctx.stroke(); ctx.setLineDash([]);
  }
  for (const s of shelters) {
    const [x, y] = proj(s.lat, s.lon);
    ctx.fillStyle = "#00897b"; ctx.beginPath(); ctx.arc(x, y, 11, 0, 2 * Math.PI); ctx.fill();
    ctx.fillStyle = "#fff"; ctx.beginPath(); ctx.arc(x, y, 5, 0, 2 * Math.PI); ctx.fill();
    ctx.fillStyle = "#00695c"; ctx.font = "14px sans-serif"; ctx.fillText(s.name + " " + s.occupancy + "/" + s.capacity, x + 14, y + 5);
  }
  for (const r of rescuers) {
    const [x, y] = proj(r.lat, r.lon);
    ctx.fillStyle = "#1565c0"; ctx.fillRect(x - 9, y - 9, 18, 18); ctx.strokeStyle = "#fff"; ctx.lineWidth = 2; ctx.strokeRect(x - 9, y - 9, 18, 18);
    ctx.fillStyle = "#0d47a1"; ctx.font = "13px sans-serif"; ctx.fillText(r.team, x + 12, y + 4);
  }
  for (const c of visibleCases) {
    const [x, y] = proj(c.lat, c.lon);
    const sel = c.id === selected;
    if (c.track && c.track.length > 1) {
      ctx.strokeStyle = "rgba(21,101,192,.6)"; ctx.lineWidth = 2; ctx.beginPath();
      c.track.forEach((p, i) => { const [tx, ty] = proj(p[0], p[1]); if (i) ctx.lineTo(tx, ty); else ctx.moveTo(tx, ty); }); ctx.stroke();
    }
    ctx.fillStyle = COLORS[c.label] || "#757575"; ctx.beginPath(); ctx.arc(x, y, sel ? 14 : 10, 0, 2 * Math.PI); ctx.fill();
    ctx.strokeStyle = "#fff"; ctx.lineWidth = 3; ctx.stroke();
    ctx.fillStyle = "#222"; ctx.font = "13px sans-serif"; ctx.fillText(String(c.score), x + 13, y + 4);
    mapPoints.push({ x, y, id: c.id });
  }
  if (S.base.lat != null) {
    const [x, y] = proj(S.base.lat, S.base.lon);
    ctx.fillStyle = "#7b1fa2"; ctx.beginPath(); ctx.arc(x, y, 13, 0, 2 * Math.PI); ctx.fill();
    ctx.fillStyle = "#fff"; ctx.font = "bold 13px sans-serif"; ctx.fillText("B", x - 5, y + 5);
  }
}

function drawMapIfAny() {
  const c = $("map");
  if (c) drawMap(c, { time: tab === "replay" ? replayT : null, heat: $("heat") ? $("heat").checked : true });
}

/* ---------------- teams ---------------- */

function renderTeams() {
  const groups = {};
  S.rescuers.forEach((r) => { (groups[r.team] = groups[r.team] || []).push(r); });
  const html = Object.keys(groups).sort().map((t) => {
    const active = S.cases.filter((c) => c.team === t && (c.status === "ASSIGNED" || c.status === "ON_THE_WAY")).length;
    const rows = groups[t].map((r) => `<tr><td>${esc(r.name)}</td><td>${r.lastSeen ? ago(r.lastSeen) : "not seen yet"}</td><td>${r.battery >= 0 ? r.battery + "%" : "-"}</td><td>${r.lat != null ? r.lat.toFixed(4) + ", " + r.lon.toFixed(4) : "-"}</td><td>${fmtDateTime(r.expiresAt)}</td></tr>`).join("");
    return `<h3>Team ${esc(t)} · ${active} active task(s)</h3><table><tr><th>Rescuer</th><th>Last seen</th><th>Battery</th><th>Position</th><th>ID valid until</th></tr>${rows}</table>`;
  }).join("") || '<div class="empty">No rescuers yet. Add rescuers on the base phone (Teams > Add rescuer).</div>';
  if (!chatTeam && S.teams.length) chatTeam = S.teams[0];
  const chat = S.teamChat.map((m) => `<div class="m"><b>${esc(m.name)}</b> <span class="muted">${fmtTime(m.at)}</span><br>${esc(m.text)}</div>`).join("") || '<div class="muted">No team messages yet.</div>';
  return `<div class="grid two"><div class="card"><h2>Rescue teams</h2>${html}
      <p class="muted">Auto-assign sends the most urgent waiting case to the nearest team with the fewest open tasks (max 3 each). ETA uses ${S.base.speedKmh} km/h.</p></div>
    <div class="card"><h2>Encrypted team chat</h2><div class="chat">${chat}</div>
      <div class="row-actions"><select id="chatTeam">${S.teams.map((t) => `<option ${t === chatTeam ? "selected" : ""}>${esc(t)}</option>`).join("")}</select>
      <input id="chatText" placeholder="Message to the team"><button class="small blue" id="chatSend">Send</button></div></div></div>`;
}

/* ---------------- alerts ---------------- */

function renderAlerts() {
  const list = S.alerts.slice().sort((a, b) => b.at - a.at).map((a) => `<tr><td><span class="badge ${a.severity >= 3 ? "Critical" : "High"}">${esc(a.kind)}</span></td><td><b>${esc(a.title)}</b><div class="muted">${esc(a.message)}</div></td><td>${a.radius ? fmtDist(a.radius) : "-"}</td><td>${fmtDateTime(a.at)}</td><td>${a.mine ? "this base" : "received"}</td></tr>`).join("");
  return `<div class="grid two"><div class="card"><h2>Send a signed alert</h2>
      <p class="muted">Signed with the base key (ECDSA). Every phone checks the signature, so fake alerts are rejected.</p>
      <label>Type</label><select id="aKind"><option value="EVACUATE">Evacuate</option><option value="DANGER">Danger zone</option><option value="INFO">Information</option><option value="MISSING">Missing person</option><option value="ALL_CLEAR">All clear</option></select>
      <label>Title</label><input id="aTitle" maxlength="80" placeholder="e.g. Leave the river bank now">
      <label>Message</label><textarea id="aMsg" maxlength="500"></textarea>
      <label>Severity</label><select id="aSev"><option value="3">3 - critical (siren on phones)</option><option value="2">2</option><option value="1">1 - information</option></select>
      <label class="toggle" style="margin-top:10px"><input type="checkbox" id="aLoc" checked> Centre on the base location</label>
      <label>Radius (metres)</label><input id="aRadius" type="number" value="500" min="50" max="20000">
      <label>Valid for (minutes, 0 = always)</label><input id="aValid" type="number" value="360" min="0">
      <div class="row-actions" style="margin-top:10px"><button class="orange" id="aSend">Sign and send</button><span id="aResult" class="muted"></span></div></div>
    <div class="card"><h2>Alerts</h2>${list ? `<table><tr><th>Type</th><th>Alert</th><th>Radius</th><th>Time</th><th>From</th></tr>${list}</table>` : '<div class="empty">No alerts yet.</div>'}</div></div>`;
}

/* ---------------- network ---------------- */

function renderNetwork() {
  const m = S.mesh.metrics || {};
  const metrics = Object.keys(m).map((k) => `<div>${esc(k)}</div><div><b>${m[k]}</b></div>`).join("");
  const peers = S.mesh.peers.map((p) => `<div>${esc(p.device)} · ${p.role === "B" ? "base" : p.role === "R" ? "rescuer" : "public"} · connected ${ago(p.since)}</div>`).join("") || '<span class="muted">No phone in direct range.</span>';
  const paths = S.cases.slice().sort((a, b) => b.firstAt - a.firstAt).slice(0, 12).map((c) => `<tr><td>${c.id.substring(0, 6)}</td><td>📱 ${esc(c.victim)} ${c.path.map((p) => "→ " + esc(p.substring(0, 6))).join(" ")} → 🏠</td><td>${c.hops}</td></tr>`).join("");
  return `<div class="grid two"><div class="card"><h2>Mesh topology</h2><canvas id="topo" width="900" height="650"></canvas>
      <div class="legend"><span style="--c:#d32f2f">Base</span><span style="--c:#1565c0">Rescuer</span><span style="--c:#2e7d32">Public phone</span></div>
      <p class="muted">Each phone reports its radio neighbours to the base once a minute (encrypted heartbeat).</p></div>
    <div class="card"><h2>This base phone</h2><div class="kv"><div>Mesh</div><div>${S.mesh.running ? "running" : "stopped"}</div><div>Direct neighbours</div><div>${S.mesh.peers.length}</div><div>Packets stored</div><div>${S.mesh.buffer} (${S.mesh.waiting} waiting)</div>${metrics}</div>
      <h3>Direct neighbours</h3>${peers}
      <h3>Packet paths (path trace)</h3>${paths ? `<table><tr><th>Case</th><th>Route</th><th>Hops</th></tr>${paths}</table>` : '<span class="muted">No SOS yet.</span>'}</div></div>`;
}

let topoPos = {};
function drawTopology(canvas) {
  const ctx = canvas.getContext("2d");
  const W = canvas.width, H = canvas.height;
  const nodes = S.nodes;
  const ids = nodes.map((n) => n.id);
  const role = {}; nodes.forEach((n) => { role[n.id] = n.role; });
  const edges = [];
  nodes.forEach((n) => n.neighbours.forEach((b) => { if (ids.includes(b) && n.id < b) edges.push([n.id, b]); else if (!ids.includes(b)) { ids.push(b); role[b] = "PUBLIC"; edges.push([n.id, b]); } }));
  ids.forEach((id, i) => { if (!topoPos[id]) topoPos[id] = { x: W / 2 + Math.cos(i * 2.4) * 200, y: H / 2 + Math.sin(i * 2.4) * 160 }; });
  for (let it = 0; it < 150; it++) {
    const f = {}; ids.forEach((id) => { f[id] = { x: 0, y: 0 }; });
    for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) {
      const a = topoPos[ids[i]], b = topoPos[ids[j]];
      let dx = a.x - b.x, dy = a.y - b.y; const d2 = Math.max(100, dx * dx + dy * dy); const k = 30000 / d2;
      const d = Math.sqrt(d2); dx /= d; dy /= d;
      f[ids[i]].x += dx * k; f[ids[i]].y += dy * k; f[ids[j]].x -= dx * k; f[ids[j]].y -= dy * k;
    }
    edges.forEach(([u, v]) => {
      const a = topoPos[u], b = topoPos[v]; const dx = b.x - a.x, dy = b.y - a.y; const d = Math.sqrt(dx * dx + dy * dy) || 1; const k = (d - 140) * 0.02;
      f[u].x += dx / d * k * d * 0.05; f[u].y += dy / d * k * d * 0.05; f[v].x -= dx / d * k * d * 0.05; f[v].y -= dy / d * k * d * 0.05;
    });
    ids.forEach((id) => {
      const p = topoPos[id]; p.x += Math.max(-10, Math.min(10, f[id].x + (W / 2 - p.x) * 0.01)); p.y += Math.max(-10, Math.min(10, f[id].y + (H / 2 - p.y) * 0.01));
      p.x = Math.max(40, Math.min(W - 40, p.x)); p.y = Math.max(40, Math.min(H - 40, p.y));
    });
  }
  ctx.clearRect(0, 0, W, H);
  ctx.strokeStyle = "#9e9e9e"; ctx.lineWidth = 3;
  edges.forEach(([u, v]) => { ctx.beginPath(); ctx.moveTo(topoPos[u].x, topoPos[u].y); ctx.lineTo(topoPos[v].x, topoPos[v].y); ctx.stroke(); });
  ids.forEach((id) => {
    const p = topoPos[id]; const r = role[id];
    ctx.fillStyle = r === "BASE" ? "#d32f2f" : r === "RESCUER" ? "#1565c0" : "#2e7d32";
    ctx.beginPath(); ctx.arc(p.x, p.y, 18, 0, 2 * Math.PI); ctx.fill(); ctx.strokeStyle = "#fff"; ctx.lineWidth = 3; ctx.stroke();
    const n = nodes.find((x) => x.id === id);
    ctx.fillStyle = "#222"; ctx.font = "14px sans-serif";
    ctx.fillText((n && n.name ? n.name : id.substring(0, 6)) + (n && n.battery >= 0 ? " " + n.battery + "%" : ""), p.x + 22, p.y + 5);
  });
  if (!ids.length) { ctx.fillStyle = "#6b6261"; ctx.font = "18px sans-serif"; ctx.fillText("Waiting for heartbeats from phones…", 40, H / 2); }
}

/* ---------------- charts ---------------- */

function renderCharts() {
  const c = S.cases;
  const rescued = c.filter((x) => x.rescuedAt > 0);
  const avgRescue = rescued.length ? rescued.reduce((a, x) => a + (x.rescuedAt - x.firstAt), 0) / rescued.length / 60000 : 0;
  const assigned = c.filter((x) => x.assignedAt > 0);
  const avgAssign = assigned.length ? assigned.reduce((a, x) => a + (x.assignedAt - x.firstAt), 0) / assigned.length / 60000 : 0;
  const people = c.reduce((a, x) => a + x.people, 0);
  const kpis = [["SOS cases", c.length], ["People reported", people], ["Rescued", rescued.length], ["Avg time to team (min)", avgAssign.toFixed(1)], ["Avg time to rescue (min)", avgRescue.toFixed(1)], ["Voice notes / photos", c.reduce((a, x) => a + x.media.length, 0)]];
  return `<div class="kpis">${kpis.map(([l, v]) => `<div class="kpi"><div class="v">${v}</div><div class="l">${l}</div></div>`).join("")}</div>
    <div class="grid three"><div class="card"><h2>By AI priority</h2><canvas id="chPrio" width="500" height="320"></canvas></div>
      <div class="card"><h2>By emergency type</h2><canvas id="chKind" width="500" height="320"></canvas></div>
      <div class="card"><h2>By status</h2><canvas id="chStatus" width="500" height="320"></canvas></div></div>
    <div class="card" style="margin-top:16px"><h2>SOS received over time</h2><canvas id="chTime" width="1400" height="300"></canvas></div>`;
}

function barChart(canvas, labels, values, colors) {
  const ctx = canvas.getContext("2d"); const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);
  const max = Math.max(1, ...values); const bw = (W - 40) / labels.length;
  ctx.font = "15px sans-serif"; ctx.textAlign = "center";
  labels.forEach((l, i) => {
    const h = (H - 70) * values[i] / max; const x = 20 + i * bw + bw * 0.15, y = H - 40 - h;
    ctx.fillStyle = colors[i % colors.length]; ctx.fillRect(x, y, bw * 0.7, h);
    ctx.fillStyle = "#222"; ctx.fillText(String(values[i]), x + bw * 0.35, y - 6);
    ctx.fillStyle = "#6b6261"; ctx.fillText(l, x + bw * 0.35, H - 16);
  });
  ctx.textAlign = "start";
}

function timeChart(canvas) {
  const ctx = canvas.getContext("2d"); const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);
  if (!S.cases.length) { ctx.fillStyle = "#6b6261"; ctx.font = "18px sans-serif"; ctx.fillText("No SOS yet", 20, H / 2); return; }
  const t0 = Math.min(...S.cases.map((c) => c.firstAt)), t1 = Math.max(S.now, t0 + 60000);
  const span = t1 - t0; const bucket = span <= 3600000 ? 5 * 60000 : span <= 6 * 3600000 ? 15 * 60000 : 3600000;
  const n = Math.ceil(span / bucket) + 1; const counts = new Array(n).fill(0);
  S.cases.forEach((c) => { counts[Math.floor((c.firstAt - t0) / bucket)]++; });
  const max = Math.max(1, ...counts); const bw = (W - 60) / n;
  ctx.fillStyle = "#d32f2f";
  counts.forEach((v, i) => { const h = (H - 50) * v / max; ctx.fillRect(40 + i * bw, H - 30 - h, Math.max(2, bw - 3), h); });
  ctx.fillStyle = "#6b6261"; ctx.font = "13px sans-serif";
  ctx.fillText(fmtDateTime(t0), 40, H - 8); ctx.fillText(fmtDateTime(t1), W - 150, H - 8); ctx.fillText("max " + max + " per " + Math.round(bucket / 60000) + " min", 44, 16);
}

/* ---------------- replay ---------------- */

function renderReplay() {
  if (!S.cases.length) return '<div class="card"><div class="empty">Replay needs at least one SOS case.</div></div>';
  const t0 = Math.min(...S.cases.map((c) => c.firstAt)) - 60000, t1 = S.now;
  if (replayT == null || replayT < t0 || replayT > t1) replayT = t1;
  const at = replayT;
  const seen = S.cases.filter((c) => c.firstAt <= at);
  const resc = seen.filter((c) => c.rescuedAt && c.rescuedAt <= at).length;
  const assigned = seen.filter((c) => c.assignedAt && c.assignedAt <= at && !(c.rescuedAt && c.rescuedAt <= at)).length;
  const events = S.cases.flatMap((c) => c.events.map((e) => ({ at: e.at, text: c.id.substring(0, 6) + " · " + e.text }))).filter((e) => e.at <= at).sort((a, b) => b.at - a.at).slice(0, 25);
  return `<div class="grid two"><div class="card"><h2>Incident replay - ${fmtDateTime(at)}</h2>
      <input type="range" id="replay" min="${t0}" max="${t1}" step="1000" value="${at}">
      <div class="row-actions"><button class="small ghost" id="playBtn">▶ Play</button><button class="small ghost" id="liveBtn">Jump to now</button></div>
      <canvas id="map" width="900" height="900"></canvas></div>
    <div class="card"><div class="kpis"><div class="kpi"><div class="v">${seen.length}</div><div class="l">SOS so far</div></div><div class="kpi"><div class="v">${assigned}</div><div class="l">With a team</div></div><div class="kpi"><div class="v">${resc}</div><div class="l">Rescued</div></div></div>
      <h3>What happened (latest first)</h3><div class="timeline">${events.map((e) => `<div><span class="t">${fmtTime(e.at)}</span>${esc(e.text)}</div>`).join("")}</div></div></div>`;
}

let playTimer = null;

/* ---------------- shelters ---------------- */

function renderShelters() {
  const list = S.shelters.map((s) => {
    const pct = s.capacity ? Math.min(100, Math.round(100 * s.occupancy / s.capacity)) : 0;
    return `<tr><td><b>${esc(s.name)}</b></td><td>${s.occupancy}/${s.capacity}<div class="occ"><div style="width:${pct}%;background:${pct >= 100 ? "#d32f2f" : "#00897b"}"></div></div></td>
      <td><input type="number" min="0" value="${s.water}" data-sup="water" data-id="${s.id}" style="width:80px"></td>
      <td><input type="number" min="0" value="${s.food}" data-sup="food" data-id="${s.id}" style="width:80px"></td>
      <td><input type="number" min="0" value="${s.medicine}" data-sup="medicine" data-id="${s.id}" style="width:80px"></td>
      <td><button class="small teal" data-save="${s.id}" data-cap="${s.capacity}">Save & announce</button></td></tr>`;
  }).join("");
  const checkins = S.checkins.slice(-30).reverse().map((c) => `<div>${fmtTime(c.at)} · ${esc(c.name)} → ${esc((S.shelters.find((s) => s.id === c.shelter) || {}).name || "")}</div>`).join("");
  return `<div class="grid two"><div class="card"><h2>Shelters and supplies</h2>
      ${list ? `<table><tr><th>Shelter</th><th>People</th><th>Water (x10 L)</th><th>Food (x10)</th><th>Medicine kits</th><th></th></tr>${list}</table>` : '<div class="empty">No shelters yet.</div>'}
      <h3>Add shelter at the base location</h3><div class="row-actions"><input id="shName" placeholder="Shelter name"><input id="shCap" type="number" value="100" style="width:110px"><button class="small teal" id="shAdd">Add</button></div></div>
    <div class="card"><h2>Recent check-ins</h2>${checkins || '<span class="muted">People check in by scanning the shelter QR code (shown on the base phone).</span>'}</div></div>`;
}

/* ---------------- people ---------------- */

function renderPeople() {
  const vol = S.volunteers.map((v) => `<tr><td>${esc(v.name)}</td><td>${esc(v.phone)}</td><td>${esc(v.skills.join(", ").toLowerCase())}</td><td>${esc(v.note)}</td><td>${ago(v.at)}</td></tr>`).join("");
  const miss = S.missing.map((m) => `<tr><td><b>${esc(m.person)}</b>${m.age > 0 ? ", " + m.age : ""}</td><td>${esc(m.description)}</td><td>${esc(m.lastSeen)}</td><td>${esc(m.reporter)} ${esc(m.contact)}</td><td>${m.found ? '<span class="badge Low">found</span>' : '<span class="badge Critical">missing</span>'}</td></tr>`).join("");
  const rep = S.reports.slice().reverse().slice(0, 50).map((r) => `<tr><td>${esc(r.kind)}</td><td>${esc(r.note)}</td><td>${esc(r.name)}</td><td>${ago(r.at)}</td></tr>`).join("");
  return `<div class="grid two"><div class="card"><h2>Find a person</h2><input id="pq" placeholder="Type a name"><div id="pres" style="margin-top:8px"></div>
      <h3>Missing persons</h3>${miss ? `<table><tr><th>Name</th><th>Description</th><th>Last seen</th><th>Contact</th><th></th></tr>${miss}</table>` : '<span class="muted">None</span>'}
      <h3>"I'm safe" messages (${S.safe.length})</h3><div class="timeline">${S.safe.slice(-20).reverse().map((s) => `<div><span class="t">${fmtTime(s.at)}</span>${esc(s.name)}: ${esc(s.message)} ${esc(s.place)}</div>`).join("") || '<span class="muted">None</span>'}</div></div>
    <div class="card"><h2>Volunteers (${S.volunteers.length})</h2>${vol ? `<table><tr><th>Name</th><th>Phone</th><th>Skills</th><th>Note</th><th>When</th></tr>${vol}</table>` : '<span class="muted">None</span>'}
      <h3>Citizen reports</h3>${rep ? `<table><tr><th>Type</th><th>Details</th><th>By</th><th>When</th></tr>${rep}</table>` : '<span class="muted">None</span>'}</div></div>`;
}

function searchPeople(q) {
  q = q.trim().toLowerCase();
  if (q.length < 2) return "";
  const hits = [];
  S.safe.forEach((s) => { if (s.name.toLowerCase().includes(q)) hits.push(`✅ ${esc(s.name)} - safe ${esc(s.place)} (${ago(s.at)})`); });
  S.checkins.forEach((c) => { if (c.name.toLowerCase().includes(q)) hits.push(`🏠 ${esc(c.name)} - in shelter ${esc((S.shelters.find((s) => s.id === c.shelter) || {}).name || "")}`); });
  S.cases.forEach((c) => { if (c.name && c.name.toLowerCase().includes(q)) hits.push(`🆘 ${esc(c.name)} - SOS ${STATUS[c.status] || c.status}`); });
  S.missing.forEach((m) => { if (m.person.toLowerCase().includes(q)) hits.push(`🔎 ${esc(m.person)} - ${m.found ? "found" : "reported missing"}`); });
  return hits.length ? hits.map((h) => `<div>${h}</div>`).join("") : '<span class="muted">No match yet.</span>';
}

/* ---------------- log ---------------- */

function renderLog() {
  return `<div class="card"><h2>Base event log</h2><div class="log">${S.log.slice().reverse().map((e) => `<div>${fmtTime(e.at)} [${esc(e.tag)}] ${esc(e.text)}</div>`).join("")}</div></div>`;
}

/* ---------------- events ---------------- */

function bindAfterRender() {
  document.querySelectorAll("tr.row").forEach((tr) => tr.addEventListener("click", () => { selected = tr.dataset.case; render(); }));
  document.querySelectorAll("[data-assign]").forEach((b) => b.addEventListener("click", () => api("/api/assign", { caseId: selected, team: b.dataset.assign }).then(refresh)));
  document.querySelectorAll("[data-status]").forEach((b) => b.addEventListener("click", () => api("/api/status", { caseId: selected, status: b.dataset.status }).then(refresh)));
  const ms = $("msgSend");
  if (ms) ms.addEventListener("click", () => { const t = $("msgText").value.trim(); if (t) api("/api/message", { caseId: selected, text: t }).then(() => { $("msgText").value = ""; refresh(); }); });
  const map = $("map");
  if (map) {
    drawMapIfAny();
    map.addEventListener("click", (e) => {
      const r = map.getBoundingClientRect(); const x = (e.clientX - r.left) * map.width / r.width, y = (e.clientY - r.top) * map.height / r.height;
      let best = null, bd = 30;
      mapPoints.forEach((p) => { const d = Math.hypot(p.x - x, p.y - y); if (d < bd) { bd = d; best = p.id; } });
      if (best) { selected = best; render(); }
    });
  }
  const zi = $("zoomIn"); if (zi) zi.addEventListener("click", () => { mapZoom = Math.min(64, mapZoom * 2); drawMapIfAny(); });
  const zo = $("zoomOut"); if (zo) zo.addEventListener("click", () => { mapZoom = Math.max(0.25, mapZoom / 2); drawMapIfAny(); });
  const heat = $("heat"); if (heat) heat.addEventListener("change", drawMapIfAny);
  const topo = $("topo"); if (topo) drawTopology(topo);
  if ($("chPrio")) {
    const labels = ["Critical", "High", "Medium", "Low"];
    barChart($("chPrio"), labels, labels.map((l) => S.cases.filter((c) => c.label === l).length), labels.map((l) => COLORS[l]));
    const kinds = Object.keys(KIND);
    barChart($("chKind"), kinds.map((k) => KIND[k].split(" ")[0]), kinds.map((k) => S.cases.filter((c) => c.kind === k).length), ["#6a1b9a", "#d32f2f", "#ef6c00", "#1565c0", "#00897b"]);
    const sts = Object.keys(STATUS);
    barChart($("chStatus"), ["New", "Team", "Way", "Rescued", "Cancel", "Unable"], sts.map((s) => S.cases.filter((c) => c.status === s).length), ["#757575", "#1565c0", "#ef6c00", "#2e7d32", "#9e9e9e", "#6a1b9a"]);
    timeChart($("chTime"));
  }
  const rp = $("replay");
  if (rp) {
    rp.addEventListener("input", () => { replayT = Number(rp.value); render(); });
    $("liveBtn").addEventListener("click", () => { replayT = S.now; render(); });
    $("playBtn").addEventListener("click", () => {
      if (playTimer) { clearInterval(playTimer); playTimer = null; return; }
      const t0 = Number(rp.min), t1 = Number(rp.max); replayT = t0;
      playTimer = setInterval(() => { replayT += (t1 - t0) / 120; if (replayT >= t1) { replayT = t1; clearInterval(playTimer); playTimer = null; } if (tab === "replay") render(); }, 250);
    });
  }
  const as = $("aSend");
  if (as) as.addEventListener("click", () => {
    const body = { kind: $("aKind").value, title: $("aTitle").value.trim(), message: $("aMsg").value.trim(), severity: Number($("aSev").value), useLocation: $("aLoc").checked, radius: Number($("aRadius").value), validMinutes: Number($("aValid").value) };
    if (!body.title) { $("aResult").textContent = "Title is required"; return; }
    api("/api/alert", body).then(() => { $("aResult").textContent = "Signed alert sent ✓"; $("aTitle").value = ""; $("aMsg").value = ""; setTimeout(refresh, 300); });
  });
  const cs = $("chatSend");
  if (cs) cs.addEventListener("click", () => { const t = $("chatText").value.trim(); chatTeam = $("chatTeam").value; if (t && chatTeam) api("/api/teamchat", { team: chatTeam, text: t }).then(() => { $("chatText").value = ""; refresh(); }); });
  const sa = $("shAdd");
  if (sa) sa.addEventListener("click", () => { const n = $("shName").value.trim(); if (n) api("/api/shelter", { name: n, capacity: Number($("shCap").value) || 100 }).then(() => { $("shName").value = ""; refresh(); }); });
  document.querySelectorAll("[data-save]").forEach((b) => b.addEventListener("click", () => {
    const id = b.dataset.save; const v = (k) => Number(document.querySelector(`[data-sup="${k}"][data-id="${id}"]`).value) || 0;
    api("/api/supplies", { id, water: v("water"), food: v("food"), medicine: v("medicine"), capacity: Number(b.dataset.cap) }).then(refresh);
  }));
  const pq = $("pq");
  if (pq) pq.addEventListener("input", () => { $("pres").innerHTML = searchPeople(pq.value); });
}

/* ---------------- print ---------------- */

function printReport() {
  if (!S) return;
  const c = S.cases.slice().sort((a, b) => b.score - a.score);
  const rows = c.map((x) => `<tr><td>${x.id.substring(0, 6)}</td><td>${x.label} ${x.score}</td><td>${STATUS[x.status] || x.status}</td><td>${esc(KIND[x.kind] || x.kind)}</td><td>${x.people}</td><td>${esc(x.name)}</td><td>${esc(x.team)}</td><td>${x.lat != null ? x.lat.toFixed(4) + "," + x.lon.toFixed(4) : "-"}</td><td>${fmtDateTime(x.firstAt)}</td><td>${x.rescuedAt ? fmtDateTime(x.rescuedAt) : ""}</td></tr>`).join("");
  $("printArea").innerHTML = `<h1>UyirMesh incident report - ${esc(S.base.name)}</h1>
    <p>Printed ${new Date().toLocaleString()} · base key ${S.base.fingerprint}</p>
    <p>Total ${c.length} SOS · Open ${openCases().length} · Rescued ${c.filter((x) => x.status === "RESCUED").length} · People reported ${c.reduce((a, x) => a + x.people, 0)} · Teams ${S.teams.join(", ")}</p>
    <table border="1" cellspacing="0" cellpadding="4"><tr><th>Case</th><th>Priority</th><th>Status</th><th>Type</th><th>People</th><th>Name</th><th>Team</th><th>Location</th><th>Received</th><th>Rescued</th></tr>${rows}</table>`;
  window.print();
}

/* ---------------- boot ---------------- */

fetch("/api/state", { credentials: "same-origin" }).then((r) => { if (r.status === 401) showLogin(); else start(); }).catch(showLogin);
