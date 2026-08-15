// Human-readable time. Mirrors ui/format/TimeFormat.kt in the Android app so both
// clients describe the same instant the same way.

function parseTimestamp(iso) {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

function formatRelative(iso, now = new Date()) {
  const date = parseTimestamp(iso);
  if (!date) return "—";

  const seconds = Math.floor((now - date) / 1000);
  if (seconds < 0) return "Just now";

  const timeOpts = { hour: "numeric", minute: "2-digit" };
  const isSameDay = (a, b) => a.toDateString() === b.toDateString();
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);

  if (seconds < 45) return "Just now";
  if (seconds < 3600) {
    const minutes = Math.floor(seconds / 60);
    return minutes <= 1 ? "1 minute ago" : `${minutes} minutes ago`;
  }
  if (seconds < 43200) {
    const hours = Math.floor(seconds / 3600);
    return hours <= 1 ? "1 hour ago" : `${hours} hours ago`;
  }
  if (isSameDay(date, now)) {
    return `Today ${date.toLocaleTimeString([], timeOpts)}`;
  }
  if (isSameDay(date, yesterday)) {
    return `Yesterday ${date.toLocaleTimeString([], timeOpts)}`;
  }
  if (seconds < 604800) {
    return date.toLocaleString([], { weekday: "short", ...timeOpts });
  }
  if (date.getFullYear() === now.getFullYear()) {
    return date.toLocaleString([], { day: "numeric", month: "short", ...timeOpts });
  }
  return date.toLocaleString([], {
    day: "numeric",
    month: "short",
    year: "numeric",
    ...timeOpts
  });
}

window.WiseTime = { parseTimestamp, formatRelative };
