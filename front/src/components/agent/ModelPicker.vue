<script setup>
/* global defineProps, defineEmits */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import TraceIcon from '@/components/trace/TraceIcon.vue'
const props = defineProps({ modelValue: String, models: { type: Array, default: () => [] }, disabled: Boolean, refreshing: Boolean })
const emit = defineEmits(['update:modelValue', 'refresh'])
const root = ref(null), trigger = ref(null), menu = ref(null), open = ref(false)
const label = computed(() => props.models.find(model => model.id === props.modelValue)?.label || '系统默认')
const choices = computed(() => [{ id: '', label: '系统默认', model: '使用服务端默认配置' }, ...props.models])
async function reveal(last = false) {
  if (props.disabled) return
  open.value = true
  await nextTick()
  const buttons = menu.value?.querySelectorAll('[role="option"]')
  const selected = choices.value.findIndex(model => model.id === props.modelValue)
  buttons?.[last ? buttons.length - 1 : Math.max(0, selected)]?.focus()
}
function close(restore = false) { open.value = false; if (restore) nextTick(() => trigger.value?.focus()) }
function select(id) { emit('update:modelValue', id); close(true) }
function keyboard(event) {
  if (event.key === 'Escape' && open.value) { event.preventDefault(); event.stopPropagation(); close(true) }
  if (event.key === 'Tab') close()
  if (['ArrowDown','ArrowUp','Home','End'].includes(event.key)) {
    event.preventDefault()
    const options = [...menu.value.querySelectorAll('[role="option"]')]
    const current = options.indexOf(document.activeElement)
    const index = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length
    options[index]?.focus()
  }
}
function outside(event) { if (!root.value?.contains(event.target)) close() }
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
watch(() => props.disabled, value => { if (value) close() })
</script>
<template>
  <div ref="root" class="model-picker" @keydown="keyboard">
    <button ref="trigger" type="button" class="model-select" :disabled="disabled" aria-label="选择模型" aria-haspopup="listbox" :aria-expanded="open" @click="open ? close() : reveal()" @keydown.down.prevent.stop="reveal()" @keydown.up.prevent.stop="reveal(true)">
      <span>{{ label }}</span><TraceIcon name="down" :size="14" :class="{ rotated: open }" />
    </button>
    <Transition name="workbench-popover">
      <div v-if="open" ref="menu" class="model-menu">
        <div class="model-menu-heading"><span>选择模型</span><button type="button" aria-label="刷新模型列表" :disabled="refreshing" @click="emit('refresh')">↻</button></div>
        <div class="model-options" role="listbox" aria-label="可用模型">
        <button v-for="model in choices" :key="model.id" type="button" role="option" :aria-selected="modelValue === model.id" @click="select(model.id)">
          <span class="model-copy"><strong>{{ model.label }}</strong><small>{{ model.model || '可用对话模型' }}</small></span><TraceIcon v-if="modelValue === model.id" name="check" :size="16" />
        </button>
        </div>
        <p>用于下一条消息，当前回答保持原模型</p>
      </div>
    </Transition>
  </div>
</template>
<style scoped>
.model-picker{position:relative;min-width:0;font-family:var(--workbench-ui);}
.model-select{display:flex;align-items:center;gap:5px;max-width:160px;min-height:32px;padding:5px 6px;border:0;border-radius:7px;background:transparent;color:var(--workbench-ink);font-size:12px;cursor:pointer;}
.model-select>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
.model-select svg{flex:none;transition:transform var(--workbench-motion-fast) var(--workbench-ease);}
.model-select svg.rotated{transform:rotate(180deg);}
.model-select:hover{background:var(--workbench-sidebar);}
.model-select:disabled{opacity:.5;cursor:default;}
.model-menu{position:absolute;bottom:calc(100% + 12px);right:0;z-index:10;width:min(280px,calc(100vw - 112px));padding:7px;background:var(--workbench-raised);border-radius:12px;box-shadow:var(--workbench-popover-shadow);transform-origin:bottom right;max-height:min(420px,55dvh);overflow:auto;overscroll-behavior:contain;}
.model-menu-heading{display:flex;align-items:center;justify-content:space-between;padding:3px 8px 7px;color:var(--workbench-muted);font-size:11px;}
.model-menu-heading button{border:0;background:transparent;border-radius:6px;padding:3px 6px;color:inherit;cursor:pointer;}
.model-options>[role=option]{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;border:0;border-radius:8px;padding:9px 10px;text-align:left;background:transparent;color:var(--workbench-ink);cursor:pointer;}
.model-options>[role=option]:hover,.model-options>[aria-selected=true]{background:var(--workbench-sidebar);}
.model-copy{min-width:0;}.model-copy strong{display:block;font-size:13px;font-weight:500;}.model-copy small{display:block;margin-top:2px;color:var(--workbench-muted);font-size:11px;overflow-wrap:anywhere;}
.model-menu>p{margin:6px 8px 3px;padding-top:9px;border-top:1px solid var(--workbench-border);font-size:11px;line-height:1.6;color:var(--workbench-muted);}
@media(pointer:coarse){.model-select,.model-options>[role=option]{min-height:44px;}}
@container(max-width:300px){.model-select{max-width:120px;font-size:11px;}}
</style>
