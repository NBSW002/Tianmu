import test from 'node:test'
import assert from 'node:assert/strict'
import { restoreDraftItems } from '../src/agent/draft-selection.mjs'

test('new drafts require selection, while an uncertain publication keeps its frozen payload selected', () => {
  const draft = { runId: 'run-1', state: 'DRAFT', items: [{ id: 'a', playbackMs: 2500 }, { id: 'b', playbackMs: null }] }
  const initial = restoreDraftItems(draft, null, [])
  assert.deepEqual(initial.map(item => item.selected), [false, false])
  assert.equal(initial[0].seconds, 2.5)
  initial[1].selected = true
  assert.deepEqual(restoreDraftItems(draft, draft, initial).map(item => item.selected), [false, true])
  const frozen = { ...draft, state: 'PUBLISHING', items: [draft.items[1]] }
  assert.deepEqual(restoreDraftItems(frozen, draft, initial).map(item => item.selected), [true])
  assert.deepEqual(restoreDraftItems({ ...draft, runId: 'run-2' }, draft, initial).map(item => item.selected), [false, false])
})
