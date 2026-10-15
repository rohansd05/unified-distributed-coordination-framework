import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { describeFaultProblem, faultToleranceApi } from './faultToleranceApi'
import { isFailoverInProgress, isRecovering } from './faultToleranceModel'

export const MODULE_TOPIC = '/topic/modules/faulttolerance'
export const CLUSTER_TOPIC = '/topic/cluster'

/**
 * Poll GET /status this often, and only while the stream runs, a failover is in progress, the old
 * primary is rejoining, or a timed-out crash or recovery may still be running (C2). A status read
 * is in-process and publishes nothing; it took 2.5 to 3.7 ms over HTTP (E8c "For E8d" item 5).
 */
export const STATUS_POLL_INTERVAL_MS = 500

/** While the WebSocket is down no event arrives, so status and the latest run are read this often. */
export const FALLBACK_POLL_INTERVAL_MS = 2000

/**
 * Experiment 8's live data and actions.
 *
 * - Mount (and STOMP reconnect): GET /api/modules/faulttolerance, whose overview carries the
 *   status (`current`), the latest run, settings, glossary and notes. A reload during a failover
 *   therefore restores the timeline, the polling and the controls from the overview alone (C5).
 * - Refresh: GET /status and GET /runs/latest together (404 = no run, shown as the empty state),
 *   on every /topic/modules/faulttolerance and /topic/cluster message and after each action.
 *   Coalesced: one read in flight, at most one queued.
 * - Polling: see STATUS_POLL_INTERVAL_MS. GET /runs/latest joins the status read while a failover
 *   or a recovery is open (so the timeline grows live and recoveryMillis is read from the run,
 *   never from RECOVERY_MEASURED, item 9), while no primary serves, and while the WebSocket is
 *   down (FALLBACK_POLL_INTERVAL_MS). Nothing is polled otherwise.
 * - Actions: one at a time from this page. A timeout of crash-primary or recover keeps every
 *   action locked until a status read started after the timeout shows no action in progress,
 *   then everything is read again (C2).
 *
 * @param {object} [options]
 * @param {object} [options.api] see createFaultToleranceApi
 */
export function useFaultTolerance({ api = faultToleranceApi } = {}) {
  const [overview, setOverview] = useState(null)
  const [status, setStatus] = useState(null)
  const [run, setRun] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [pending, setPending] = useState(null)
  const [problem, setProblem] = useState(null)
  const [awaitingIdle, setAwaitingIdle] = useState(false)
  const { subscribe, onConnect, status: connection } = useStomp()
  const mountedRef = useRef(true)
  const loadedRef = useRef(false)
  const inFlightRef = useRef(false)
  const queuedRef = useRef(null)
  const readSeqRef = useRef(0)
  const idleAfterSeqRef = useRef(null)
  const pendingRef = useRef(false)
  const isInitialConnectRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const load = useCallback(async () => {
    try {
      const next = await api.getOverview()
      if (!mountedRef.current) return
      loadedRef.current = true
      setOverview(next)
      setStatus(next.current)
      setRun(next.latestRun)
      setError(null)
    } catch (err) {
      if (mountedRef.current) setError(err)
    } finally {
      if (mountedRef.current) setLoading(false)
    }
  }, [api])

  const refresh = useCallback(async (withRun = true) => {
    if (!loadedRef.current) {
      load()
      return
    }
    if (inFlightRef.current) {
      queuedRef.current = { withRun: withRun || Boolean(queuedRef.current?.withRun) }
      return
    }
    inFlightRef.current = true
    let next = { withRun }
    try {
      while (next) {
        queuedRef.current = null
        readSeqRef.current += 1
        const seq = readSeqRef.current
        const [nextStatus, nextRun] = await Promise.allSettled([
          api.getStatus(),
          next.withRun ? api.getLatestRun() : Promise.resolve(undefined),
        ])
        if (!mountedRef.current) return
        if (nextStatus.status === 'fulfilled') {
          setStatus(nextStatus.value)
          setError(null)
          if (idleAfterSeqRef.current !== null && seq > idleAfterSeqRef.current && !nextStatus.value.actionInProgress) {
            idleAfterSeqRef.current = null
            setAwaitingIdle(false)
            queuedRef.current = { withRun: true }
          }
        } else {
          setError(nextStatus.reason)
        }
        // Any other failure of runs/latest keeps the run shown; the status error says the backend is unwell.
        if (next.withRun && nextRun.status === 'fulfilled') setRun(nextRun.value)
        next = queuedRef.current
      }
    } finally {
      inFlightRef.current = false
    }
  }, [api, load])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    const offModule = subscribe(MODULE_TOPIC, () => refresh())
    const offCluster = subscribe(CLUSTER_TOPIC, () => refresh())
    return () => {
      offModule()
      offCluster()
    }
  }, [subscribe, refresh])

  useEffect(() => {
    return onConnect(() => {
      if (isInitialConnectRef.current) {
        isInitialConnectRef.current = false
        return
      }
      load()
    })
  }, [onConnect, load])

  const connected = connection === 'connected'
  const openRun = isFailoverInProgress(status, run) || isRecovering(run)
  const noPrimary = Boolean(status?.started) && (status.currentPrimaryId === null || status.currentPrimaryId === undefined)
  const fast = status?.stream?.state === 'RUNNING' || openRun || awaitingIdle
  const interval = fast ? STATUS_POLL_INTERVAL_MS : connected ? null : FALLBACK_POLL_INTERVAL_MS
  const withRun = openRun || noPrimary || awaitingIdle || !connected
  useEffect(() => {
    if (!interval) return undefined
    const timer = setInterval(() => refresh(withRun), interval)
    return () => clearInterval(timer)
  }, [interval, withRun, refresh])

  const perform = useCallback(async (action, pendingText, call) => {
    if (pendingRef.current) return null
    pendingRef.current = true
    setPending(pendingText)
    setProblem(null)
    let result = null
    try {
      result = await call()
      if (!mountedRef.current) return null
      if (result?.status && typeof result.status === 'object') {
        setStatus(result.status)
      } else if (result?.state) {
        setStatus((current) => (current ? { ...current, stream: result } : current))
      }
    } catch (err) {
      if (!mountedRef.current) return null
      const described = describeFaultProblem(err, action)
      setProblem(described)
      if (described.kind === 'timeout' && (action === 'crash' || action === 'recover')) {
        idleAfterSeqRef.current = readSeqRef.current
        setAwaitingIdle(true)
      }
    } finally {
      pendingRef.current = false
      if (mountedRef.current) setPending(null)
    }
    refresh()
    return result
  }, [refresh])

  return {
    overview,
    status,
    run,
    loading,
    error,
    connection,
    pending,
    problem,
    awaitingIdle,
    locked: Boolean(pending) || awaitingIdle,
    refresh,
    start: () => perform('start', 'Starting Experiment 8...', () => api.start()),
    startStream: (ratePerSecond) => perform('stream', 'Starting the update stream...',
      () => api.startStream({ mode: status?.stream?.mode ?? null, ratePerSecond })),
    stopStream: () => perform('stream', 'Stopping the update stream...', () => api.stopStream()),
    setMode: (mode) => perform('mode', 'Changing the mode...', () => api.setMode(mode)),
    crashPrimary: () => perform('crash', 'Crashing the primary...', () => api.crashPrimary()),
    recover: () => perform('recover', 'Recovering the old primary...', () => api.recover()),
  }
}
