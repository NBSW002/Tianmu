// Apply the current presentation to previously generated documents as well.
// Original report text, links, tables and chart data are left intact.
export const reportTheme = `
:root{color-scheme:light;--report-paper:#f5f4ed;--report-surface:#faf9f5;--report-ink:#141413;--report-muted:#5e5d59;--report-line:#e8e6dc;--report-accent:#c96442}
body{background:var(--report-paper)!important;color:var(--report-ink)!important;font-family:'PingFang SC','Microsoft YaHei UI','Microsoft YaHei',system-ui,sans-serif!important;line-height:1.75}
header,section,.chart-card{background:var(--report-surface)!important;border-color:var(--report-line)!important;box-shadow:none!important}
header{background:#e8e6dc!important}
h1,h2,h3{font-weight:500!important;color:var(--report-ink)}
a,.eyebrow,.label,.action-preview>span,.peak-callout strong{color:#a55237!important}
a:focus-visible,summary:focus-visible{outline:2px solid #3898ec;outline-offset:3px}
.muted,small,blockquote footer,.metrics span,.chart-note,.chart-empty,.chart-data summary,.topic-coverage,.peak-callout,summary{color:var(--report-muted)!important}
.topic,td,th{border-color:var(--report-line)!important}
.badge{background:#e8e6dc!important;color:#4d4c48!important}
blockquote{background:var(--report-surface)!important;border-left-color:var(--report-accent)!important}
.action{background:#f1e2d6!important}.context{background:#e9eee4!important}.method{background:transparent!important}
.number,.bar-index,.bar-value small,.x-axis,.y-axis{color:#87867f!important}.bar-value{color:#4d4c48!important}
.chart-card,.other-topics{background:var(--report-surface)!important}.bar-track{background:#e8e6dc!important}
.bar-track span,.peak-dot{background:var(--report-accent)!important}.other-bar .bar-track span{background:#b0aea5!important}
.trend-line{stroke:var(--report-accent)!important}.plot-grid{stroke:var(--report-line)!important}
.playback-chart path[fill]:not([fill="none"]){fill:var(--report-accent)!important}
`;

export function themeReportHtml(html) {
  if (/data-report-layout=["']editorial-v2["']/i.test(html)) return html;
  const style = `<style data-echoframe-report-theme>${reportTheme}</style>`;
  return /<\/head\s*>/i.test(html) ? html.replace(/<\/head\s*>/i, style + '</head>') : html + style;
}
