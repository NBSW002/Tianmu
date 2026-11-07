import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

test('initial reaction read blocks a write and a late previous-item response cannot overwrite the current item', async () => {
  const component = fs.readFileSync(new URL('../src/components/Comment/FeedbackLikeButton.vue', import.meta.url), 'utf8')
  const source = component.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[^\n]*\n/gm, '')
  const pending = [], props = { itemId: '1', source: 'COMMENT' }, users = { isLogin: true, user: { userId: 'alice' } }
  let refresh
  const view = new Function('ref', 'watch', 'onBeforeUnmount', 'defineProps', 'useUserStore', 'request', 'ElMessage', source + '\nreturn {toggle,likes,liked,busy}')(
    value => ({ value }), (get, callback) => { refresh = callback }, () => {}, () => props, () => users,
    args => new Promise(resolve => pending.push({ args, resolve })), { warning() {}, error() {} })
  const first = refresh(); assert.equal(view.busy.value, true)
  await view.toggle(); assert.equal(pending.length, 1)
  props.itemId = '2'; const second = refresh()
  pending[1].resolve({ data: { likes: 7, liked: true } }); await second
  pending[0].resolve({ data: { likes: 0, liked: false } }); await first
  assert.equal(view.likes.value, 7); assert.equal(view.liked.value, true); assert.equal(view.busy.value, false)
  const write = view.toggle(); assert.equal(pending[2].args.data.liked, false)
  pending[2].resolve({ data: { likes: 6, liked: false } }); await write
  assert.equal(view.likes.value, 6); assert.equal(view.liked.value, false)
})
