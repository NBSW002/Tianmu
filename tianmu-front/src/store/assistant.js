import { defineStore } from 'pinia'

export const useAssistantStore = defineStore('assistant', {
  state: () => ({ open: false, page: null, runId: '', focus: '', turns: [], history: [], notice: '', commandReplies: [], historyMessages: [], historyCursor: '', historySession: '' }),
  actions: {
    show() { this.open = true },
    reset() { this.$reset() },
  },
})
