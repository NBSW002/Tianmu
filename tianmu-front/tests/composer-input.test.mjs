import test from 'node:test';
import assert from 'node:assert/strict';
import { createCompositionGuard } from '../src/agent/composer-input.mjs';

test('IME candidate confirmation and its trailing Enter cannot send a message', () => {
  let time = 1000, prevented = 0;
  const guard = createCompositionGuard(() => time);
  const event = { preventDefault: () => prevented++ };
  guard.start(); assert.equal(guard.blocksEnter(event), true); assert.equal(prevented, 0);
  guard.end(); time += 30;
  assert.equal(guard.blocksEnter(event), true); assert.equal(prevented, 1);
  time += 20; assert.equal(guard.blocksEnter(event), false);
});

test('ordinary Enter, expired guard and separate composition sessions remain usable', () => {
  let time = 1000;
  const guard = createCompositionGuard(() => time), event = { preventDefault() {} };
  assert.equal(guard.blocksEnter(event), false);
  assert.equal(guard.blocksEnter({ ...event, isComposing: true }), true);
  assert.equal(guard.blocksEnter({ ...event, keyCode: 229 }), true);
  guard.start(); guard.end(); time += 501; assert.equal(guard.blocksEnter(event), false);
  guard.start(); guard.end(); guard.reset(); assert.equal(guard.blocksEnter(event), false);
});
