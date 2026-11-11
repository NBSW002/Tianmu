import test from 'node:test';
import assert from 'node:assert/strict';
import {reportDocumentHtml} from '../src/agent/report-document.mjs';

test('preview and download preserve the same editorial document in standards mode',()=>{
 const html="<!doctype html><html><head><title>报告</title></head><body data-report-layout='editorial-v2'><h1>标题</h1><figure><img src='data:image/png;base64,eA=='></figure></body></html>";
 const result=reportDocumentHtml(html,'https://example.com');
 assert.ok(result.startsWith('<!doctype html><html><head>'));
 assert.equal((result.match(/<!doctype/gi)||[]).length,1);
 assert.ok(result.indexOf('Content-Security-Policy')>result.indexOf('<head>'));
 assert.match(result,/img-src data:/);assert.match(result,/<base href="https:\/\/example.com\/">/);
 assert.ok(result.includes("<body data-report-layout='editorial-v2'><h1>标题</h1>"));
 assert.ok(!result.includes('data-echoframe-report-theme'));
});
test('legacy HTML keeps readable content and receives its compatible theme',()=>{
 const result=reportDocumentHtml('<html><meta charset="utf-8"><body>历史原话</body></html>','https://example.com');
 assert.ok(result.startsWith('<!doctype html><html><head>'));
 assert.match(result,/data-echoframe-report-theme/);assert.match(result,/历史原话/);
});
