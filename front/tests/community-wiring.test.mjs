import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { compile, createRenderer, h, nextTick, onUnmounted } from 'vue'
import { createRouter, createMemoryHistory, useRoute } from 'vue-router'

test('follow requests reach the business controller paths', async () => {
  const source = fs.readFileSync(new URL('../src/api/user.js', import.meta.url), 'utf8')
  const calls = []
  const api = new Function('request', source.replace(/^import .*$/mg, '').replace(/export /g, '') + '\nreturn {fetchFocus,fetchUnfocus,fetchFocusList,fetchFansList}')((config) => { calls.push(config); return Promise.resolve() })
  await api.fetchFocus('1', '2'); await api.fetchUnfocus('1', '2')
  await api.fetchFocusList('2'); await api.fetchFansList('2')
  const controller = fs.readFileSync(new URL('../../src/main/java/com/orangecode/tianmu/controller/FollowController.java', import.meta.url), 'utf8')
  for (const call of calls) {
    assert.ok(call.url.startsWith('/user/'))
    const annotation = call.method === 'POST' ? 'PostMapping' : 'GetMapping'
    assert.ok(controller.includes(`@${annotation}("${call.url.slice('/user'.length)}")`), call.url)
  }
  assert.equal(calls[0].data.creatorId, '2')
  assert.equal(calls[2].params.userId, '2')
})

test('changing space owner remounts profile and lists while preserving the assistant', async () => {
  const created = [], removed = []
  const renderer = createRenderer({
    createElement: () => ({ children: [] }), createText: text => ({ text }), createComment: () => ({}),
    insert: (child, parent, anchor) => { child.parent = parent; const index = parent.children.indexOf(anchor); if (index < 0) parent.children.push(child); else parent.children.splice(index, 0, child) }, remove: child => { if (child.parent) child.parent.children = child.parent.children.filter(item => item !== child) },
    setText: () => {}, setElementText: () => {}, parentNode: child => child.parent, nextSibling: child => { const siblings = child.parent?.children || []; return siblings[siblings.indexOf(child) + 1] || null }, patchProp: () => {},
  })
  const Profile = { setup() { const id = useRoute().params.userId; created.push(id); onUnmounted(() => removed.push(id)); return () => h('div') } }
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/user/:userId', component: Profile }] })
  const source = fs.readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  let assistantMounts = 0
  const app = renderer.createApp({ render: compile(source.match(/<template>([\s\S]*?)<\/template>/)[1]) })
  app.component('AssistantHost', { setup() { assistantMounts++; return () => h('aside') } })
  app.use(router)
  await router.push('/user/1'); await router.isReady()
  app.mount({ children: [] })
  await router.push('/user/2'); await nextTick()
  assert.deepEqual(created, ['1', '2'])
  assert.deepEqual(removed, ['1'])
  assert.equal(assistantMounts, 1)
  app.unmount()
})
