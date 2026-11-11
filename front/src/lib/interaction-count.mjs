function countValue(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(0, Math.floor(number)) : 0;
}

export function compactInteractionCount(value) {
  const number = countValue(value);
  if (number < 10000) return String(number);
  const wan = Number((number / 10000).toFixed(1));
  return wan >= 10000
    ? `${Number((number / 100000000).toFixed(1))}亿`
    : `${wan}万`;
}

export function fullInteractionCount(value) {
  return countValue(value).toLocaleString('zh-CN');
}
