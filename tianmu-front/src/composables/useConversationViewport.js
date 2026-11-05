import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

// Adapted from open-claude-ui's scroll pinning and edge-fade interaction.
// User input takes priority over token arrivals; see THIRD_PARTY_NOTICES.md.
export function useConversationViewport(container, content) {
  const pinned = ref(true), showJump = ref(false), topFade = ref(0), bottomFade = ref(0)
  let observer, frame, cooldown, lastInput = -Infinity, previousTop = 0
  function measure() {
    const el = container.value
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    topFade.value = Math.min(20, Math.max(0, el.scrollTop))
    bottomFade.value = Math.min(20, Math.max(0, distance))
    showJump.value = distance > 120
  }
  function intent(event) {
    if (event.type === 'keydown' && !['ArrowUp','ArrowDown','PageUp','PageDown','Home','End',' '].includes(event.key)) return
    if (event.type === 'keydown' && event.target.closest('button,input,textarea,select,summary')) return
    lastInput = performance.now()
    pinned.value = false
    clearTimeout(cooldown)
    cooldown = setTimeout(() => {
      const el = container.value
      if (el) pinned.value = el.scrollHeight - el.scrollTop - el.clientHeight <= 40
    }, 160)
  }
  function scroll() {
    const el = container.value
    if (!el) return
    // Also covers scrollbar drags and assistive scrolling, without a wheel event.
    if (el.scrollTop < previousTop - 1) { pinned.value = false; lastInput = performance.now() }
    previousTop = el.scrollTop
    if (performance.now() - lastInput > 150) pinned.value = el.scrollHeight - el.scrollTop - el.clientHeight <= 40
    measure()
  }
  function jumpToLatest() {
    const el = container.value
    if (!el) return
    pinned.value = true
    lastInput = -Infinity
    el.scrollTo({ top: el.scrollHeight, behavior: 'auto' })
    previousTop = el.scrollTop
    measure()
  }
  function resized() {
    cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => {
      if (pinned.value && performance.now() - lastInput > 150) jumpToLatest()
      else measure()
    })
  }
  onMounted(() => {
    observer = new ResizeObserver(resized)
    if (container.value) observer.observe(container.value)
    if (content.value) observer.observe(content.value)
    measure()
  })
  onBeforeUnmount(() => { observer?.disconnect(); cancelAnimationFrame(frame); clearTimeout(cooldown) })
  const fadeStyle = computed(() => ({ '--conversation-fade-top': `${topFade.value}px`, '--conversation-fade-bottom': `${bottomFade.value}px` }))
  return { showJump, fadeStyle, scroll, intent, jumpToLatest }
}
