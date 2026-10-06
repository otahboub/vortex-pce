/**
 * Static, illustrative Flow-Aware PCE visualizer.
 * Values and event logs are synthetic display fixtures, not controller telemetry.
 */

// Global State
const state = {
  currentTier: 'tier2', // Default to Kuiper-630 Constellation
  currentArchetype: 'arch3', // arch1 .. arch8
  cosClass: 'high', // high (strict), medium (laxed), low (best-effort)
  laxitySec: 2.0,
  isPlaying: true,
  simSpeed: 1,
  loadMultiplier: 1.0,
  linkFaultPct: 0,
  tcpVariant: 'cubic',
  isCloudPlaybackRunning: false,
  orbitalAngle: 0,
  
  // Telemetry metrics
  metrics: {
    far: 100.0,
    peakTransitBuffer: 15.02,
    sourceHoldingBuffer: 95.24,
    solverLatency: 251,
    penaltyScore: 0.0,
    activeFlows: 0,
    packetDrops: 0,
    rttPenalty: 0.0,
    pcepLatency: 38.2
  },
  
  nodes: [],
  links: [],
  particles: [],
  pcepLogs: []
};

// Tuple mappings for 8 Literature Policy Archetypes
const ARCHETYPE_TUPLES = {
  arch1: { name: 'Arch 1: CSPF Line-Rate Burst', h1: 'FCFS', f1: 'FAST_K (CSPF)', h2: 'Line-Rate (e=C)', f2: 'Static Calendar', desc: 'Unthrottled line-rate burst causes bufferbloat and socket buffer drops.' },
  arch2: { name: 'Arch 2: Multi-Path Fair-Alloc', h1: 'LWF', f1: 'Multi-Tunnel', h2: 'Multi-Path LP (e_LP)', f2: 'WAN TE Alloc', desc: 'Static WAN LP solver experiences collapse under dynamic contact breaks.' },
  arch3: { name: 'Arch 3: Advance Reservation DFE Prototype', h1: 'LWEEF_CoS', f1: 'Caller Path', h2: 'DFE Rate', f2: 'In-Memory Ledger', desc: 'Executable Python prototype with interval-based link reservations.' },
  arch4: { name: 'Arch 4: Time-Gated (TSN)', h1: 'Priority', f1: 'Single-Path', h2: 'Microsecond GCL', f2: 'Switch GCL', desc: 'Taxonomy entry; not implemented by the Python planner.' },
  arch5: { name: 'Arch 5: Storage-Assisted', h1: 'LWEEF', f1: 'Storage Edges', h2: 'Store-and-Forward', f2: 'Node Storage R*', desc: 'Intermediate store-and-forward staging enables traversal across non-overlapping contacts.' },
  arch6: { name: 'Arch 6: Snapshot-Routing', h1: 'FCFS Orbit', f1: 'Snapshots', h2: 'Line-Rate ISL', f2: 'SRv6 ERO Object', desc: 'Discrete snapshot graph updates fail to model continuous rate pacing.' },
  arch7: { name: 'Arch 7: Deterministic Contact', h1: 'Contact Prio', f1: 'Contact Dijkstra', h2: 'Contact Volume', f2: 'NASA CGR Ledgers', desc: 'Single-path Contact Graph Routing leads to high node storage buffer accumulation.' },
  arch8: { name: 'Arch 8: Stochastic Contact', h1: 'Risk Weight', f1: 'Chance Search', h2: 'SLA Chance alpha', f2: 'Stoch Reliability', desc: 'Chance-constrained SLA bounds under fading channel condition.' }
};

// Canvas Initialization
let canvas, ctx;

document.addEventListener('DOMContentLoaded', () => {
  canvas = document.getElementById('topologyCanvas');
  ctx = canvas.getContext('2d');
  
  resizeCanvas();
  window.addEventListener('resize', resizeCanvas);
  
  setupEventListeners();
  loadTopology(state.currentTier);
  
  // Start Animation Loop
  requestAnimationFrame(animLoop);
  
  // Initial Logs
  addLog('DEMO_INIT', 'Static visualizer loaded; no controller or network session is connected.');
  addLog('TOPOLOGY', 'Illustrative Kuiper-scale topology view loaded.');
  addLog('POLICY', 'Archetype entries are visual taxonomy fixtures; only Python arch3 is executable.');
});

function resizeCanvas() {
  const rect = canvas.parentElement.getBoundingClientRect();
  canvas.width = rect.width * window.devicePixelRatio;
  canvas.height = rect.height * window.devicePixelRatio;
  ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
}

// Topology Generators
function loadTopology(tier) {
  state.nodes = [];
  state.links = [];
  state.particles = [];
  
  const w = canvas.width / window.devicePixelRatio;
  const h = canvas.height / window.devicePixelRatio;
  
  if (tier === 'tier1') {
    const nodeCount = 38;
    for (let i = 0; i < nodeCount; i++) {
      const angle = (i / nodeCount) * Math.PI * 2;
      const rx = w * 0.38 * Math.cos(angle) + w * 0.5;
      const ry = h * 0.35 * Math.sin(angle) + h * 0.5;
      state.nodes.push({ id: `ES_${i+1}`, x: rx, y: ry, type: i === 0 || i === 19 ? 'core' : 'router' });
    }
    for (let i = 0; i < nodeCount; i++) {
      const next = (i + 1) % nodeCount;
      const chord = (i + 12) % nodeCount;
      state.links.push({ source: i, target: next, cap: 100, active: true });
      if (i % 3 === 0) state.links.push({ source: i, target: chord, cap: 100, active: true });
    }
  } else if (tier === 'tier2') {
    const planes = 8;
    const satsPerPlane = 8;
    let idCounter = 0;
    for (let p = 0; p < planes; p++) {
      const planeAngle = (p / planes) * Math.PI;
      for (let s = 0; s < satsPerPlane; s++) {
        const satAngle = (s / satsPerPlane) * Math.PI * 2;
        const radius = h * 0.36;
        const x = w * 0.5 + radius * Math.cos(satAngle + state.orbitalAngle) * Math.cos(planeAngle);
        const y = h * 0.5 + radius * Math.sin(satAngle + state.orbitalAngle);
        state.nodes.push({ id: `K630_Sat_${++idCounter}`, x, y, type: 'satellite', plane: p, baseSatAngle: satAngle, basePlaneAngle: planeAngle });
      }
    }
    for (let i = 0; i < state.nodes.length; i++) {
      const nextSat = (i + 1) % state.nodes.length;
      state.links.push({ source: i, target: nextSat, cap: 10, active: true });
      if (i + satsPerPlane < state.nodes.length) {
        state.links.push({ source: i, target: i + satsPerPlane, cap: 10, active: true });
      }
    }
  } else if (tier === 'tier3') {
    const nodeCount = 120;
    for (let i = 0; i < nodeCount; i++) {
      const x = (Math.sin(i * 0.3) * 0.4 + 0.5) * w;
      const y = (Math.cos(i * 0.25) * 0.38 + 0.5) * h;
      state.nodes.push({ id: `SL_${i+1}`, x, y, type: 'satellite' });
    }
    for (let i = 0; i < nodeCount; i++) {
      const target1 = (i + 1) % nodeCount;
      const target2 = (i + 5) % nodeCount;
      state.links.push({ source: i, target: target1, cap: 10, active: true });
      if (i % 2 === 0) state.links.push({ source: i, target: target2, cap: 10, active: true });
    }
  } else if (tier === 'cloud') {
    state.nodes = [
      { id: 'NYC3 Master ONOS (165.227.71.22)', x: w * 0.32, y: h * 0.38, type: 'master_pce', ip: '165.227.71.22' },
      { id: 'SFO3 Router (146.190.168.92)',     x: w * 0.15, y: h * 0.65, type: 'pcc_router', ip: '146.190.168.92' },
      { id: 'FRA1 Router (159.65.119.41)',     x: w * 0.75, y: h * 0.35, type: 'pcc_router', ip: '159.65.119.41' },
      { id: 'LON Transit Relay (138.68.10.14)', x: w * 0.62, y: h * 0.62, type: 'router', ip: '138.68.10.14' }
    ];
    state.links = [
      { source: 0, target: 1, cap: 10, active: true, latency: '38.2 ms' },
      { source: 0, target: 2, cap: 10, active: true, latency: '84.5 ms' },
      { source: 1, target: 3, cap: 10, active: true, latency: '65.1 ms' },
      { source: 3, target: 2, cap: 10, active: true, latency: '18.4 ms' }
    ];
  }
  
  applyLinkFaults();
  spawnParticles();
}

function applyLinkFaults() {
  const faultCount = Math.floor(state.links.length * (state.linkFaultPct / 100));
  state.links.forEach((link, idx) => {
    link.active = idx >= faultCount;
  });
}

function spawnParticles() {
  state.particles = [];
  const activeLinks = state.links.filter(l => l.active);
  const particleCount = state.currentArchetype === 'arch3' ? 140 : 70;
  
  for (let i = 0; i < particleCount; i++) {
    const link = activeLinks[Math.floor(Math.random() * activeLinks.length)];
    if (!link) continue;
    state.particles.push({
      link: link,
      progress: Math.random(),
      speed: (0.003 + Math.random() * 0.005) * state.simSpeed,
      dropped: false
    });
  }
}

// Synthetic cloud event sequence
function runCloudIllustration() {
  if (state.currentTier !== 'cloud') {
    state.currentTier = 'cloud';
    document.getElementById('tierSelect').value = 'cloud';
    loadTopology('cloud');
  }
  
  addLog('DEMO', '========== STARTING SYNTHETIC CLOUD EVENT SEQUENCE ==========');
  addLog('DEMO_PCE', 'Illustrative NYC controller event; no HTTP request was sent.');
  
  setTimeout(() => {
    addLog('DEMO_PCEP', 'Illustrative NYC-to-SFO PCEP handshake event.');
  }, 1200);

  setTimeout(() => {
    addLog('DEMO_PCEP', 'Illustrative NYC-to-FRA PCEP handshake event.');
  }, 2400);

  setTimeout(() => {
    addLog('DEMO_INITIATE', 'Illustrative PCInitiate event for a three-region path.');
  }, 3600);

  setTimeout(() => {
    addLog('DEMO_KERNEL', 'Illustrative route and label-installation event.');
  }, 4800);

  setTimeout(() => {
    addLog('DEMO_FLOW', 'Illustrative flow animation complete; no traffic was generated.');
    addLog('DEMO', '========== SYNTHETIC CLOUD EVENT SEQUENCE COMPLETE ==========');
  }, 6000);
}

// Animation Render Loop
function animLoop() {
  if (state.isPlaying) {
    updateSimulation();
  }
  renderCanvas();
  requestAnimationFrame(animLoop);
}

function updateSimulation() {
  const isBurst = state.currentArchetype === 'arch1' || state.currentArchetype === 'arch6';
  
  if (state.currentTier === 'tier2' && state.isPlaying) {
    state.orbitalAngle += 0.0015;
    const w = canvas.width / window.devicePixelRatio;
    const h = canvas.height / window.devicePixelRatio;
    const radius = h * 0.36;
    
    state.nodes.forEach(n => {
      if (n.baseSatAngle !== undefined) {
        n.x = w * 0.5 + radius * Math.cos(n.baseSatAngle + state.orbitalAngle) * Math.cos(n.basePlaneAngle);
        n.y = h * 0.5 + radius * Math.sin(n.baseSatAngle + state.orbitalAngle);
      }
    });
  }
  
  state.particles.forEach(p => {
    p.progress += p.speed * (isBurst ? 1.8 : 1.0);
    if (p.progress >= 1.0) {
      p.progress = 0;
      if (isBurst && Math.random() < 0.25) {
        p.dropped = true;
        state.metrics.packetDrops += 14;
      } else {
        p.dropped = false;
      }
    }
  });
  
  // Calculate dynamic metrics based on archetype and CoS
  if (state.currentArchetype === 'arch3') {
    state.metrics.far = 100.0;
    state.metrics.peakTransitBuffer = 15.02;
    state.metrics.sourceHoldingBuffer = 95.24;
    state.metrics.rttPenalty = 0.0;
    state.metrics.solverLatency = 251;
    state.metrics.penaltyScore = state.cosClass === 'medium' ? 1.42 : (state.cosClass === 'low' ? 0.25 : 0.0);
  } else if (state.currentArchetype === 'arch1') {
    state.metrics.far = state.currentTier === 'tier3' ? 18.7 : (state.currentTier === 'tier2' ? 69.6 : 80.8);
    state.metrics.peakTransitBuffer = 110.26;
    state.metrics.sourceHoldingBuffer = 0.00;
    state.metrics.rttPenalty = 64.1;
    state.metrics.solverLatency = 40;
    state.metrics.penaltyScore = 85.4;
  } else {
    state.metrics.far = state.currentTier === 'tier3' ? 24.5 : 44.7;
    state.metrics.peakTransitBuffer = 32.10;
    state.metrics.sourceHoldingBuffer = 78.16;
    state.metrics.rttPenalty = 18.2;
    state.metrics.solverLatency = 285;
    state.metrics.penaltyScore = 12.8;
  }
  
  updateDashboardUI();
}

function renderCanvas() {
  const w = canvas.width / window.devicePixelRatio;
  const h = canvas.height / window.devicePixelRatio;
  
  ctx.clearRect(0, 0, w, h);
  
  // Render Links
  state.links.forEach(l => {
    const src = state.nodes[l.source];
    const tgt = state.nodes[l.target];
    if (!src || !tgt) return;
    
    ctx.beginPath();
    ctx.moveTo(src.x, src.y);
    ctx.lineTo(tgt.x, tgt.y);
    
    if (!l.active) {
      ctx.strokeStyle = 'rgba(255, 0, 85, 0.4)';
      ctx.setLineDash([4, 4]);
      ctx.lineWidth = 1;
    } else {
      ctx.strokeStyle = state.currentArchetype === 'arch3' 
        ? 'rgba(0, 243, 255, 0.35)' 
        : 'rgba(255, 183, 3, 0.4)';
      ctx.setLineDash([]);
      ctx.lineWidth = 1.5;
    }
    ctx.stroke();
    ctx.setLineDash([]);
  });
  
  // Render Animated Flow Particles
  state.particles.forEach(p => {
    if (!p.link || !p.link.active) return;
    const src = state.nodes[p.link.source];
    const tgt = state.nodes[p.link.target];
    if (!src || !tgt) return;
    
    const px = src.x + (tgt.x - src.x) * p.progress;
    const py = src.y + (tgt.y - src.y) * p.progress;
    
    ctx.beginPath();
    ctx.arc(px, py, p.dropped ? 5 : 4, 0, Math.PI * 2);
    
    if (p.dropped) {
      ctx.fillStyle = '#ff0055';
      ctx.shadowColor = '#ff0055';
      ctx.shadowBlur = 8;
    } else if (state.currentArchetype === 'arch3') {
      ctx.fillStyle = state.cosClass === 'high' ? '#00ff9d' : (state.cosClass === 'medium' ? '#00f3ff' : '#ffb703');
      ctx.shadowColor = ctx.fillStyle;
      ctx.shadowBlur = 8;
    } else {
      ctx.fillStyle = '#00f3ff';
      ctx.shadowColor = '#00f3ff';
      ctx.shadowBlur = 6;
    }
    
    ctx.fill();
    ctx.shadowBlur = 0;
  });
  
  // Render Nodes
  state.nodes.forEach(n => {
    ctx.beginPath();
    const radius = n.type === 'master_pce' ? 12 : (n.type === 'core' ? 9 : 6);
    ctx.arc(n.x, n.y, radius, 0, Math.PI * 2);
    
    if (n.type === 'master_pce') {
      ctx.fillStyle = '#7928ca';
      ctx.strokeStyle = '#00f3ff';
      ctx.lineWidth = 3;
    } else if (n.type === 'satellite') {
      ctx.fillStyle = '#00f3ff';
      ctx.strokeStyle = '#00ff9d';
      ctx.lineWidth = 1.5;
    } else {
      ctx.fillStyle = '#1e293b';
      ctx.strokeStyle = '#64748b';
      ctx.lineWidth = 1.5;
    }
    
    ctx.fill();
    ctx.stroke();
  });
}

// UI Event Handlers
function setupEventListeners() {
  document.getElementById('tierSelect').addEventListener('change', (e) => {
    state.currentTier = e.target.value;
    loadTopology(state.currentTier);
    addLog('TOPOLOGY_CHANGE', `Switched topology view to ${e.target.options[e.target.selectedIndex].text}`);
    if (state.currentTier === 'cloud') {
      runCloudIllustration();
    }
  });
  
  document.getElementById('archSelect').addEventListener('change', (e) => {
    state.currentArchetype = e.target.value;
    updateTupleDisplay();
    spawnParticles();
    addLog('POLICY_CHANGE', `Displayed taxonomy entry changed to ${ARCHETYPE_TUPLES[state.currentArchetype].name}`);
  });
  
  const cosSelect = document.getElementById('cosSelect');
  if (cosSelect) {
    cosSelect.addEventListener('change', (e) => {
      state.cosClass = e.target.value;
      addLog('COS_CHANGE', `CRP/DFE v2.0 Schedulability CoS updated to ${state.cosClass.toUpperCase()}`);
    });
  }

  document.getElementById('playBtn').addEventListener('click', () => {
    state.isPlaying = !state.isPlaying;
    const btn = document.getElementById('playBtn');
    btn.innerText = state.isPlaying ? 'Pause' : 'Play';
  });
  
  document.getElementById('faultSlider').addEventListener('input', (e) => {
    state.linkFaultPct = parseInt(e.target.value);
    document.getElementById('faultVal').innerText = `${state.linkFaultPct}%`;
    applyLinkFaults();
    addLog('FAULT_INJECT', `Random ISL Link Blackout injected at ${state.linkFaultPct}% drop rate.`);
  });
  
  document.getElementById('tcpSelect').addEventListener('change', (e) => {
    state.tcpVariant = e.target.value;
    addLog('TCP_DISPLAY', `Displayed TCP variant changed to ${state.tcpVariant.toUpperCase()}`);
  });
}

function updateTupleDisplay() {
  const t = ARCHETYPE_TUPLES[state.currentArchetype];
  document.getElementById('tupleH1').innerText = t.h1;
  document.getElementById('tupleF1').innerText = t.f1;
  document.getElementById('tupleH2').innerText = t.h2;
  document.getElementById('tupleF2').innerText = t.f2;
}

function updateDashboardUI() {
  document.getElementById('farVal').innerText = `${state.metrics.far.toFixed(1)}%`;
  document.getElementById('transitVal').innerText = `${state.metrics.peakTransitBuffer.toFixed(2)} GB`;
  document.getElementById('sourceVal').innerText = `${state.metrics.sourceHoldingBuffer.toFixed(2)} GB`;
  document.getElementById('solveVal').innerText = `${state.metrics.solverLatency} ms`;
  
  const penaltyElem = document.getElementById('penaltyVal');
  if (penaltyElem) {
    penaltyElem.innerText = `${state.metrics.penaltyScore.toFixed(2)}`;
  }

  document.getElementById('hudNodes').innerText = state.nodes.length;
  document.getElementById('hudLinks').innerText = state.links.filter(l => l.active).length;
}

function addLog(tag, msg) {
  const logBox = document.getElementById('logBox');
  if (!logBox) return;
  
  const time = new Date().toISOString().substring(11, 19);
  const div = document.createElement('div');
  div.className = 'log-entry';
  div.innerHTML = `<span class="log-time">[${time}]</span><span class="log-tag">${tag}:</span>${msg}`;
  
  logBox.insertBefore(div, logBox.firstChild);
  if (logBox.children.length > 35) logBox.removeChild(logBox.lastChild);
}
