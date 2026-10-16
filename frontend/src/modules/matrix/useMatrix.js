import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { describeProblem, matrixApi } from './matrixApi'
import { needsStart, runBody } from './matrixModel'

export const MODULE_TOPIC = '/topic/modules/matrix'
export const CLUSTER_TOPIC = '/topic/cluster'

/**
 * How often the overview is read while the module is busy (another tab's run, or a run of ours
 * whose request timed out). A run's end also arrives over STOMP; this covers a lost connection.
 */
export const BUSY_POLL_INTERVAL_MS = 2000

/**
 * Experiment 10's live data and actions for the page. Adapted from Experiment 9's useMpi.
 *
 * Reads: the overview, the run history and the series, together, on every module event, cluster
 * event and STOMP reconnect, coalesced (one in flight, one queued). While the overview says BUSY
 * and no request of this page is in flight, or after a timed-out request, the reads also repeat
 * every BUSY_POLL_INTERVAL_MS; nothing is polled otherwise.
 *
 * Start: on mount the overview is read first (it never starts anything), then POST /start is
 * sent with the served timeout (the fallback only if that read failed). After that, a start is
 * sent only on the edge "no up node lacks MatrixEndpoint" to "some up node lacks it" (a cluster
 * reset). A failed start is not retried by itself; the page offers start() again.
 *
 * Runs: one request at a time from this page. runAll sends one POST /runs per served series size,
 * each after the previous answered; stopAfterCurrent lets the run in progress finish (a started
 * run cannot be cut short) and sends no more. Any error stops the loop; a 200 of any outcome
 * (REFUSED included) goes on to the next size.
 *
 * Timeouts: a timed-out start or run is never taken as failed. The page stays locked until a read
 * sent after the timeout shows the module no longer BUSY (E8d's rule).
 *
 * @param {object} [options]
 * @param {object} [options.api] see createMatrixApi
 */
export function useMatrix({ api = matrixApi } = {}) {
  const [overview, setOverview] = useState(null)
  const [runs, setRuns] = useState(null)
  const [series, setSeries] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [runsError, setRunsError] = useState(null)
  const [seriesError, setSeriesError] = useState(null)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState(null)
  const [pending, setPending] = useState(null)
  const [problem, setProblem] = useState(null)
  const [lastRun, setLastRun] = useState(null)
  const [awaitingIdle, setAwaitingIdle] = useState(false)
  const [progress, setProgress] = useState(null)
  const [stopRequested, setStopRequested] = useState(false)
  const [runAllNote, setRunAllNote] = useState(null)
  const { subscribe, onConnect } = useStomp()
  const overviewRef = useRef(null)
  const mountedRef = useRef(true)
  const inFlightRef = useRef(false)
  const queuedRef = useRef(false)
  const startingRef = useRef(false)
  const startsDoneRef = useRef(0)   // start responses applied; a read sent before one is older than it
  const neededStartRef = useRef(true)   // the mount start is the first trigger
  const isInitialConnectRef = useRef(true)
  const readSeqRef = useRef(0)   // reads sent so far
  const idleAfterSeqRef = useRef(null)   // after a timeout: only a read sent later may unlock
  const pendingRef = useRef(false)
  const stopRef = useRef(false)
  const mountGenerationRef = useRef(0)

  const applyOverview = useCallback((next) => {
    overviewRef.current = next
    setOverview(next)
  }, [])

  const refresh = useCallback(async () => {
    if (inFlightRef.current) {
      queuedRef.current = true
      return
    }
    inFlightRef.current = true
    try {
      do {
        queuedRef.current = false
        const startsAtSend = startsDoneRef.current
        const seq = ++readSeqRef.current
        const [nextOverview, nextRuns, nextSeries] = await Promise.allSettled([api.getOverview(), api.getRuns(), api.getSeries()])
        if (!mountedRef.current) return
        if (startsDoneRef.current !== startsAtSend) {
          queuedRef.current = true   // a start answered meanwhile: this overview may predate it, so read again
        } else if (nextOverview.status === 'fulfilled') {
          applyOverview(nextOverview.value)
          setError(null)
          if (idleAfterSeqRef.current !== null && seq > idleAfterSeqRef.current && nextOverview.value?.status !== 'BUSY') {
            idleAfterSeqRef.current = null
            setAwaitingIdle(false)
          }
        } else {
          setError(nextOverview.reason)
        }
        if (nextRuns.status === 'fulfilled') {
          setRuns(nextRuns.value)
          setRunsError(null)
        } else {
          setRunsError(nextRuns.reason)
        }
        if (nextSeries.status === 'fulfilled') {
          setSeries(nextSeries.value)
          setSeriesError(null)
        } else {
          setSeriesError(nextSeries.reason)
        }
        setLoading(false)
      } while (queuedRef.current)
    } finally {
      inFlightRef.current = false
    }
  }, [api, applyOverview])

  /** Locks the page until a read sent from now on shows the module idle. */
  const awaitIdle = useCallback(() => {
    idleAfterSeqRef.current = readSeqRef.current
    setAwaitingIdle(true)
  }, [])

  const start = useCallback(async () => {
    if (startingRef.current) return
    startingRef.current = true
    setStarting(true)
    setStartError(null)
    try {
      const next = await api.start(overviewRef.current)
      if (!mountedRef.current) return
      startsDoneRef.current += 1
      applyOverview(next)
      setError(null)
      setLoading(false)
    } catch (err) {
      if (!mountedRef.current) return
      setStartError(err)
      if (describeProblem(err)?.kind === 'timeout') awaitIdle()
    } finally {
      startingRef.current = false
      if (mountedRef.current) setStarting(false)
    }
    refresh()
  }, [api, applyOverview, awaitIdle, refresh])

  useEffect(() => {
    mountedRef.current = true
    inFlightRef.current = false
    queuedRef.current = false
    startingRef.current = false
    neededStartRef.current = true
    const generation = ++mountGenerationRef.current
    refresh().then(() => {
      // Only the newest mount starts (StrictMode mounts twice in development).
      if (mountedRef.current && generation === mountGenerationRef.current) start()
    })
    return () => {
      mountedRef.current = false
      mountGenerationRef.current += 1
    }
  }, [refresh, start])

  // A start fires on the edge "no node needs a start" -> "some node needs one", never on every read.
  useEffect(() => {
    if (!overview) return
    const needed = needsStart(overview)
    if (needed && !neededStartRef.current && !startingRef.current) {
      start()
    }
    neededStartRef.current = needed
  }, [overview, start])

  useEffect(() => {
    const unsubscribeModule = subscribe(MODULE_TOPIC, () => refresh())
    const unsubscribeCluster = subscribe(CLUSTER_TOPIC, () => refresh())
    return () => {
      unsubscribeModule()
      unsubscribeCluster()
    }
  }, [subscribe, refresh])

  useEffect(() => {
    return onConnect(() => {
      if (isInitialConnectRef.current) {
        isInitialConnectRef.current = false
        return
      }
      refresh()
    })
  }, [onConnect, refresh])

  const polling = awaitingIdle || (overview?.status === 'BUSY' && !pending && !progress)
  useEffect(() => {
    if (!polling) return undefined
    const timer = setInterval(() => refresh(), BUSY_POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [polling, refresh])

  /** One POST /runs; resolves to { ok, run, problem } and never throws. */
  const runOnce = useCallback(async (size, rootNode) => {
    pendingRef.current = true
    setPending({ size })
    setProblem(null)
    let outcome
    try {
      const run = await api.runMatrix(runBody({ size, rootNode }), overviewRef.current)
      if (mountedRef.current) setLastRun(run)
      outcome = { ok: true, run, problem: null }
    } catch (err) {
      const described = describeProblem(err)
      if (mountedRef.current) {
        setProblem(described)
        if (described.kind === 'timeout') awaitIdle()
      }
      outcome = { ok: false, run: null, problem: described }
    } finally {
      pendingRef.current = false
      if (mountedRef.current) setPending(null)
    }
    if (mountedRef.current) refresh()
    return outcome
  }, [api, awaitIdle, refresh])

  const run = useCallback(async (size, rootNode) => {
    if (pendingRef.current || progress) return null
    setRunAllNote(null)
    return runOnce(size, rootNode)
  }, [progress, runOnce])

  const runAll = useCallback(async (rootNode) => {
    if (pendingRef.current || progress) return
    const sizes = overviewRef.current?.limits?.seriesSizes ?? []
    stopRef.current = false
    setStopRequested(false)
    setRunAllNote(null)
    let done = 0
    let note = null
    for (let index = 0; index < sizes.length; index += 1) {
      if (!mountedRef.current) return
      if (stopRef.current) {
        note = `Stopped after size ${sizes[index - 1]}, as asked. ${sizes.length - index} of ${sizes.length} sizes did not run.`
        break
      }
      setProgress({ index: index + 1, total: sizes.length, size: sizes[index] })
      const outcome = await runOnce(sizes[index], rootNode)
      if (!outcome.ok) {
        note = `Stopped at size ${sizes[index]}: ${outcome.problem.message} ${sizes.length - index - 1} later `
          + `${sizes.length - index - 1 === 1 ? 'size was' : 'sizes were'} not run.`
        break
      }
      done += 1
    }
    if (!mountedRef.current) return
    setProgress(null)
    setStopRequested(false)
    setRunAllNote(note ?? `Ran all ${done} sizes, one after another.`)
  }, [progress, runOnce])

  const stopAfterCurrent = useCallback(() => {
    stopRef.current = true
    setStopRequested(true)
  }, [])

  return {
    overview,
    runs,
    series,
    loading,
    error,
    runsError,
    seriesError,
    starting,
    startError,
    start,
    refresh,
    pending,
    progress,
    stopRequested,
    runAllNote,
    problem,
    lastRun,
    awaitingIdle,
    locked: Boolean(pending) || Boolean(progress) || awaitingIdle,
    run,
    runAll,
    stopAfterCurrent,
  }
}
