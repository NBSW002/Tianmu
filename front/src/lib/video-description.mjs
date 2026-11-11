function escapeHtml(value) {
  return value.replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
}

export function renderVideoDescription(value) {
  const text = String(value ?? '');
  const pattern = /https?:\/\/[^\s<>"'，。！？；、（）【】]+/gi;
  let html = '', position = 0;
  for (const match of text.matchAll(pattern)) {
    html += escapeHtml(text.slice(position, match.index));
    const address = match[0].replace(/[),.;!?]+$/, '');
    try {
      const url = new URL(address);
      const bilibili = url.hostname === 'b23.tv' || url.hostname === 'bilibili.com' || url.hostname.endsWith('.bilibili.com');
      const label = bilibili ? '哔哩哔哩原视频 ↗' : `${url.hostname.replace(/^www\./, '')} ↗`;
      html += `<a class="video-source-link" href="${escapeHtml(address)}" title="${escapeHtml(address)}" target="_blank" rel="noopener noreferrer">${escapeHtml(label)}</a>`;
      html += escapeHtml(match[0].slice(address.length));
    } catch {
      html += escapeHtml(match[0]);
    }
    position = match.index + match[0].length;
  }
  return html + escapeHtml(text.slice(position));
}
