import test from 'node:test';
import assert from 'node:assert/strict';
import { compactInteractionCount, fullInteractionCount } from '../src/lib/interaction-count.mjs';

test('formats interaction counts at Chinese unit boundaries without 10000万', () => {
  for (const [input, expected] of [[0,'0'],[9999,'9999'],[10000,'1万'],[26100,'2.6万'],[99999999,'1亿'],[123456789,'1.2亿'],['1000000000','10亿']]) {
    assert.equal(compactInteractionCount(input),expected);
  }
});

test('normalizes missing and invalid counts and preserves full tooltip counts', () => {
  for (const input of [undefined,null,NaN,Infinity,-1,'invalid']) assert.equal(compactInteractionCount(input),'0');
  assert.equal(fullInteractionCount(26100),'26,100');
  assert.equal(fullInteractionCount('123456789'),'123,456,789');
});
