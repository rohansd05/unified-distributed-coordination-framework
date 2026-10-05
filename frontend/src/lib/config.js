/**
 * Application environment configuration.
 *
 * Reads VITE_API_BASE_URL and VITE_WS_URL without hard-coded fallbacks.
 * Validates protocol schemes (http(s):// for API and ws(s):// for WebSocket).
 */

const HTTP_PROTOCOL_REGEX = /^https?:\/\//i
const WS_PROTOCOL_REGEX = /^wss?:\/\//i

/**
 * Validates and returns environment configuration.
 * Evaluates `import.meta.env` at call time so tests can use `vi.stubEnv`.
 *
 * @returns {{ apiBaseUrl: string | null, wsUrl: string | null, error: string | null, errors: string[] }}
 */
export function getConfig() {
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()
  const wsUrl = import.meta.env.VITE_WS_URL?.trim()
  const errors = []

  if (!apiBaseUrl) {
    errors.push('VITE_API_BASE_URL is missing')
  } else if (!HTTP_PROTOCOL_REGEX.test(apiBaseUrl)) {
    errors.push(`VITE_API_BASE_URL is invalid: "${apiBaseUrl}" must start with http:// or https://`)
  }

  if (!wsUrl) {
    errors.push('VITE_WS_URL is missing')
  } else if (!WS_PROTOCOL_REGEX.test(wsUrl)) {
    errors.push(`VITE_WS_URL is invalid: "${wsUrl}" must start with ws:// or wss://`)
  }

  if (errors.length > 0) {
    return {
      apiBaseUrl: null,
      wsUrl: null,
      error: errors.join('; '),
      errors,
    }
  }

  return {
    apiBaseUrl,
    wsUrl,
    error: null,
    errors: [],
  }
}
