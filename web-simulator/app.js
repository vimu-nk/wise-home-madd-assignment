// WiseHome hardware simulator.
//
// This stands in for the physical devices. Its controls are deliberately the
// inverse of the phone app's: the app asks the house to do something, the
// simulator reports what the hardware is actually doing — including the fault
// states (ERROR / DISCONNECTED) that nothing else in the system can produce.

const floorTabsEl = document.getElementById("floor-tabs");
const roomListEl = document.getElementById("room-list");
const statusDotEl = document.getElementById("connection-dot");
const statusTextEl = document.getElementById("connection-text");
const simulateToggle = document.getElementById("simulate-toggle");

/** Show setup failures on the page — a silent throw here just leaves the
 *  placeholder text up forever, which looks identical to "still connecting". */
function fatal(message) {
  console.error(message);
  if (roomListEl) {
    roomListEl.replaceChildren();
    const box = document.createElement("div");
    box.className = "fatal";
    box.textContent = message;
    roomListEl.append(box);
  }
  throw new Error(message);
}

if (typeof supabase === "undefined") {
  fatal("Supabase library failed to load. Check your network connection, then hard-refresh (Ctrl+Shift+R).");
}
if (!window.SUPABASE_CONFIG) {
  fatal("config.js did not load. Hard-refresh the page (Ctrl+Shift+R).");
}
if (!window.WiseVocab || !window.WiseTime) {
  fatal("vocabulary.js / timeformat.js did not load — your browser is probably serving a cached page. Hard-refresh with Ctrl+Shift+R.");
}

const supabaseClient = supabase.createClient(
  window.SUPABASE_CONFIG.url,
  window.SUPABASE_CONFIG.anonKey
);

// Namespaced rather than destructured: vocabulary.js declares these as top-level
// functions, so `const { roomsForFloor, ... }` here is a redeclaration in the same
// global scope and kills the whole file with a SyntaxError before it ever runs.
const Vocab = window.WiseVocab;

const SENSOR_RANGES = {
  motion: { min: 0, max: 1, binary: true },
  door_window: { min: 0, max: 1, binary: true },
  water_leak: { min: 0, max: 1, binary: true },
  smoke: { min: 0, max: 80, unit: "ppm" },
  gas: { min: 0, max: 60, unit: "ppm" }
};

const state = {
  floors: [],
  devices: [],
  selectedFloorId: null,
  switches: new Map(), // deviceId -> [switch]
  sensors: new Map(),
  cameras: new Map(),
  locks: new Map(),
  thermostats: new Map(),
  acUnits: new Map(),
  power: new Map()
};

let simulateIntervalId = null;

// ---------------------------------------------------------------- data load

function indexBy(rows, key) {
  return new Map((rows || []).map((row) => [row[key], row]));
}

/** Rejects if a request never settles, so a hang surfaces instead of looking idle. */
function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${ms / 1000}s`)), ms)
    )
  ]);
}

async function loadAll() {
  try {
    const results = await withTimeout(Promise.all([
      supabaseClient.from("floors").select("*"),
      supabaseClient.from("devices").select("*"),
      supabaseClient.from("device_switches").select("*"),
      supabaseClient.from("sensors").select("*"),
      supabaseClient.from("cameras").select("*"),
      supabaseClient.from("smart_locks").select("*"),
      supabaseClient.from("thermostats").select("*"),
      supabaseClient.from("ac_units").select("*"),
      supabaseClient.from("power_metrics").select("*")
    ]), 15000, "Loading data from Supabase");

    const failed = results.find((r) => r.error);
    if (failed) {
      roomListEl.textContent = `Error loading data: ${failed.error.message}`;
      return;
    }

    const [floors, devices, switches, sensors, cameras, locks, thermostats, acUnits, power] =
      results.map((r) => r.data);

    state.floors = floors.sort((a, b) => {
      const order = ["Ground Floor", "First Floor", "Exterior / Garden"];
      return order.indexOf(a.name) - order.indexOf(b.name);
    });
    state.devices = devices;
    state.sensors = indexBy(sensors, "device_id");
    state.cameras = indexBy(cameras, "device_id");
    state.locks = indexBy(locks, "device_id");
    state.thermostats = indexBy(thermostats, "device_id");
    state.acUnits = indexBy(acUnits, "device_id");
    state.power = indexBy(power, "device_id");

    state.switches = new Map();
    (switches || []).forEach((sw) => {
      const list = state.switches.get(sw.device_id) || [];
      list.push(sw);
      state.switches.set(sw.device_id, list);
    });
    state.switches.forEach((list) => list.sort((a, b) => a.switch_index - b.switch_index));

    if (!state.selectedFloorId && state.floors.length) {
      state.selectedFloorId = state.floors[0].id;
    }

    render();
  } catch (err) {
    roomListEl.textContent = `Connection failed: ${err.message}. Check config.js and your network.`;
    console.error(err);
  }
}

function contextFor(device) {
  return {
    switches: state.switches.get(device.id),
    sensor: state.sensors.get(device.id),
    camera: state.cameras.get(device.id),
    lock: state.locks.get(device.id),
    power: state.power.get(device.id),
    acUnit: state.acUnits.get(device.id),
    thermostat:
      state.thermostats.get(device.id) ||
      [...state.thermostats.values()].find((t) => t.controls_device_id === device.id)
  };
}

// ------------------------------------------------------------------- render

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function render() {
  renderFloorTabs();
  renderRooms();
}

function renderFloorTabs() {
  floorTabsEl.replaceChildren();
  state.floors.forEach((floor) => {
    const tab = el("button", "floor-tab", floor.name);
    if (floor.id === state.selectedFloorId) tab.classList.add("is-selected");
    tab.addEventListener("click", () => {
      state.selectedFloorId = floor.id;
      render();
    });
    floorTabsEl.appendChild(tab);
  });
}

function renderRooms() {
  roomListEl.replaceChildren();

  if (!state.floors.length) {
    roomListEl.append(el("div", "empty", "No floors found. Has schema.sql and seed.sql been run against this project?"));
    return;
  }

  const floor = state.floors.find((f) => f.id === state.selectedFloorId);
  if (!floor) return;

  const floorDevices = state.devices.filter((d) => d.floor_id === floor.id);
  if (!floorDevices.length) {
    roomListEl.append(el("div", "empty", `No devices on ${floor.name}.`));
    return;
  }

  Vocab.roomsForFloor(floor).forEach((room) => {
    const roomDevices = floorDevices.filter((d) => Vocab.roomContains(room, d));
    if (!roomDevices.length) return;

    const section = el("section", "room");
    const header = el("div", "room-header");
    header.append(el("h2", null, room.label));
    header.append(el("span", "room-count", `${roomDevices.length} devices`));
    section.append(header);

    const grid = el("div", "device-grid");
    roomDevices.forEach((device) => grid.append(renderDeviceCard(device)));
    section.append(grid);

    roomListEl.append(section);
  });
}

function renderDeviceCard(device) {
  const ctx = contextFor(device);
  const display = Vocab.deviceDisplay(device, ctx);

  const card = el("article", "device-card");
  card.dataset.deviceId = device.id;

  const top = el("div", "device-card-top");
  const identity = el("div");
  identity.append(el("div", "device-name", device.name));
  identity.append(el("div", "device-type", Vocab.deviceTypeLabel(device, ctx)));
  top.append(identity, el("span", `status-badge ${display.tone}`, display.stateLabel));
  card.append(top);

  if (display.detail) card.append(el("div", "device-detail", display.detail));

  const controls = el("div", "device-controls");
  buildControls(device, ctx, display, controls);
  card.append(controls);

  card.append(faultControls(device));
  return card;
}

function buildControls(device, ctx, display, container) {
  switch (device.type) {
    case "sensor":
      container.append(sensorControls(device, ctx));
      break;
    case "camera":
      container.append(cameraControls(device, ctx));
      break;
    case "multiswitch":
      container.append(switchControls(device, ctx));
      break;
    case "smart_lock":
      container.append(
        actionButton(display.onVerb, device.status !== "ON", () => setLock(device, true)),
        actionButton(display.offVerb, device.status === "ON", () => setLock(device, false))
      );
      break;
    case "ac_unit":
      container.append(acControls(device, ctx));
      break;
    case "smart_plug_metered":
      container.append(powerControls(device, ctx));
      break;
    case "thermostat":
      container.append(el("p", "hint", "Driven from the app — the panel has no hardware control."));
      break;
    default:
      container.append(
        actionButton(
          device.status === "ON" ? display.offVerb || "Turn off" : display.onVerb || "Turn on",
          true,
          () => setStatus(device, device.status === "ON" ? "OFF" : "ON")
        )
      );
  }
}

function actionButton(label, enabled, onClick, variant = "") {
  const btn = el("button", `btn ${variant}`.trim(), label);
  btn.disabled = !enabled;
  btn.addEventListener("click", onClick);
  return btn;
}

/**
 * ERROR and DISCONNECTED are hardware conditions — the app can never produce
 * them. Simulating them here is the only way to exercise those two states.
 */
function faultControls(device) {
  const row = el("div", "fault-row");
  row.append(el("span", "fault-label", "Simulate hardware"));

  const faulty = device.status === "ERROR";
  const offline = device.status === "DISCONNECTED";

  row.append(
    actionButton(faulty ? "Clear fault" : "Fault", true, () =>
      setStatus(device, faulty ? "OFF" : "ERROR"), "btn-ghost"),
    actionButton(offline ? "Reconnect" : "Disconnect", true, () =>
      setStatus(device, offline ? "OFF" : "DISCONNECTED"), "btn-ghost")
  );
  return row;
}

function switchControls(device, ctx) {
  const wrap = el("div", "switch-list");
  (ctx.switches || []).forEach((sw) => {
    const row = el("div", "switch-row");
    row.append(el("span", null, sw.label || `Switch ${sw.switch_index}`));
    row.append(
      actionButton(sw.status === "ON" ? "Turn off" : "Turn on", true, () =>
        setSwitch(device, sw, sw.status === "ON" ? "OFF" : "ON"))
    );
    wrap.append(row);
  });
  return wrap;
}

function sensorControls(device, ctx) {
  const sensor = ctx.sensor;
  const wrap = el("div", "sensor-controls");
  if (!sensor) {
    wrap.append(el("p", "hint", "No sensor row linked."));
    return wrap;
  }

  const range = SENSOR_RANGES[sensor.sensor_type] || { min: 0, max: 100 };
  const triggered = device.status === "ON";

  wrap.append(
    actionButton(triggered ? "Clear" : "Trigger", true, () => triggerSensor(device, sensor, !triggered))
  );

  if (!range.binary) {
    const slider = document.createElement("input");
    slider.type = "range";
    slider.min = range.min;
    slider.max = range.max;
    slider.step = 1;
    slider.value = sensor.current_reading ?? range.min;
    slider.className = "slider";
    slider.addEventListener("change", () => setSensorReading(device, sensor, Number(slider.value)));
    wrap.append(slider);
  }
  return wrap;
}

function cameraControls(device, ctx) {
  const wrap = el("div", "camera-controls");
  const camera = ctx.camera;
  if (camera?.last_snapshot_url) {
    const img = document.createElement("img");
    img.className = "snapshot";
    img.src = camera.last_snapshot_url;
    img.alt = `${device.name} snapshot`;
    wrap.append(img);
  } else {
    wrap.append(el("div", "snapshot-placeholder", "No snapshot yet"));
  }
  wrap.append(actionButton("New snapshot", true, () => cycleCamera(device.id)));
  return wrap;
}

function acControls(device, ctx) {
  const wrap = el("div", "ac-controls");
  const current = ctx.acUnit?.current_temp_c;
  wrap.append(el("span", "reading", `Reported room temperature: ${current ?? "—"}°`));
  const row = el("div", "btn-row");
  row.append(
    actionButton("−1°", true, () => setAcTemp(device.id, (current ?? 24) - 1), "btn-ghost"),
    actionButton("+1°", true, () => setAcTemp(device.id, (current ?? 24) + 1), "btn-ghost")
  );
  wrap.append(row);
  return wrap;
}

function powerControls(device, ctx) {
  const wrap = el("div", "power-controls");
  wrap.append(
    actionButton(device.status === "ON" ? "Turn off" : "Turn on", true, () =>
      setStatus(device, device.status === "ON" ? "OFF" : "ON"))
  );
  const slider = document.createElement("input");
  slider.type = "range";
  slider.min = 0;
  slider.max = 2000;
  slider.step = 10;
  slider.value = ctx.power?.current_watts ?? 0;
  slider.className = "slider";
  slider.addEventListener("change", () => setWatts(device.id, Number(slider.value)));
  wrap.append(slider);
  return wrap;
}

// -------------------------------------------------------------------- writes

async function setStatus(device, status) {
  await supabaseClient.from("devices").update({ status }).eq("id", device.id);
}

async function setLock(device, locked) {
  const stamp = new Date().toISOString();
  await supabaseClient.from("devices").update({ status: locked ? "ON" : "OFF" }).eq("id", device.id);
  await supabaseClient
    .from("smart_locks")
    .update(locked ? { last_locked_at: stamp } : { last_unlocked_at: stamp })
    .eq("device_id", device.id);
}

async function setSwitch(device, sw, status) {
  await supabaseClient.from("device_switches").update({ status }).eq("id", sw.id);
  const siblings = (state.switches.get(device.id) || []).map((s) =>
    s.id === sw.id ? { ...s, status } : s
  );
  const anyOn = siblings.some((s) => s.status === "ON");
  await supabaseClient.from("devices").update({ status: anyOn ? "ON" : "OFF" }).eq("id", device.id);
}

async function triggerSensor(device, sensor, triggered) {
  const range = SENSOR_RANGES[sensor.sensor_type] || { min: 0, max: 100 };
  const reading = range.binary ? (triggered ? 1 : 0) : triggered ? range.max : range.min;
  await supabaseClient
    .from("sensors")
    .update({
      current_reading: reading,
      ...(triggered ? { last_triggered_at: new Date().toISOString() } : {})
    })
    .eq("device_id", device.id);
  await supabaseClient.from("devices").update({ status: triggered ? "ON" : "OFF" }).eq("id", device.id);
  if (triggered) {
    await supabaseClient.from("usage_logs").insert({
      device_id: device.id,
      event_type: "SENSOR_TRIGGERED",
      triggered_by: "schedule"
    });
  }
}

async function setSensorReading(device, sensor, reading) {
  const range = SENSOR_RANGES[sensor.sensor_type] || { min: 0, max: 100 };
  const overThreshold = sensor.alert_threshold != null && reading >= sensor.alert_threshold;
  await supabaseClient
    .from("sensors")
    .update({
      current_reading: reading,
      ...(overThreshold ? { last_triggered_at: new Date().toISOString() } : {})
    })
    .eq("device_id", device.id);
  await supabaseClient
    .from("devices")
    .update({ status: overThreshold ? "ON" : "OFF" })
    .eq("id", device.id);
}

async function setAcTemp(deviceId, temp) {
  await supabaseClient
    .from("ac_units")
    .update({ current_temp_c: Math.round(temp * 10) / 10 })
    .eq("device_id", deviceId);
}

async function setWatts(deviceId, watts) {
  await supabaseClient
    .from("power_metrics")
    .update({ current_watts: watts, last_reading_at: new Date().toISOString() })
    .eq("device_id", deviceId);
}

function stockPhotoUrls(deviceId) {
  return [0, 1, 2].map((i) => `https://picsum.photos/seed/wisehome-${deviceId}-${i}/480/320`);
}

async function cycleCamera(deviceId) {
  const camera = state.cameras.get(deviceId);
  const photos = stockPhotoUrls(deviceId);
  const next = photos[(photos.indexOf(camera?.last_snapshot_url) + 1) % photos.length];
  await supabaseClient
    .from("cameras")
    .update({ last_snapshot_url: next, last_snapshot_at: new Date().toISOString() })
    .eq("device_id", deviceId);
}

// ------------------------------------------------------------------ realtime

const WATCHED_TABLES = [
  "devices",
  "device_switches",
  "sensors",
  "cameras",
  "smart_locks",
  "thermostats",
  "ac_units",
  "power_metrics"
];

function setConnectionState(connected, label) {
  statusDotEl.className = `status-dot ${connected ? "active" : "offline"}`;
  statusTextEl.textContent = label;
}

function subscribeToChanges() {
  const channel = supabaseClient.channel("simulator-all");
  WATCHED_TABLES.forEach((table) => {
    channel.on("postgres_changes", { event: "*", schema: "public", table }, () => loadAll());
  });
  channel.subscribe((status) => {
    if (status === "SUBSCRIBED") {
      setConnectionState(true, "Live · connected");
      loadAll();
    } else if (status === "CHANNEL_ERROR" || status === "TIMED_OUT") {
      setConnectionState(false, "Reconnecting…");
    } else if (status === "CLOSED") {
      setConnectionState(false, "Offline");
    }
  });
}

// ------------------------------------------------------------ simulate timer

function randomDrift(sensor) {
  const range = SENSOR_RANGES[sensor.sensor_type] || { min: 0, max: 100 };
  if (range.binary) return Math.random() < 0.2 ? 1 : 0;
  const current = sensor.current_reading ?? (range.min + range.max) / 2;
  const drift = (Math.random() - 0.5) * (range.max - range.min) * 0.2;
  return Math.round(Math.max(range.min, Math.min(range.max, current + drift)));
}

async function simulateTick() {
  try {
    const sensors = [...state.sensors.values()];
    if (!sensors.length) return;
    const sensor = sensors[Math.floor(Math.random() * sensors.length)];
    const device = state.devices.find((d) => d.id === sensor.device_id);
    if (!device) return;
    await setSensorReading(device, sensor, randomDrift(sensor));

    // Occasionally refresh a camera so the demo looks alive.
    if (Math.random() < 0.3) {
      const cameraIds = [...state.cameras.keys()];
      if (cameraIds.length) {
        await cycleCamera(cameraIds[Math.floor(Math.random() * cameraIds.length)]);
      }
    }
  } catch (err) {
    console.error("simulateTick failed:", err);
  }
}

simulateToggle.addEventListener("change", () => {
  if (simulateToggle.checked) {
    simulateIntervalId = setInterval(simulateTick, 10000);
  } else {
    clearInterval(simulateIntervalId);
    simulateIntervalId = null;
  }
});

// --------------------------------------------------------------------- start

setConnectionState(false, "Connecting…");
loadAll();
subscribeToChanges();
