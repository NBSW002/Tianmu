export function playbackTime(value) {
  const number = Number(value);
  const seconds = Number.isFinite(number) ? Math.max(0, Math.floor(number)) : 0;
  const minutes = Math.floor(seconds / 60);
  const tail = String(seconds % 60).padStart(2, "0");
  return minutes >= 60
    ? Math.floor(minutes / 60) +
        ":" +
        String(minutes % 60).padStart(2, "0") +
        ":" +
        tail
    : minutes + ":" + tail;
}

export function playbackProgress(position, duration) {
  if (!Number.isFinite(Number(duration)) || Number(duration) <= 0) return null;
  return Math.min(
    100,
    (Math.max(0, Number(position) || 0) / Number(duration)) * 100
  );
}

function dateKey(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
}

export function historyDay(value, now = Date.now()) {
  const day = dateKey(value);
  if (!day) return "更早";
  if (day === dateKey(now)) return "今天";
  if (day === dateKey(now - 86400000)) return "昨天";
  return `${day.slice(0, 4)}年${Number(day.slice(5, 7))}月${Number(
    day.slice(8)
  )}日`;
}

export function historyClock(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleTimeString("zh-CN", {
    timeZone: "Asia/Shanghai",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

export function groupHistory(items, now = Date.now()) {
  const groups = [];
  for (const item of items) {
    const label = historyDay(item.watchedAt, now);
    let group = groups.find((value) => value.label === label);
    if (!group) {
      group = { label, items: [] };
      groups.push(group);
    }
    group.items.push(item);
  }
  return groups;
}
