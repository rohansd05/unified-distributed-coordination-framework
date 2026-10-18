import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { scenariosApi as defaultApi } from './scenariosApi'
import { SCENARIO_MODULE, applyEvent, isActive, preferRun, skeletonRun } from './scenarioModel'

/** While a run is active and no scenario event arrived for this long, GET /runs/latest is polled. */
export const POLL_MS = 1000

/**
 * The scenarios, the latest run and the start action for the Scenarios page.
 *
 * Live: every /topic/events message of module "scenario" is folded into the run at once. Fallback:
 * while a run is active, GET /api/scenarios/runs/latest every second unless an event arrived in the
 * last second (a dropped notification or a lost connection never leaves the page stuck). When a run
 * finishes, the run is read once more for its end state, which no event carries.
 *
 * @returns {{ scenarios: Array|null, loadError: Error|null, run: object|null, starting: string|null,
 *   startProblem: {kind: 'busy'|'error', message: string}|null, start: (id: string) => Promise<void>,
 *   status: string }}
 */
export function useScenarios({ api = defaultApi } = {}) {
  const [scenarios, setScenarios] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [run, setRun] = useState(null)
  const [starting, setStarting] = useState(null)
  const [startProblem, setStartProblem] = useState(null)
  const scenariosRef = useRef([])
  const lastEventAt = useRef(0)
  const { subscribe, onConnect, status } = useStomp()

  const refreshLatest = useCallback(async () => {
    try {
      const latest = await api.getLatestRun()
      setRun((current) => preferRun(current, latest))
    } catch {
      // the next poll or event tries again; the page keeps what it has
    }
  }, [api])

  const refreshRun = useCallback(async (runId) => {
    try {
      const fresh = await api.getRun(runId)
      setRun((current) => preferRun(current, fresh))
    } catch {
      refreshLatest()
    }
  }, [api, refreshLatest])

  useEffect(() => {
    let cancelled = false
    api.getScenarios()
      .then((list) => {
        if (cancelled) return
        scenariosRef.current = list ?? []
        setScenarios(list ?? [])
        setLoadError(null)
      })
      .catch((err) => { if (!cancelled) setLoadError(err) })
    refreshLatest()
    return () => { cancelled = true }
  }, [api, refreshLatest])

  useEffect(() => subscribe('/topic/events', (event) => {
    if (!event || event.module !== SCENARIO_MODULE) return
    lastEventAt.current = Date.now()
    setRun((current) => applyEvent(current, event, scenariosRef.current))
    if (event.type === 'SCENARIO_FINISHED' && event.data?.runId != null) {
      refreshRun(event.data.runId)
    }
  }), [subscribe, refreshRun])

  useEffect(() => onConnect(() => { refreshLatest() }), [onConnect, refreshLatest])

  const active = isActive(run)
  useEffect(() => {
    if (!active) return undefined
    const timer = setInterval(() => {
      if (Date.now() - lastEventAt.current >= POLL_MS) refreshLatest()
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [active, refreshLatest])

  const start = useCallback(async (scenarioId) => {
    setStarting(scenarioId)
    setStartProblem(null)
    try {
      const accepted = await api.startRun(scenarioId)
      setRun((current) => (current && current.runId >= accepted.runId
        ? current
        : skeletonRun(accepted, scenariosRef.current)))
    } catch (err) {
      if (err?.status === 409) {
        setStartProblem({ kind: 'busy', message: 'Another scenario is running. It is shown below; run this one when it has finished.' })
        refreshLatest()
      } else {
        setStartProblem({ kind: 'error', message: err?.detail || err?.message || 'The scenario could not be started.' })
      }
    } finally {
      setStarting(null)
    }
  }, [api, refreshLatest])

  return { scenarios, loadError, run, starting, startProblem, start, status }
}
