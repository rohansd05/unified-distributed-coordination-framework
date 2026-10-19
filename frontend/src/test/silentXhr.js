import { afterEach, beforeEach, vi } from 'vitest'

/** An XMLHttpRequest that never answers: requests stay pending, so jsdom prints no connection errors. */
class SilentXhr {
  open() {}
  send() {}
  abort() {}
  setRequestHeader() {}
  addEventListener() {}
  removeEventListener() {}
  getAllResponseHeaders() { return '' }
}

/**
 * Call at the top of a test file whose pages render with their real api clients and no backend:
 * no request reaches the network and no AggregateError trace is printed (14.1).
 */
export function silenceXhr() {
  beforeEach(() => vi.stubGlobal('XMLHttpRequest', SilentXhr))
  afterEach(() => vi.unstubAllGlobals())
}
