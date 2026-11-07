

import { terminal, progressLabel, reportPath } from './protocol.mjs';
import { notifyMemoryChanged } from '../lib/memory-events.mjs';

/** Owns subscription/polling lifecycle; UI state is supplied by the conversation view. */
export function createRunMonitor({ getAgentRun, readAgentEvents, applySnapshot, notice }) {
let monitor, pollTimer;
function stop() { monitor?.abort(); clearTimeout(pollTimer); }
function start(turn) {
  stop();
  const controller = new AbortController();
  monitor = controller;
  const signal = controller.signal;
  let sequence = turn.eventSequence || 0;
  let handled = sequence;
  const refresh = async () => {
    const snapshot = await getAgentRun(turn.runId, signal);
    if (!signal.aborted) {
      notice.value = '';
      applySnapshot(turn, snapshot);
    }
  };
  const fatal = (error) => error.status === 401 || error.status === 403 || error.status === 404;
  const poll = async () => {
    try {
      await refresh();
      if (signal.aborted) return;
      if (terminal(turn.snapshot?.status)) {
        controller.abort();
        return;
      }
    } catch (error) {
      if (signal.aborted) return;
      notice.value = error.message;
      if (fatal(error)) {
        controller.abort();
        return;
      }
    }
    if (!signal.aborted) pollTimer = setTimeout(poll, 2500);
  };
  pollTimer = setTimeout(poll, 2500);
  const stream = async () => {
    while (!signal.aborted) {
      try {
        await readAgentEvents(turn.runId, sequence, signal, (event) => {
          if (signal.aborted) return;
          const payload = event.payload,
            seq = Number(payload.sequence || event.id);
          if (!Number.isSafeInteger(seq) || seq < 1) return;
          sequence = Math.max(sequence, seq);
          if (seq <= handled) return;
          handled = seq;
          turn.eventSequence = seq;
          const data = payload.data || {};
          // Completed model remarks remain visible when the next tool/model round starts.
          if (event.type === 'TEXT_END' && turn.streamingText) {
            turn.streamingNotes = [...(turn.streamingNotes || []), { sequence: seq, text: turn.streamingText }];
            turn.streamingText = '';
          }
          if (event.type === 'TEXT_DELTA' && typeof data.text === 'string') turn.streamingText = (turn.streamingText || '') + data.text;
          if (event.type === 'MODEL_SELECTED') turn.modelLabel = data.label || data.model;
          if (event.type === 'PROGRESS') {
            if (data.stage === 'tool_completed' && ['remember_memory','forget_memory'].includes(data.tool)) notifyMemoryChanged('agent');
            const label = progressLabel(data);
            if (!label) return;
            const next = {
              sequence: seq,
              label,
              at: payload.at,
              waitingAuth: data.stage === 'analysis_waiting_auth',
              analysisCalls: data.stage === 'feedback_analysis' && Number.isSafeInteger(data.model_calls) ? data.model_calls : undefined,
            };
            const previous = turn.events[turn.events.length - 1];
            if (previous?.label === label) Object.assign(previous, { analysisCalls: next.analysisCalls ?? previous.analysisCalls });
            else turn.events.push(next);
            turn.events = turn.events.slice(-60);
          }
          if (event.type === 'ARTIFACT_READY')
            turn.reportPath = reportPath(turn.runId, data.url) || turn.reportPath;
        });
        if (signal.aborted) return;
        await refresh();
        if (signal.aborted) return;
        if (terminal(turn.snapshot?.status)) {
          clearTimeout(pollTimer);
          return;
        }
      } catch (error) {
        if (signal.aborted) return;
        notice.value = error.message;
        if (fatal(error)) {
          controller.abort();
          clearTimeout(pollTimer);
          return;
        }
        if (error.code === 'EVENT_CURSOR_EXPIRED' || error.code === 'INVALID_CURSOR') sequence = 0;
      }
      await new Promise((resolve) => {
        let timer;
        const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
        timer = setTimeout(finish, 1500);
        if (signal.aborted) finish(); else signal.addEventListener('abort', finish, { once: true });
      });

    }
  };
  return stream();
}
return { start, stop, get signal() { return monitor?.signal; } };
}
