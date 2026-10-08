import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { clockSyncApi } from './clockSyncApi'

export const MODULE_TOPIC = '/topic/modules/clocksync'
export const CLUSTER_TOPIC = '/topic/cluster'
export const POLL_INTERVAL_MS = 1000
export const TIMELINE_LIMIT = 100

/**
 * Hook for Experiment 3 (Clock Synchronization) state and live synchronization.
 *
 * Requirements:
 * - Coalesces refreshes: at most one refresh in flight and one queued (Change 3).
 * - Fetches verification only on user action or once when a traffic session ends, never per event (Change 3).
 * - Polls only while backend reports busy / actionInProgress (Change 3).
 * - Tracks asynchronous Berkeley round completion from backend state changes without timers (Change 4).
 * - Follows useMultithreading / useReplication pattern with shared useStomp (Change 7).
 */
export function useClockSync({ api = clockSyncApi, onRoundCompleted } = {}) {
  const [overview, setOverview] = useState(null)
  const [timeline, setTimeline] = useState(null)
  const [verification, setVerification] = useState(null)
  const [loading, setLoading] = useState(true)
  const [verifying, setVerifying] = useState(false)
  const [error, setError] = useState(null)

  const { subscribe, onConnect } = useStomp()
  const inFlightRef = useRef(false)
  const queuedRef = useRef(false)
  const isInitialConnectRef = useRef(true)
  const isMountedRef = useRef(true)

  // Track state transitions for completion events
  const prevActionInProgressRef = useRef(null)
  const prevRoundIdRef = useRef(null)

  // Explicit verification request
  const verify = useCallback(async () => {
    if (!isMountedRef.current) return
    setVerifying(true)
    try {
      const res = await api.getVerification()
      if (isMountedRef.current) {
        setVerification(res)
      }
      return res
    } finally {
      if (isMountedRef.current) {
        setVerifying(false)
      }
    }
  }, [api])

  // Core refresh routine: fetches overview and timeline
  const doRefresh = useCallback(async () => {
    if (inFlightRef.current) {
      queuedRef.current = true
      return
    }
    inFlightRef.current = true

    try {
      const [nextOverview, nextTimeline] = await Promise.all([
        api.getOverview(),
        api.getTimeline(TIMELINE_LIMIT),
      ])

      if (!isMountedRef.current) return

      setOverview(nextOverview)
      setTimeline(nextTimeline)
      setError(null)

      // Detect end of random traffic session: previous action had traffic, current action is null / IDLE
      const prevAction = prevActionInProgressRef.current
      const isNowIdle = nextOverview?.status === 'IDLE' && !nextOverview?.actionInProgress
      if (prevAction && isNowIdle && prevAction.toLowerCase().includes('traffic')) {
        // Trigger one verification call upon traffic session completion
        verify().catch(() => {})
      }
      prevActionInProgressRef.current = nextOverview?.actionInProgress ?? null

      // Detect asynchronous Berkeley round completion: round changed and now IDLE
      const currentRoundId = nextOverview?.latestRound?.roundId ?? null
      const prevRoundId = prevRoundIdRef.current
      if (
        prevRoundId != null &&
        currentRoundId != null &&
        currentRoundId !== prevRoundId &&
        isNowIdle
      ) {
        if (onRoundCompleted) {
          onRoundCompleted(nextOverview.latestRound)
        }
      }
      prevRoundIdRef.current = currentRoundId
    } catch (err) {
      if (isMountedRef.current) {
        setError(err)
      }
    } finally {
      inFlightRef.current = false
      if (isMountedRef.current) {
        setLoading(false)
      }
      if (queuedRef.current) {
        queuedRef.current = false
        // Fire queued refresh
        doRefresh()
      }
    }
  }, [api, verify, onRoundCompleted])

  const refresh = useCallback(() => {
    doRefresh()
  }, [doRefresh])

  // Initial load
  useEffect(() => {
    isMountedRef.current = true
    doRefresh()
    return () => {
      isMountedRef.current = false
    }
  }, [doRefresh])

  // STOMP subscriptions
  useEffect(() => {
    const unsubModule = subscribe(MODULE_TOPIC, () => {
      doRefresh()
    })
    const unsubCluster = subscribe(CLUSTER_TOPIC, () => {
      doRefresh()
    })
    return () => {
      unsubModule()
      unsubCluster()
    }
  }, [subscribe, doRefresh])

  // Reconnect handler
  useEffect(() => {
    return onConnect(() => {
      if (isInitialConnectRef.current) {
        isInitialConnectRef.current = false
        return
      }
      doRefresh()
    })
  }, [onConnect, doRefresh])

  // Polling only while busy (actionInProgress)
  const isBusy = Boolean(overview?.status === 'BUSY' || overview?.actionInProgress)
  useEffect(() => {
    if (!isBusy) return undefined
    const interval = setInterval(doRefresh, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [isBusy, doRefresh])

  return {
    overview,
    timeline,
    verification,
    loading,
    verifying,
    error,
    active: isBusy,
    refresh,
    verify,
  }
}
