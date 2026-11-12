import test from 'node:test'
import assert from 'node:assert/strict'
import { donutArcs } from '../src/trace/trace-donut.mjs'

test('round caps and gaps remain within their original sectors', () => {
  const arcs = donutArcs([{value:30,color:'a'},{value:70,color:'b'}])
  const halfCap = 9 / (4 * Math.PI * 42) * 100
  assert.ok(arcs.every(arc => arc.rounded))
  assert.ok(arcs[0].start - halfCap >= 0)
  assert.ok(arcs[0].start + arcs[0].length + halfCap < 30)
  assert.ok(arcs[1].start - halfCap > 30)
  assert.ok(arcs[1].start + arcs[1].length + halfCap < 100)
})
test('very small sectors remain visible without an oversized round cap', () => {
  const arcs = donutArcs([{value:0.1,color:'a'},{value:99.9,color:'b'}])
  assert.equal(arcs[0].rounded, false)
  assert.ok(arcs[0].length > 0 && arcs[0].length < 0.1)
})
test('single non-zero sector is a full ring, and missing values are ignored', () => {
  assert.deepEqual(donutArcs([{value:0},{value:null},{value:7,color:'a'}]), [{value:7,color:'a',full:true}])
  assert.deepEqual(donutArcs([{value:0},{value:-1}]), [])
})
