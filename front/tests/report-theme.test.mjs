import test from 'node:test';
import assert from 'node:assert/strict';
import {themeReportHtml} from '../src/agent/report-theme.mjs';

test('editorial documents retain their own layout when opened or downloaded', () => {
  const html="<!doctype html><html><head><style>header{background:transparent}</style></head><body data-report-layout='editorial-v2'><nav>目录</nav><main>报告</main></body></html>";
  assert.equal(themeReportHtml(html),html);
});
test('historical documents still receive the compatible report theme', () => {
  const result=themeReportHtml('<html><head></head><body>历史原话</body></html>');
  assert.match(result,/data-echoframe-report-theme/);
  assert.match(result,/历史原话/);
});
