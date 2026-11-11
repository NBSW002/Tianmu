<script setup>
/* global defineProps */
import { computed, ref } from 'vue'
import TraceIcon from './TraceIcon.vue'

const props = defineProps({ title: String, subtitle: String, value: { default: null }, state: String, reference: String })
const copied = ref(false)
const copyError = ref(false)
const protectedPayload = computed(() => props.value?.memoryProtected === true
  && /^[0-9a-f-]{36}$/i.test(props.value?.runId || '')
  && props.value.details === 'Read authorized memory references from the run memory endpoint'
  && Object.keys(props.value).sort().join(',') === 'details,memoryProtected,runId')
const stateLabels = { DISABLED: '已关闭采集', CAPTURE_FAILED: '采集失败', LIMIT_REACHED: '已达采集上限', NOT_CAPTURED: '未采集', UNAVAILABLE: '内容不可用', TRUNCATED: '内容已截断', SOURCE_PROTECTED: '旧记录未保留正文', SOURCE_GUARDED: '查看时校验来源权限', NOT_APPLICABLE: '本节点不适用' }
const content = computed(() => typeof props.value === 'string' ? props.value : JSON.stringify(props.value, null, 2))
const tokens = computed(() => {
  if (props.value == null) return []
  return content.value.split(/("(?:\\.|[^"\\])*"\s*:|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|\b\d+(?:\.\d+)?\b)/g).filter(Boolean).map((text) => ({
    text, type: /^".*:\s*$/.test(text) ? 'key' : /^"/.test(text) ? 'string' : /^(true|false|null|\d)/.test(text) ? 'literal' : 'plain',
  }))
})
async function copy() {
  try {
    await navigator.clipboard.writeText(content.value)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1800)
  } catch { copyError.value = true; setTimeout(() => { copyError.value = false }, 2500) }
}
</script>

<template>
  <section class="trace-code">
    <div class="code-heading">
      <div class="code-title"><h3>{{ title }}</h3><p v-if="subtitle" class="code-subtitle">{{ subtitle }}</p></div>
      <div class="code-actions">
        <span v-if="value != null && !protectedPayload" class="code-format">{{ typeof value === 'string' ? 'TEXT' : 'JSON' }}</span>
        <span v-if="state === 'TRUNCATED'" class="code-state">已截断</span>
        <button v-if="value != null && !protectedPayload" type="button" :aria-label="`复制${title}`" :title="copyError ? '复制失败，请手动选择文本' : copied ? '已复制' : '复制内容'" @click="copy"><TraceIcon :name="copied ? 'check' : 'copy'" :size="14" /></button>
      </div>
    </div>
    <div v-if="protectedPayload" class="code-protected" role="note"><TraceIcon name="info" :size="16" /><div><strong>内容已保护</strong><p>为保护记忆来源，这份详情仅保留运行引用。</p></div></div>
    <pre v-else-if="value != null" tabindex="0" role="region" :aria-label="`${title}内容`"><code><span v-for="(token, index) in tokens" :key="index" :class="`syntax-${token.type}`">{{ token.text }}</span></code></pre>
    <div v-else class="code-empty">
      <TraceIcon name="info" :size="17" />
      <p>{{ reference ? '正文未载入' : '暂无内容' }}<small>{{ stateLabels[state] || '未采集' }}</small></p>
    </div>
  </section>
</template>

<style scoped>
.trace-code { margin-top: 22px; }
.code-heading, .code-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
h3 { margin: 0; font-size: 12px; font-weight: 500; color: var(--workbench-ink); }
.code-format { font: 12px var(--trace-mono); color: var(--workbench-accent); }
.code-state { font-size: 12px; color: var(--workbench-ink); }
button { border: 0; background: none; color: var(--workbench-muted); padding: 3px; cursor: pointer; display: flex; }
button:hover { color: var(--workbench-accent); }
button:focus-visible { outline: 2px solid var(--workbench-accent); outline-offset: 3px; }
pre { margin: 10px 0 0; background: var(--workbench-sidebar); border: 1px solid var(--workbench-border); border-radius: 8px; padding: 15px 14px; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.9 var(--trace-mono); tab-size: 2; color: var(--workbench-ink); }
pre:focus-visible { outline: 2px solid var(--workbench-accent); }
.syntax-key { color: var(--workbench-accent); }.syntax-string { color: var(--workbench-success); }.syntax-literal { color: var(--workbench-ink); }
.code-empty { display: flex; gap: 8px; margin-top: 10px; padding: 14px 12px; background: var(--workbench-sidebar); border: 1px dashed var(--workbench-border); border-radius: 8px; color: var(--workbench-muted); }
.code-empty svg { flex-shrink: 0; margin-top: 2px; }.code-empty p { margin: 0; font-size: 12px; line-height: 1.8; }.code-empty small { display: block; font: 12px/1.8 var(--trace-mono); margin-top: 4px; }

.syntax-key {color:var(--workbench-code-key);}.syntax-string{color:var(--workbench-success);}.syntax-literal,.code-state{color:var(--workbench-warning);}
pre{max-height:360px;overflow:auto;font-size:12px;line-height:1.75;background:var(--workbench-paper);border-color:var(--workbench-border);color:var(--workbench-ink);font-variant-numeric:tabular-nums;}
.code-protected { display: flex; align-items: flex-start; gap: 8px; margin-top: 10px; padding: 12px; border: 1px solid var(--workbench-border); border-radius: 8px; background: var(--workbench-paper); color: var(--workbench-muted); }
.code-protected > svg { flex-shrink: 0; margin-top: 2px; }
.code-protected strong { font-size: 12px; font-weight: 500; color: var(--workbench-ink); }
.code-protected p { margin: 3px 0 0; font-size: 11px; line-height: 1.8; }

.trace-code {min-width:0;margin:0 0 14px;border:1px solid var(--workbench-border);border-radius:9px;background:var(--workbench-paper);overflow:hidden;}
.code-heading {padding:9px 11px;align-items:flex-start;border-bottom:1px solid var(--workbench-border-subtle);background:var(--workbench-raised);}
.code-title {min-width:0;}
.code-actions {flex-shrink:0;}
.code-subtitle {margin:4px 0 0;font:11px/1.5 var(--workbench-mono);color:var(--workbench-muted);overflow-wrap:anywhere;}
pre {margin:0;padding:11px;max-height:clamp(160px,26dvh,280px);min-height:64px;box-sizing:border-box;border:0;border-radius:0;overflow:auto;overscroll-behavior:contain;scrollbar-width:thin;scrollbar-color:var(--workbench-ring) transparent;}
pre:focus-visible {outline:2px solid var(--workbench-focus);outline-offset:-2px;}
.code-empty,.code-protected {margin:0;padding:12px 11px;border:0;border-radius:0;background:transparent;}
.code-empty small {margin-top:2px;}
pre {line-height:1.85;letter-spacing:0;}
.code-heading {line-height:1.5;letter-spacing:.01em;}
</style>
