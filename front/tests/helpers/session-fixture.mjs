import fs from 'node:fs'
import { createPinia, setActivePinia } from 'pinia'
import { computed, watch } from 'vue'
import axios from 'axios'
import * as protocol from '../../src/agent/protocol.mjs'
import { readRunSnapshot } from '../../src/agent/run-clock.mjs'
import { shouldExpireSession } from '../../src/lib/auth-session.mjs'
import { watchAssistantIdentity } from '../../src/lib/assistant-session.mjs'

const source = url => fs.readFileSync(url, 'utf8').replace(/^import[^\n]*\r?\n/gm, '').replace(/^export /gm, '')

// Execute the actual stores and HTTP boundary with controlled storage/network timing.
export function sessionFixture(baseline = null) {
  const previousStorage = globalThis.localStorage, previousFetch = globalThis.fetch
  const values = new Map()
  globalThis.localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  setActivePinia(createPinia())
  const auth = new Function('TIANMU_USER', source(new URL('../../src/lib/auth.js', import.meta.url)) + '\nreturn {readStoredUser,hasAuthToken}')( 'TIANMU_USER')
  const userSource = baseline ? new URL('user.js', baseline) : new URL('../../src/store/user.js', import.meta.url)
  const useUsers = new Function('defineStore', 'fetchFocus', 'fetchUnfocus', 'ElMessage', 'TIANMU_USER', 'TIANMU_LOGIN', 'hasAuthToken', 'readStoredUser', 'shouldExpireSession', source(userSource) + '\nreturn useUserStore')(
    defineStore, () => {}, () => {}, { success() {} }, 'TIANMU_USER', 'TIANMU_LOGIN', auth.hasAuthToken, auth.readStoredUser, shouldExpireSession)
  const useAssistant = new Function('defineStore', source(new URL('../../src/store/assistant.js', import.meta.url)) + '\nreturn useAssistantStore')(defineStore)
  const users = useUsers(), assistant = useAssistant()
  users.login({ userId: 'alice', token: 'fixture-old-token' })
  assistant.open = true; assistant.focus = '尚未发送的内容'; assistant.turns = [{ message: '私有正文' }]
  let stop
  if (baseline) {
    const host = fs.readFileSync(new URL('AssistantHost.vue', baseline), 'utf8')
    const callback = host.match(/watch\(accountKey,\s*(\(\) => \{[^}]+\})/)[1]
    const accountKey = computed(() => `${users.user.userId || ''}:${users.user.token || ''}`)
    stop = watch(accountKey, new Function('assistant', 'mounted', `return (${callback})`)(assistant, { value: true }), { flush: 'sync' })
  } else stop = watchAssistantIdentity(users, assistant)
  const agentSource = baseline ? new URL('agent.js', baseline) : new URL('../../src/api/agent.js', import.meta.url)
  const api = new Function('readStoredUser', 'useUserStore', 'reportPath', 'sseDecoder', 'validRunId', 'readRunSnapshot', 'notifyMemoryChanged', source(agentSource) + '\nreturn {createAgentRun}')(auth.readStoredUser, useUsers, protocol.reportPath, protocol.sseDecoder, protocol.validRunId, readRunSnapshot, () => {})
  const business = new Function('axios', 'useUserStore', source(new URL('../../src/lib/request.js', import.meta.url)).replace(/default request\s*;?/, '') + '\nreturn request')(axios, useUsers)
  return { users, assistant, api, business, close() { stop(); globalThis.localStorage = previousStorage; globalThis.fetch = previousFetch } }
}

// Keep the test loader independent of application aliases and DOM-only libraries.
import { defineStore } from 'pinia'
