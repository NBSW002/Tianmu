<script setup>
/* global defineProps, defineEmits */
import { ref } from 'vue'

const props = defineProps({ label: String, value: Number, min: Number, max: Number, reverse: Boolean })
const emit = defineEmits(['resize', 'dragging', 'reset'])
const activePointer = ref(null)
let lastX = 0, startX = 0, dragged = false
function begin(event) {
  if (event.button !== 0 || activePointer.value != null) return
  event.preventDefault()
  event.currentTarget.focus()
  event.currentTarget.setPointerCapture(event.pointerId)
  activePointer.value = event.pointerId
  startX = lastX = event.clientX
  dragged = false
}
function move(event) {
  if (activePointer.value !== event.pointerId) return
  if (!dragged) {
    if (Math.abs(event.clientX - startX) < 4) return
    dragged = true
    emit('dragging', true)
  }
  emit('resize', (event.clientX - lastX) * (props.reverse ? -1 : 1))
  lastX = event.clientX
}
function finish(event) {
  if (activePointer.value !== event.pointerId) return
  activePointer.value = null
  if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
  dragged = false
  emit('dragging', false)
}
function key(event) {
  const delta = { ArrowLeft: -16, ArrowRight: 16 }[event.key]
  if (delta != null) { event.preventDefault(); emit('resize', delta * (props.reverse ? -1 : 1)) }
  if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault()
    emit('resize', (event.key === 'Home' ? props.min : props.max) - props.value)
  }
}
</script>

<template>
  <div class="trace-divider" :class="{ dragging: activePointer != null }" role="separator" tabindex="0"
    aria-orientation="vertical" :aria-label="label" :aria-valuenow="Math.round(value)" :aria-valuemin="min" :aria-valuemax="Math.round(max)" :aria-valuetext="`${Math.round(value)} 像素`"
    title="拖动调整宽度，双击恢复；方向键微调" @pointerdown="begin" @pointermove="move" @pointerup="finish" @pointercancel="finish" @lostpointercapture="finish" @keydown="key" @dblclick="emit('reset')"><span /></div>
</template>

<style scoped>
.trace-divider { width: 8px; flex: 0 0 8px; position: relative; cursor: col-resize; touch-action: none; display: flex; align-items: center; justify-content: center; background: var(--workbench-paper); z-index: 5; }
.trace-divider span { width: 2px; height: 28px; border-radius: 2px; background: var(--workbench-border); }
.trace-divider:hover, .trace-divider.dragging, .trace-divider:focus-visible { background: var(--workbench-border); outline: none; }
.trace-divider:hover span, .trace-divider.dragging span, .trace-divider:focus-visible span { background: var(--workbench-accent); }
@media(max-width:1100px) { .trace-divider { display: none; } }
</style>
