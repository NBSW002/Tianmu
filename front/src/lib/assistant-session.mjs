import { watch } from 'vue'
import { principalKey } from './auth-session.mjs'

// Credential rotation within one identity preserves the current conversation.
export function watchAssistantIdentity(users, assistant, onReset = () => {}) {
  return watch(() => principalKey(users.user), () => {
    const open = assistant.open
    assistant.reset()
    assistant.open = open
    onReset()
  }, { flush: 'sync' })
}
