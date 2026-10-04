/**
 * The Grafana URL, or null when unset. Only local mode sets VITE_GRAFANA_URL, so the
 * Monitoring page and its sidebar link exist only there (docs/HANDOFF.md 8.2).
 *
 * Read at render time rather than at import, so tests can stub it with vi.stubEnv.
 */
export function grafanaUrl() {
  const url = import.meta.env.VITE_GRAFANA_URL
  return url ? url : null
}
