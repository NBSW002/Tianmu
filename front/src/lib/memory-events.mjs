// Invalidation signals carry no user ID, token, memory IDs or body text.
const listeners = new Set()
const storageKey = 'echoframe.memory.changed.v1'
let channel = null
let listening = false
function fire(source) { for (const listener of [...listeners]) { try { listener(source) } catch { /* One view cannot block other invalidations. */ } } }
function storageChanged(event) { if (event.key === storageKey && event.newValue) fire('remote') }
function connect() {
  if (listening || typeof window === 'undefined') return
  listening = true
  window.addEventListener('storage', storageChanged)
  try { if (typeof window.BroadcastChannel === 'function') { channel = new window.BroadcastChannel(storageKey); channel.onmessage = event => { if (event.data === 'changed') fire('remote') } } } catch { channel = null }
}
export function onMemoryChanged(listener) {
  listeners.add(listener); connect()
  return () => {
    listeners.delete(listener)
    if (!listeners.size && listening) { channel?.close(); channel = null; window.removeEventListener('storage', storageChanged); listening = false }
  }
}
export function notifyMemoryChanged(origin = 'local') {
  fire(origin === 'agent' ? 'agent' : 'local')
  if (typeof window === 'undefined') return
  let sent = false
  try { if (channel) { channel.postMessage('changed'); sent = true } else if (typeof window.BroadcastChannel === 'function') { const sender = new window.BroadcastChannel(storageKey); sender.postMessage('changed'); sender.close(); sent = true } } catch { /* Fall back to a storage signal. */ }
  if (!sent) try { window.localStorage.setItem(storageKey, `${Date.now()}:${Math.random()}`) } catch { /* Local views have already cleared their private copies. */ }
}
