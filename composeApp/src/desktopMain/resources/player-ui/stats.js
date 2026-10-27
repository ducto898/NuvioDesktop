// nuvio-rr fork, STATS: "Stats for nerds" overlay. I (or the close button) asks the app to toggle it; the app then
// sends window.playerStats(...) once a second (StatsForNerds.kt) with display-ready rows and a verdict, and
// {visible:false} to hide it. This file keeps only the chart history and which sections are collapsed.
(() => {
  const historyLength = 60;
  const collapsedKey = "nuvio-rr-stats-collapsed";
  const history = { buffer: [], net: [], need: [] };
  let panel = null;
  let lastStats = null;

  const post = (type, value = 0) => {
    const bridge = window.chrome && window.chrome.webview;
    if (bridge) bridge.postMessage({ type, value });
  };

  const readCollapsed = () => {
    try {
      return new Set(JSON.parse(localStorage.getItem(collapsedKey) || "[]"));
    } catch (_) {
      return new Set();
    }
  };
  const collapsed = readCollapsed();
  const saveCollapsed = () => {
    try {
      localStorage.setItem(collapsedKey, JSON.stringify([...collapsed]));
    } catch (_) {
      // private storage unavailable: collapsing still works for this page
    }
  };

  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };
  const icon = paths => {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.innerHTML = paths;
    return svg;
  };
  const tone = name => getComputedStyle(panel).getPropertyValue(name).trim();

  const build = () => {
    panel = el("div", "stats-panel");
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-label", "Stats for nerds");
    const head = el("div", "stats-head");
    head.append(el("div", "stats-title", "Stats for nerds"));
    const pill = el("span", "stats-pill", "…");
    pill.dataset.status = "ok";
    head.append(pill);
    const copy = el("button", "stats-icon");
    copy.type = "button";
    copy.title = "Copy as text";
    copy.append(icon('<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h8"/>'));
    copy.addEventListener("click", copyText);
    const close = el("button", "stats-icon");
    close.type = "button";
    close.title = "Close (I)";
    close.append(icon('<path d="M6 6l12 12M18 6L6 18"/>'));
    close.addEventListener("click", () => post("statsForNerds"));
    head.append(copy, close);

    const headline = el("div", "stats-headline");
    const charts = el("div", "stats-charts");
    charts.append(chartCard("buffer", "Buffer health"), chartCard("net", "Network"));
    const body = el("div", "stats-body");
    const foot = el("div", "stats-foot");
    foot.innerHTML = 'Press <kbd>I</kbd> to hide · updates every second';
    panel.append(head, headline, charts, body, foot);
    // clicks in the panel must not reach the player (play/pause, chrome toggling)
    ["pointerdown", "click", "dblclick", "wheel"].forEach(type => panel.addEventListener(type, event => event.stopPropagation()));
    (document.getElementById("playerRoot") || document.body).append(panel);
  };

  const chartCard = (id, label) => {
    const card = el("div", "stats-chart");
    card.dataset.chart = id;
    card.append(el("div", "stats-chart-label", label));
    card.append(el("div", "stats-chart-value", "–"));
    card.append(el("canvas"));
    return card;
  };

  const push = (series, value) => {
    series.push(value == null || Number.isNaN(value) ? null : value);
    while (series.length > historyLength) series.shift();
  };

  // filled area of [values] with an optional dashed reference line (the file's bitrate on the network chart)
  const drawChart = (canvas, values, color, reference) => {
    const ratio = window.devicePixelRatio || 1;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (!width || !height) return;
    if (canvas.width !== Math.round(width * ratio)) {
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
    }
    const g = canvas.getContext("2d");
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    g.clearRect(0, 0, width, height);
    const known = values.filter(v => v != null);
    const refs = (reference || []).filter(v => v != null);
    const top = Math.max(1, ...known, ...refs.map(v => v * 1.25)) * 1.1;
    const x = i => (i + historyLength - values.length) / (historyLength - 1) * width;
    const y = v => height - 1 - (v / top) * (height - 3);

    g.strokeStyle = "rgba(255,255,255,.07)";
    g.lineWidth = 1;
    g.beginPath();
    g.moveTo(0, height - .5);
    g.lineTo(width, height - .5);
    g.stroke();

    let open = false;
    const path = new Path2D();
    let first = -1;
    let last = -1;
    values.forEach((v, i) => {
      if (v == null) return;
      if (!open) {
        path.moveTo(x(i), y(v));
        open = true;
        first = i;
      } else {
        path.lineTo(x(i), y(v));
      }
      last = i;
    });
    if (open) {
      const area = new Path2D(path);
      area.lineTo(x(last), height);
      area.lineTo(x(first), height);
      area.closePath();
      const fill = g.createLinearGradient(0, 0, 0, height);
      fill.addColorStop(0, color + "55");
      fill.addColorStop(1, color + "05");
      g.fillStyle = fill;
      g.fill(area);
      g.strokeStyle = color;
      g.lineWidth = 1.6;
      g.lineJoin = "round";
      g.stroke(path);
      g.fillStyle = color;
      g.beginPath();
      g.arc(x(last), y(values[last]), 2.4, 0, Math.PI * 2);
      g.fill();
    }
    if (refs.length) {
      const need = refs[refs.length - 1];
      g.setLineDash([3, 3]);
      g.strokeStyle = "rgba(255,255,255,.45)";
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(0, Math.round(y(need)) + .5);
      g.lineTo(width, Math.round(y(need)) + .5);
      g.stroke();
      g.setLineDash([]);
    }
  };

  const hexOf = cssVar => {
    const value = tone(cssVar);
    return /^#[0-9a-f]{6}$/i.test(value) ? value : "#3ddc84";
  };

  const renderCharts = stats => {
    const buffer = stats.buffer || {};
    const network = stats.network || {};
    const bufferCard = panel.querySelector('[data-chart="buffer"]');
    const netCard = panel.querySelector('[data-chart="net"]');

    const bufferValue = bufferCard.querySelector(".stats-chart-value");
    bufferValue.textContent = buffer.seconds != null ? `${buffer.seconds.toFixed(1)} s` : "–";
    if (buffer.mb != null) bufferValue.append(el("small", null, `${buffer.mb} MB`));
    const bufferColor = hexOf(buffer.status === "bad" ? "--stats-bad" : buffer.status === "warn" ? "--stats-warn" : "--stats-ok");
    drawChart(bufferCard.querySelector("canvas"), history.buffer, bufferColor);

    const netValue = netCard.querySelector(".stats-chart-value");
    netValue.textContent = network.mbit != null ? formatMbit(network.mbit) : "–";
    netValue.append(el("small", null, network.need != null ? `need ${Math.round(network.need)}` : network.via || ""));
    netCard.title = `${network.via || ""}; the dashed line is the file's average bitrate`;
    const short = network.mbit != null && network.need != null && network.mbit < network.need;
    drawChart(netCard.querySelector("canvas"), history.net, hexOf(short ? "--stats-warn" : "--stats-ok"), history.need);
  };

  const formatMbit = value => value < 10 ? `${value.toFixed(1)} Mbit/s` : `${Math.round(value)} Mbit/s`;

  // section blocks and their title buttons persist (a click on a title must survive the next update); rows are redrawn
  const sectionBlocks = new Map();
  const sectionBlock = title => {
    let block = sectionBlocks.get(title);
    if (block) return block;
    block = el("div", "stats-section");
    if (collapsed.has(title)) block.classList.add("collapsed");
    const button = el("button", "stats-section-title");
    button.type = "button";
    const chevron = icon('<path d="M6 9l6 6 6-6"/>');
    chevron.classList.add("stats-chevron");
    const flag = el("span", "stats-flag");
    flag.hidden = true;
    button.append(chevron, document.createTextNode(title), flag);
    button.addEventListener("click", () => {
      block.classList.toggle("collapsed");
      if (block.classList.contains("collapsed")) collapsed.add(title);
      else collapsed.delete(title);
      saveCollapsed();
    });
    block.append(button, el("div", "stats-rows"));
    sectionBlocks.set(title, block);
    return block;
  };

  const renderBody = stats => {
    const body = panel.querySelector(".stats-body");
    const blocks = (stats.sections || []).map(section => {
      const block = sectionBlock(section.title);
      const worst = (section.rows || []).reduce((acc, row) => row.s === "bad" ? "bad" : (row.s === "warn" && acc !== "bad") ? "warn" : acc, "");
      const flag = block.querySelector(".stats-flag");
      flag.hidden = !worst;
      if (worst) flag.dataset.status = worst;
      const rows = block.querySelector(".stats-rows");
      rows.replaceChildren(...(section.rows || []).map(row => {
        const line = el("div", "stats-row");
        if (row.s) line.dataset.status = row.s;
        line.append(el("div", "stats-key", row.k));
        const value = el("div", "stats-val", row.v);
        if (row.d) value.append(el("small", null, row.d));
        line.append(value);
        return line;
      }));
      return block;
    });
    if (blocks.length !== body.children.length || blocks.some((block, i) => body.children[i] !== block)) {
      const scroll = body.scrollTop;
      body.replaceChildren(...blocks);
      body.scrollTop = scroll;
    }
  };

  const renderHead = stats => {
    const pill = panel.querySelector(".stats-pill");
    pill.dataset.status = stats.status || "ok";
    pill.textContent = stats.status === "bad" ? "Problem" : stats.status === "warn" ? "Check" : "Good";
    const headline = panel.querySelector(".stats-headline");
    headline.dataset.status = stats.status || "ok";
    headline.textContent = stats.headline || "";
    const more = stats.issues || [];
    if (more.length) {
      const list = el("ul", "stats-more");
      more.forEach(issue => list.append(el("li", null, issue.v)));
      headline.append(list);
    }
  };

  const copyText = () => {
    if (!lastStats) return;
    const lines = [`Nuvio RR stats — ${new Date().toLocaleString()}`, `Status: ${lastStats.status} — ${lastStats.headline}`];
    (lastStats.issues || []).forEach(issue => lines.push(`  also: ${issue.v}`));
    const buffer = lastStats.buffer || {};
    const network = lastStats.network || {};
    if (buffer.seconds != null) lines.push(`Buffer health: ${buffer.seconds} s${buffer.mb != null ? ` (${buffer.mb} MB)` : ""}`);
    if (network.mbit != null) lines.push(`Network: ${formatMbit(network.mbit)} via ${network.via}${network.need != null ? `, file needs ~${formatMbit(network.need)}` : ""}`);
    (lastStats.sections || []).forEach(section => {
      lines.push("", section.title);
      (section.rows || []).forEach(row => lines.push(`  ${row.k}: ${row.v}${row.d ? ` (${row.d})` : ""}${row.s && row.s !== "ok" ? ` [${row.s}]` : ""}`));
    });
    const text = lines.join("\n");
    const foot = panel.querySelector(".stats-foot");
    const done = ok => {
      foot.innerHTML = ok ? '<span class="stats-toast">Copied</span>' : "Copy failed";
      window.setTimeout(() => { foot.innerHTML = 'Press <kbd>I</kbd> to hide · updates every second'; }, 1500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => done(true), () => done(fallbackCopy(text)));
    } else {
      done(fallbackCopy(text));
    }
  };

  const fallbackCopy = text => {
    const area = el("textarea");
    area.value = text;
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.append(area);
    area.select();
    let ok = false;
    try {
      ok = document.execCommand("copy");
    } catch (_) {
      ok = false;
    }
    area.remove();
    return ok;
  };

  window.playerStats = stats => {
    if (!panel) build();
    if (!stats || !stats.visible) {
      panel.classList.remove("shown");
      history.buffer.length = 0;
      history.net.length = 0;
      history.need.length = 0;
      lastStats = null;
      return;
    }
    lastStats = stats;
    push(history.buffer, stats.buffer && stats.buffer.seconds);
    push(history.net, stats.network && stats.network.mbit);
    push(history.need, stats.network && stats.network.need);
    renderHead(stats);
    renderBody(stats);
    panel.classList.add("shown");
    renderCharts(stats);
  };

  const isTyping = target => Boolean(target && target.closest && target.closest("input, textarea, select, [contenteditable='true']"));
  document.addEventListener("keydown", event => {
    if (event.code !== "KeyI" || event.repeat || event.ctrlKey || event.metaKey || event.altKey || isTyping(event.target)) return;
    event.preventDefault();
    post("statsForNerds");
  });
  window.addEventListener("resize", () => { if (lastStats && panel) renderCharts(lastStats); });
})();
