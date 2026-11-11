import test from 'node:test';
import assert from 'node:assert/strict';
import { renderVideoDescription } from '../src/lib/video-description.mjs';

test('keeps source destinations and punctuation while shortening visible links',()=>{
  const html=renderVideoDescription('来源：https://www.bilibili.com/video/BV123。\nUP主：作者');
  assert.ok(html.includes('href="https://www.bilibili.com/video/BV123"'));
  assert.ok(html.includes('哔哩哔哩原视频 ↗</a>。\nUP主：作者'));
  assert.ok(html.includes('rel="noopener noreferrer"'));
  assert.ok(renderVideoDescription('https://example.com/watch?a=1&b=2').includes('a=1&amp;b=2'));
});

test('treats description markup as text and does not trust lookalike source domains',()=>{
  const html=renderVideoDescription('<img src=x onerror=alert(1)> javascript:alert(1) https://bilibili.com.example.org/watch');
  assert.ok(html.includes('&lt;img'));
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('哔哩哔哩原视频'));
  assert.ok(html.includes('bilibili.com.example.org ↗'));
  assert.equal(renderVideoDescription(null),'');
});
