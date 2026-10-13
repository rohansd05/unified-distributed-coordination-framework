import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { rmiApi } from './rmiApi'
import { needsStart } from './rmiModel'

export const MODULE_TOPIC = '/topic/modules/rmi'
export const CLUSTER_TOPIC = '/topic/cluster'

/**
 * Experiment 1's live data for the page: the overview, the call history and every node's
 * registry, read together.
 *
 * Refreshes come from any event on the module's topic, every cluster event (crash, recover,
 * reset) and a STOMP reconnect; there is no polling. They are coalesced: at most one read is in
 * flight and at most one more is queued. The history is replaced wholesale on every read,
 * because a cluster reset restarts call ids.
 *
 * Start: the overview never starts anything, so the hook calls POST /start once on mount, and
 * again only when "some node is up but not started" turns from false to true (for example
 * after a cluster reset). A failed start is not retried by itself: the condition stays true, so
 * no new edge comes; the page offers "Try again" (start()). Nothing updates after unmount.
 *
 * @param {object} [options]
 * @param {object} [options.api] see createRmiApi
 */
export function useRmi({ api = rmiApi } = {}) {
  const [overview, setOverview] = useState(null)
  const [history, setHistory] = useState(null)
  const [registries, setRegistries] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [historyError, setHistoryError] = useState(null)
  const [registriesError, setRegistriesError] = useState(null)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState(null)
  const { subscribe, onConnect } = useStomp()
  const generationRef = useRef(0)
  const inFlightRef = useRef(false)
  const queuedRef = useRef(false)
  const startingRef = useRef(false)
  const startsDoneRef = useRef(0)   // start responses applied; a read sent before one is older than it
  const neededStartRef = useRef(true)   // the mount start is the first trigger
  const isInitialConnectRef = useRef(true)

  const refresh = useCallback(async () => {
    if (inFlightRef.current) {
      queuedRef.current = true
      return
    }
    inFlightRef.current = true
    const generation = generationRef.current
    try {
      do {
        queuedRef.current = false
        const startsAtSend = startsDoneRef.current
        const [nextOverview, nextHistory, nextRegistries] = await Promise.allSettled([
          api.getOverview(), api.getHistory(), api.getRegistries(),
        ])
        if (generation !== generationRef.current) return
        if (startsDoneRef.current !== startsAtSend) {
          queuedRef.current = true   // a start answered meanwhile: this overview may predate it, so read again
        } else if (nextOverview.status === 'fulfilled') {
          setOverview(nextOverview.value)
          setError(null)
        } else {
          setError(nextOverview.reason)
        }
        if (nextHistory.status === 'fulfilled') {
          setHistory(nextHistory.value)
          setHistoryError(null)
        } else {
          setHistoryError(nextHistory.reason)
        }
        if (nextRegistries.status === 'fulfilled') {
          setRegistries(nextRegistries.value)
          setRegistriesError(null)
        } else {
          setRegistriesError(nextRegistries.reason)
        }
        setLoading(false)
      } while (queuedRef.current)
    } finally {
      if (generation === generationRef.current) {
        inFlightRef.current = false
      }
    }
  }, [api])

  const start = useCallback(async () => {
    if (startingRef.current) return
    startingRef.current = true
    const generation = generationRef.current
    setStarting(true)
    setStartError(null)
    try {
      const next = await api.start()
      if (generation !== generationRef.current) return
      startsDoneRef.current += 1
      setOverview(next)
      setError(null)
      setLoading(false)
    } catch (err) {
      if (generation !== generationRef.current) return
      setStartError(err)
    } finally {
      if (generation === generationRef.current) {
        startingRef.current = false
        setStarting(false)
      }
    }
    refresh()   // the registries exist only now
  }, [api, refresh])

  useEffect(() => {
    inFlightRef.current = false
    queuedRef.current = false
    startingRef.current = false
    neededStartRef.current = true
    refresh()
    start()
    return () => {
      generationRef.current += 1
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

  return { overview, history, registries, loading, error, historyError, registriesError, starting, startError, start, refresh }
}
