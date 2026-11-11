import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const source = fs.readFileSync(new URL('../src/lib/utils.js', import.meta.url), 'utf8');
const { highlightKeyword } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

test('search treats metacharacters as literal characters and preserves character highlighting', () => {
  for (const character of ['[', ']', '\\', '(', ')', '*', '+', '.', '?', '^', '$', '{', '}', '|', '😀']) {
    assert.equal(highlightKeyword(character, character), `<em class="suggest_high_light">${character}</em>`);
  }
  assert.equal(highlightKeyword('猫狗', '猫和狗'), '<em class="suggest_high_light">猫</em>和<em class="suggest_high_light">狗</em>');
});

test('search escapes both matched and unmatched user markup', () => {
  const result = highlightKeyword('猫', '猫<img src=x onerror=alert(1)> & "');
  assert.ok(!result.includes('<img'));
  assert.ok(result.includes('&lt;img'));
  assert.ok(result.includes('&amp; &quot;'));
  assert.equal(highlightKeyword('<', '<'), '<em class="suggest_high_light">&lt;</em>');
  assert.equal(highlightKeyword(undefined, '<img>'), '&lt;img&gt;');
  assert.equal(highlightKeyword('x', null), '');
});
