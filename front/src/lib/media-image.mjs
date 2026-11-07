const widths = new Set([320, 640, 960]);
const imagePath = /^\/api\/media\/[A-Za-z0-9_-]{8,128}\.(?:png|jpe?g|gif|webp|avif)$/i;
export function imageVariantUrl(value, width) {
  if (!widths.has(width) || typeof value !== 'string') return value;
  const [path, query = ''] = value.split('?');
  if (!imagePath.test(path)) return value;
  const params = new URLSearchParams(query);
  params.set('w', width);
  return `${path}?${params}`;
}
