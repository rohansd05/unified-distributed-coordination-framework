import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { mapReduceApi } from './mapReduceApi'
import { baselineCandidate, compareResults, isTerminal } from './runModel'

export const MODULE_TOPIC = '/topic/modules/mapreduce'
export const CLUSTER_TOPIC = '/topic/cluster'
/** Poll the shown run this often, and only while it is RUNNING. */
export const POLL_INTERVAL_MS = 500
/** Module event types that change what the page shows (each checked against the backend source). */
export const REFRESH_ON = ['JOB_STARTED', 'JOB_COMPLETED', 'JOB_FAILED', 'WORKER_CRASH_TRIGGERED']

/** A refresh must not turn a run the page already saw finish back into RUNNING. */
function newer(current, next) {
  if (current && next && current.runId === next.runId && isTerminal(current.state) && next.state === 'RUNNING') {
    return current
  }
  return next
}

/**
 * Experiment 7's live data: the overview, the run history and one shown run.
 *
 * - Loads GET /api/modules/mapreduce, GET /runs and GET /runs/latest (404 = no run yet) on mount,
 *   on JOB_STARTED, JOB_COMPLETED, JOB_FAILED and WORKER_CRASH_TRIGGERED (/topic/modules/mapreduce),
 *   on any /topic/cluster message, and after a STOMP reconnect.
 * - While the shown run is RUNNING, polls GET /runs/{runId} every 500 ms; it stops on COMPLETED or
 *   FAILED, on a 404 (the run was cleared by a cluster reset) and on unmount.
 * - The shown run is the latest one, or the history entry the user picked (selectRun).
 * - For a completed crash run, loads the matching earlier run (baselineCandidate) and compares the
 *   results (compareResults); the verdict is in `comparison`.
 *
 * Only the newest refresh may update state, and nothing updates after unmount.
 *
 * @param {object} [options]
 * @param {object} [options.api] see createMapReduceApi
 */
export function useMapReduce({ api = mapReduceApi } = {}) {
  const [overview, setOverview] = useState(null)
  const [runs, setRuns] = useState([])
  const [run, setRun] = useState(null)
  const [pinnedRunId, setPinnedRunId] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [cleared, setCleared] = useState(false)
  const [comparison, setComparison] = useState(null)
  const { subscribe, onConnect } = useStomp()
  const generationRef = useRef(0)
  const mountedRef = useRef(true)
  const pinnedRef = useRef(null)
  const isInitialConnectRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      generationRef.current += 1
    }
  }, [])

  const refresh = useCallback(async () => {
    const generation = ++generationRef.current
    try {
      const pinned = pinnedRef.current
      const [nextOverview, nextRuns, nextRun] = await Promise.all([
        api.getOverview(),
        api.getRuns(),
        pinned ? api.getRun(pinned) : api.getLatestRun(),
      ])
      if (generation !== generationRef.current) {
        return   // a newer refresh or an unmount has superseded this one
      }
      setOverview(nextOverview)
      setRuns(Array.isArray(nextRuns) ? nextRuns : [])
      setRun((current) => newer(current, nextRun))
      setError(null)
    } catch (err) {
      if (generation === generationRef.current) {
        if (err?.status === 404 && pinnedRef.current) {
          pinnedRef.current = null   // the picked run is gone (history bound or reset): follow the latest again
          setPinnedRunId(null)
          setCleared(true)
        } else {
          setError(err)
        }
      }
    } finally {
      if (generation === generationRef.current) {
        setLoading(false)
      }
    }
  }, [api])

  useEffect(() => {
    refresh()
  }, [refresh])

  useEffect(() => {
    const unsubscribeModule = subscribe(MODULE_TOPIC, (event) => {
      if (REFRESH_ON.includes(event?.type)) {
        refresh()
      }
    })
    const unsubscribeCluster = subscribe(CLUSTER_TOPIC, () => refresh())
    return () => {
      unsubscribeModule()
      unsubscribeCluster()
    }
  }, [subscribe, refresh])

  useEffect(() => onConnect(() => {
    if (isInitialConnectRef.current) {
      isInitialConnectRef.current = false
      return
    }
    refresh()
  }), [onConnect, refresh])

  // Poll the shown run while it is RUNNING.
  const runningId = run?.state === 'RUNNING' ? run.runId : null
  useEffect(() => {
    if (!runningId) {
      return undefined
    }
    let cancelled = false
    const tick = async () => {
      try {
        const next = await api.getRun(runningId)
        if (cancelled) return
        setRun((current) => (current?.runId === runningId ? next : current))
        if (isTerminal(next?.state)) {
          refresh()
        }
      } catch (err) {
        if (cancelled) return
        if (err?.status === 404) {
          setRun((current) => (current?.runId === runningId ? null : current))
          setCleared(true)
        }
        // any other error: the next tick tries again
      }
    }
    const interval = setInterval(tick, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [runningId, api, refresh])

  // Compare a completed crash run with the matching earlier run, when one exists.
  const crashRunId = run?.state === 'COMPLETED' && run?.crash?.triggered ? run.runId : null
  const candidate = crashRunId ? baselineCandidate(run, runs) : null
  const candidateId = candidate?.runId ?? null
  useEffect(() => {
    if (!crashRunId) {
      setComparison(null)
      return undefined
    }
    let cancelled = false
    if (!candidateId) {
      setComparison({ runId: crashRunId, baselineId: null, ...compareResults(run, null) })
      return undefined
    }
    api.getRun(candidateId)
      .then((baseline) => {
        if (!cancelled) setComparison({ runId: crashRunId, baselineId: candidateId, ...compareResults(run, baseline) })
      })
      .catch(() => {
        if (!cancelled) {
          setComparison({ runId: crashRunId, baselineId: candidateId, verdict: 'not-comparable', reason: 'baseline-unavailable' })
        }
      })
    return () => {
      cancelled = true
    }
    // run is read only through crashRunId and candidateId, which identify it
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [crashRunId, candidateId, api])

  /** Shows a run just accepted by POST /runs (it is polled while RUNNING). */
  const showAccepted = useCallback((accepted) => {
    pinnedRef.current = null
    setPinnedRunId(null)
    setCleared(false)
    setRun(accepted)
  }, [])

  /** Shows one history entry; null goes back to following the latest run. */
  const selectRun = useCallback(async (runId) => {
    pinnedRef.current = runId
    setPinnedRunId(runId)
    setCleared(false)
    if (!runId) {
      refresh()
      return
    }
    try {
      const picked = await api.getRun(runId)
      if (mountedRef.current && pinnedRef.current === runId) setRun(picked)
    } catch (err) {
      if (!mountedRef.current) return
      if (err?.status === 404) {
        pinnedRef.current = null
        setPinnedRunId(null)
        setCleared(true)
        refresh()
      } else {
        setError(err)
      }
    }
  }, [api, refresh])

  return {
    overview,
    runs,
    run,
    pinnedRunId,
    loading,
    error,
    cleared,
    comparison: comparison && comparison.runId === run?.runId ? comparison : null,
    refresh,
    showAccepted,
    selectRun,
  }
}
