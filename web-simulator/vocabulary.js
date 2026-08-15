// Per-device-type language. Mirrors ui/format/DeviceDisplay.kt so the simulator
// and the Android app never disagree about what a device's state is called.
//
// "ON"/"OFF" is meaningless on a gate, an iron or a smoke detector.

const TONE = {
  ACTIVE: "active",
  IDLE: "idle",
  ATTENTION: "attention",
  FAULT: "fault",
  OFFLINE: "offline"
};

const ROOMS = {
  "ground floor": [
    { label: "Foyer", x0: 0, y0: 0, x1: 1, y1: 0 },
    { label: "Living Room", x0: 2, y0: 0, x1: 3, y1: 2 },
    { label: "Kitchen", x0: 4, y0: 0, x1: 5, y1: 2 },
    { label: "Dining Area", x0: 0, y0: 1, x1: 1, y1: 2 },
    { label: "Guest Bedroom", x0: 0, y0: 3, x1: 1, y1: 4 },
    { label: "Garage", x0: 2, y0: 3, x1: 5, y1: 4 }
  ],
  "first floor": [
    { label: "Master Bedroom", x0: 0, y0: 0, x1: 1, y1: 1 },
    { label: "Bedroom 2", x0: 3, y0: 0, x1: 4, y1: 1 },
    { label: "Study / Office", x0: 5, y0: 0, x1: 5, y1: 3 },
    { label: "Bathroom", x0: 2, y0: 2, x1: 2, y1: 3 },
    { label: "Balcony", x0: 0, y0: 4, x1: 1, y1: 4 },
    { label: "Landing / Hallway", x0: 2, y0: 4, x1: 4, y1: 4 }
  ],
  "exterior / garden": [
    { label: "Walking Gate", x0: 0, y0: 0, x1: 1, y1: 1 },
    { label: "Driveway Gate", x0: 3, y0: 0, x1: 4, y1: 1 },
    { label: "Front Approach", x0: 2, y0: 2, x1: 5, y1: 3 },
    { label: "Back Garden", x0: 5, y0: 4, x1: 7, y1: 5 }
  ]
};

function roomsForFloor(floor) {
  return ROOMS[floor.name.trim().toLowerCase()] || [
    { label: floor.name, x0: 0, y0: 0, x1: floor.grid_cols - 1, y1: floor.grid_rows - 1 }
  ];
}

function roomContains(room, device) {
  return (
    device.grid_x >= room.x0 &&
    device.grid_x <= room.x1 &&
    device.grid_y >= room.y0 &&
    device.grid_y <= room.y1
  );
}

function applianceTypeLabel(raw) {
  const map = {
    tv: "Television",
    fridge: "Refrigerator",
    washing_machine: "Washing machine",
    microwave: "Microwave",
    fan: "Exhaust fan",
    water_heater: "Water heater"
  };
  if (!raw) return "Appliance";
  return map[raw.toLowerCase()] || raw.replace(/_/g, " ");
}

function sensorTypeLabel(type) {
  return {
    motion: "Motion sensor",
    door_window: "Door & window sensor",
    smoke: "Smoke detector",
    gas: "Gas detector",
    water_leak: "Water leak sensor"
  }[type] || "Sensor";
}

function deviceTypeLabel(device, ctx = {}) {
  switch (device.type) {
    case "smart_lock":
      return {
        sliding_gate: "Sliding gate",
        turnstile: "Turnstile gate"
      }[ctx.lock?.mechanism] || "Deadbolt lock";
    case "multiswitch":
      return ctx.switches?.length ? `${ctx.switches.length}-gang switch panel` : "Switch panel";
    case "scheduled_safety":
      return "Safety-timed appliance";
    case "scheduled_light":
      return "Light";
    case "smart_plug_metered":
      return "Metered plug";
    case "ac_unit":
      return "Air conditioner";
    case "thermostat":
      return "Thermostat";
    case "camera":
      return "Security camera";
    case "outlet":
      return "Power outlet";
    case "sensor":
      return sensorTypeLabel(ctx.sensor?.sensor_type);
    case "appliance":
      return applianceTypeLabel(device.appliance_type);
    default:
      return device.type.replace(/_/g, " ");
  }
}

/** Returns { stateLabel, tone, onVerb, offVerb, detail }. */
function deviceDisplay(device, ctx = {}) {
  if (device.status === "ERROR") {
    return { stateLabel: "Needs attention", tone: TONE.FAULT };
  }
  if (device.status === "DISCONNECTED") {
    return { stateLabel: "Offline", tone: TONE.OFFLINE };
  }

  const on = device.status === "ON";

  switch (device.type) {
    case "smart_lock": {
      const mech = ctx.lock?.mechanism;
      const labels = {
        sliding_gate: { on: "Closed", off: "Open", onVerb: "Close", offVerb: "Open" },
        turnstile: { on: "Locked", off: "Free to turn", onVerb: "Lock", offVerb: "Release" }
      }[mech] || { on: "Locked", off: "Unlocked", onVerb: "Lock", offVerb: "Unlock" };
      const stamp = on ? ctx.lock?.last_locked_at : ctx.lock?.last_unlocked_at;
      return {
        stateLabel: on ? labels.on : labels.off,
        tone: on ? TONE.ACTIVE : TONE.ATTENTION,
        onVerb: labels.onVerb,
        offVerb: labels.offVerb,
        detail: stamp ? `${on ? labels.on : labels.off} ${WiseTime.formatRelative(stamp)}` : null
      };
    }

    case "multiswitch": {
      const switches = ctx.switches || [];
      const total = switches.length;
      const onCount = switches.filter((s) => s.status === "ON").length;
      let label;
      if (total === 0) label = on ? "On" : "Off";
      else if (onCount === 0) label = "All off";
      else if (onCount === total) label = "All on";
      else label = `${onCount} of ${total} on`;
      return { stateLabel: label, tone: onCount > 0 ? TONE.ACTIVE : TONE.IDLE };
    }

    case "scheduled_safety":
      return {
        stateLabel: on ? "Heating" : "Off",
        tone: on ? TONE.ATTENTION : TONE.IDLE,
        onVerb: "Turn on",
        offVerb: "Turn off"
      };

    case "scheduled_light":
      return {
        stateLabel: on ? "On" : "Off",
        tone: on ? TONE.ACTIVE : TONE.IDLE,
        onVerb: "Turn on",
        offVerb: "Turn off"
      };

    case "ac_unit": {
      const mode = ctx.thermostat?.mode;
      const active = mode === "HEAT" ? "Heating" : mode === "COOL" ? "Cooling" : "Running";
      const bits = [];
      if (ctx.acUnit?.fan_speed) bits.push(`Fan ${ctx.acUnit.fan_speed.toLowerCase()}`);
      if (ctx.acUnit?.current_temp_c != null) bits.push(`Room ${ctx.acUnit.current_temp_c}°`);
      return {
        stateLabel: on ? active : "Idle",
        tone: on ? TONE.ACTIVE : TONE.IDLE,
        detail: bits.length ? bits.join(" · ") : null,
        onVerb: "Turn on",
        offVerb: "Turn off"
      };
    }

    case "thermostat": {
      const mode = ctx.thermostat?.mode;
      const target = ctx.thermostat?.target_temp_c;
      const label =
        mode === "COOL" ? `Cooling to ${target}°`
        : mode === "HEAT" ? `Heating to ${target}°`
        : mode === "AUTO" ? `Auto · ${target}°`
        : "Off";
      return { stateLabel: label, tone: mode && mode !== "OFF" ? TONE.ACTIVE : TONE.IDLE };
    }

    case "camera":
      return {
        stateLabel: on ? "Live" : "Standby",
        tone: on ? TONE.ACTIVE : TONE.IDLE,
        detail: ctx.camera?.last_snapshot_at
          ? `Updated ${WiseTime.formatRelative(ctx.camera.last_snapshot_at)}`
          : null
      };

    case "sensor": {
      const type = ctx.sensor?.sensor_type;
      const triggered = on;
      const label = {
        motion: triggered ? "Motion detected" : "Clear",
        door_window: triggered ? "Open" : "Closed",
        smoke: triggered ? "Smoke detected" : "Clear",
        gas: triggered ? "Gas detected" : "Clear",
        water_leak: triggered ? "Leak detected" : "Dry"
      }[type] || (triggered ? "Triggered" : "Clear");
      const severe = ["smoke", "gas", "water_leak"].includes(type);
      const bits = [];
      if (ctx.sensor?.current_reading != null && ctx.sensor?.unit) {
        bits.push(`${ctx.sensor.current_reading} ${ctx.sensor.unit}`);
      }
      if (ctx.sensor?.last_triggered_at) {
        bits.push(`Last triggered ${WiseTime.formatRelative(ctx.sensor.last_triggered_at)}`);
      }
      return {
        stateLabel: label,
        tone: !triggered ? TONE.IDLE : severe ? TONE.FAULT : TONE.ATTENTION,
        detail: bits.length ? bits.join(" · ") : null
      };
    }

    case "smart_plug_metered": {
      const bits = [];
      if (ctx.power?.current_watts != null) bits.push(`${ctx.power.current_watts} W now`);
      if (ctx.power?.energy_kwh_total != null) bits.push(`${ctx.power.energy_kwh_total} kWh total`);
      return {
        stateLabel: on ? "Powered" : "Off",
        tone: on ? TONE.ACTIVE : TONE.IDLE,
        detail: bits.length ? bits.join(" · ") : null,
        onVerb: "Turn on",
        offVerb: "Turn off"
      };
    }

    case "outlet":
      return {
        stateLabel: on ? "Powered" : "Off",
        tone: on ? TONE.ACTIVE : TONE.IDLE,
        onVerb: "Turn on",
        offVerb: "Turn off"
      };

    case "appliance": {
      const labels = {
        tv: { on: "Playing", off: "Standby" },
        fridge: { on: "Running", off: "Off" },
        washing_machine: { on: "Washing", off: "Idle", onVerb: "Start", offVerb: "Stop" },
        microwave: { on: "Running", off: "Idle" },
        fan: { on: "Spinning", off: "Off" },
        water_heater: { on: "Heating", off: "Off" }
      }[(device.appliance_type || "").toLowerCase()] || { on: "On", off: "Off" };
      return {
        stateLabel: on ? labels.on : labels.off,
        tone: on ? TONE.ACTIVE : TONE.IDLE,
        onVerb: labels.onVerb || "Turn on",
        offVerb: labels.offVerb || "Turn off"
      };
    }

    default:
      return { stateLabel: on ? "On" : "Off", tone: on ? TONE.ACTIVE : TONE.IDLE };
  }
}

window.WiseVocab = {
  TONE,
  roomsForFloor,
  roomContains,
  deviceTypeLabel,
  deviceDisplay,
  sensorTypeLabel,
  applianceTypeLabel
};
