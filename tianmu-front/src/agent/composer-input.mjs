/** Composition-confirmation Enter must never submit a Chinese/Japanese/Korean draft. */
export function createCompositionGuard(now = () => performance.now()) {
  let composing = false, endedAt = -Infinity;
  return {
    start() { composing = true; endedAt = -Infinity; },
    end() { composing = false; endedAt = now(); },
    reset() { composing = false; endedAt = -Infinity; },
    blocksEnter(event) {
      if (composing || event.isComposing || event.keyCode === 229) return true;
      if (now() - endedAt <= 500) {
        endedAt = -Infinity;
        event.preventDefault();
        return true;
      }
      return false;
    },
  };
}
