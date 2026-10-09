/* Synthetic model for the app mockups. Fields mirror the ESP32 status block (docs/app/PHONE_LINK_PROTOCOL.md). */
(() => {
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const SC = {
    ready:   { name: "Ready",           link: true,  bike: true,  sd: true,  clock: true, ha: true },
    offline: { name: "Dead zone",       link: true,  bike: true,  sd: true,  clock: true, ha: false },
    sd:      { name: "SD card missing", link: true,  bike: true,  sd: false, clock: true, ha: true },
    nobike:  { name: "Bike off",        link: true,  bike: false, sd: true,  clock: true, ha: true },
    nolink:  { name: "Bridge lost",     link: false, bike: false, sd: true,  clock: true, ha: true },
  };
  const ZN = ["Recovery", "Endurance", "Tempo", "Threshold", "Surge"], FTP = 150;
  const st = { key: "ready", s: SC.ready, ride: { w: 120, v: 19, cad: 74, soc: 70, odo: 142.3 }, backlog: 0, up: 1620, age: 0, tick: 0 };
  const subs = [];
  const emit = () => subs.forEach(f => f(api));

  function setScenario(k) {
    st.key = k; st.s = SC[k]; st.age = 0;
    st.backlog = k === "offline" ? 37 : 0;
    if (st.s.link && !st.s.bike) st.ride = { ...st.ride, w: 0, v: 0, cad: 0 };  // a lost bridge keeps the last real values
    emit();
  }

  function step() {
    st.tick++; st.up++;
    const r = st.ride, s = st.s;
    if (!s.link) { st.age++; }
    else if (!s.bike) { r.w = 0; r.v = 0; r.cad = 0; }
    else {
      r.w = Math.max(0, r.w + (Math.sin(st.tick / 9) * 14 + (Math.random() - .5) * 26) - (r.w - 125) * .06);
      r.v = Math.max(0, r.v + (r.w * .065 + 8 - r.v) * .15);
      r.cad = Math.max(0, r.cad + (r.w * .1 + 62 - r.cad) * .2);
      r.soc = Math.max(0, r.soc - .004); r.odo += r.v / 3600;
      if (!s.ha) st.backlog += 1; else if (st.backlog > 0) st.backlog = Math.max(0, st.backlog - 4);
    }
    emit();
  }

  const unknown = { state: "unknown", value: "No data", sub: "Bridge not connected" };
  function rows() {
    const s = st.s, L = s.link, n = st.backlog, min = Math.round(st.up / 60);
    return [
      { id: "link", label: "ESP32 bridge", state: L ? "ok" : "bad", value: L ? "Connected" : "Not found", sub: L ? "Phone link encrypted" : `Unreachable for ${st.age || 14} s` },
      L ? { id: "bike", label: "Bike", state: s.bike ? "ok" : "off", value: s.bike ? "Connected" : "Off", sub: s.bike ? "Recording rides" : "Waiting for the bike" } : { id: "bike", label: "Bike", ...unknown },
      L ? { id: "sd", label: "SD card", state: s.sd ? "ok" : "bad", value: s.sd ? "OK" : "Missing", sub: s.sd ? "92 % free" : "Rides are not being saved" } : { id: "sd", label: "SD card", ...unknown },
      L ? { id: "clock", label: "Clock", state: s.clock ? "ok" : "bad", value: s.clock ? "Set" : "Not set", sub: s.clock ? "Set by the phone, 14:02" : "Records are undated" } : { id: "clock", label: "Clock", ...unknown },
      L ? { id: "ha", label: "Home Assistant", state: s.ha ? "ok" : "off", value: s.ha ? "Online" : "Offline", sub: s.ha ? "Uploading as you ride" : "Saving to the card" } : { id: "ha", label: "Home Assistant", ...unknown },
      L ? { id: "backlog", label: "Backlog", state: n === 0 ? "ok" : "warn", value: n === 0 ? "0 waiting" : `${n} waiting`, sub: n === 0 ? "All uploaded" : "Uploads when back online" } : { id: "backlog", label: "Backlog", ...unknown },
      L ? { id: "drop", label: "Dropped", state: "ok", value: "0 dropped", sub: "0 write failures" } : { id: "drop", label: "Dropped", ...unknown },
      L ? { id: "up", label: "ESP32 uptime", state: "ok", value: `${min} min`, sub: "Boot 0ECD971A" } : { id: "up", label: "ESP32 uptime", ...unknown },
    ];
  }

  function verdict() {
    const s = st.s;
    if (!s.link) return { tone: "bad", title: "No bridge", sub: "Cannot reach the ESP32. Is it powered?" };
    if (!s.sd) return { tone: "bad", title: "Not ready", sub: "SD card missing. Rides will not be saved." };
    if (!s.bike) return { tone: "wait", title: "Switch bike on", sub: "Bridge and card are ready. Waiting for the bike." };
    if (!s.ha) return { tone: "ok", title: "Ready to ride", sub: "Home Assistant offline. Saving to the card." };
    return { tone: "ok", title: "Ready to ride", sub: "Bike, card and uploads are all working." };
  }

  function banner() {
    const s = st.s;
    if (!s.link) return `Bridge lost ${st.age} s. Last values shown.`;
    if (!s.sd) return "SD card missing. Rides are not being saved.";
    return "";
  }

  const zoneIndex = w => { const f = w / FTP; return f < .55 ? 0 : f < .75 ? 1 : f < .9 ? 2 : f < 1.05 ? 3 : 4; };

  /* fixed cells; pattern "###" or "##.#"; set("24.1") right-aligns into it and flips only the cells that changed */
  function cells(el, pattern, cls) {
    el.innerHTML = "";
    const els = [...pattern].map(ch => {
      const c = document.createElement("span");
      c.className = "cell " + (cls || "") + (ch === "." ? " dot" : "");
      c.innerHTML = "<b></b>"; el.appendChild(c); return c;
    });
    const shown = [];
    return function set(str) {
      const chars = [...String(str)], out = new Array(pattern.length).fill("");
      for (let i = pattern.length - 1, j = chars.length - 1; i >= 0 && j >= 0; i--, j--) out[i] = chars[j];
      [...pattern].forEach((p, i) => { if (p === ".") out[i] = "."; });
      out.forEach((ch, i) => {
        if (shown[i] === ch) return;
        shown[i] = ch;
        els[i].firstChild.textContent = ch || " ";
        els[i].classList.toggle("blank", ch === "");
        if (!reduce && els[i].isConnected) { els[i].classList.remove("flip"); void els[i].offsetWidth; els[i].classList.add("flip"); }
      });
    };
  }

  function zones(el) {
    el.innerHTML = [1, 2, 3, 4, 5].map(i => `<div style="background:var(--z${i})">${i}</div>`).join("");
    return function (i) { [...el.children].forEach((d, k) => k === i ? d.setAttribute("aria-current", "true") : d.removeAttribute("aria-current")); };
  }

  function controls(el) {
    el.innerHTML = Object.entries(SC).map(([k, v]) => `<button type="button" data-k="${k}" aria-pressed="${k === st.key}">${v.name}</button>`).join("");
    el.addEventListener("click", e => {
      const b = e.target.closest("button"); if (!b) return;
      [...el.children].forEach(x => x.setAttribute("aria-pressed", x === b));
      setScenario(b.dataset.k);
    });
    const q = new URLSearchParams(location.search).get("s");
    if (q && SC[q]) { [...el.children].forEach(x => x.setAttribute("aria-pressed", x.dataset.k === q)); setScenario(q); }
  }

  const api = { st, rows, verdict, banner, zoneIndex, ZN, cells, zones, controls, on: f => { subs.push(f); f(api); }, start: () => { step(); setInterval(step, 1000); } };
  window.EB = api;
})();
