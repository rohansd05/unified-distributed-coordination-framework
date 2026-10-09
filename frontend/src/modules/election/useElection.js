import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { electionApi } from './electionApi'

export const MODULE_TOPIC = '/topic/modules/election'
export const CLUSTER_TOPIC = '/topic/cluster'
/** Added to the backend's round timeout before the one-shot refresh, so the round is overdue when read. */
export const TIMEOUT_MARGIN_MS = 250

/**
 * Experiment 4's overview, live. Refreshes come from any event on the module's topic, every
 * cluster event (crash, recover, leader change, reset) and a STOMP reconnect. They are
 * coalesced: at most one request in flight and at most one queued, so a burst of election
 * messages costs two reads. There is no polling loop.
 *
 * One exception, because the backend closes an overdue round only when it is read: while a
 * round is open, exactly one timer refreshes once, roundTimeoutMillis (from the overview) +
 * 250 ms after the page first saw that round. It is cleared when the round changes and on
 * unmount. Without it a Ring election with one live node would look in progress forever.
 *
 * @param {object} [options]
 * @param {object} [options.api] see createElectionApi
 * @returns {{ overview: object|null, loading: boolean, error: Error|null, refresh: () => Promise<void> }}
 */
export function useElection({ api = electionApi } = {}) {
  const [overview, setOverview] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { subscribe, onConnect } = useStomp()
  const generationRef = useRef(0)
  const inFlightRef = useRef(false)
  const queuedRef = useRef(false)
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
        try {
          const next = await api.getOverview()
          if (generation !== generationRef.current) {
            return   // unmounted, or the api changed
          }
          setOverview(next)
          setError(null)
        } catch (err) {
          if (generation !== generationRef.current) {
            return
          }
          setError(err)
        }
        setLoading(false)
      } while (queuedRef.current)
    } finally {
      if (generation === generationRef.current) {
        inFlightRef.current = false
      }
    }
  }, [api])

  useEffect(() => {
    inFlightRef.current = false
    queuedRef.current = false
    refresh()
    return () => {
      generationRef.current += 1
    }
  }, [refresh])

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

  const openRoundId = overview?.currentRound?.roundId ?? null
  const roundTimeoutMillis = overview?.settings?.roundTimeoutMillis ?? null
  useEffect(() => {
    if (openRoundId == null || roundTimeoutMillis == null) {
      return undefined
    }
    const timer = setTimeout(refresh, roundTimeoutMillis + TIMEOUT_MARGIN_MS)
    return () => clearTimeout(timer)
  }, [openRoundId, roundTimeoutMillis, refresh])

  return { overview, loading, error, refresh }
}
