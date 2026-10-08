import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { loadBalancingApi } from './loadBalancingApi'

export const MODULE_TOPIC = '/topic/modules/loadbalancing'
export const CLUSTER_TOPIC = '/topic/cluster'
/** Poll this often, and only while something is running: runs at the defaults take under a second. */
export const POLL_INTERVAL_MS = 500

/** Module events that change what the page shows; DISPATCH_* bursts are left to polling. */
const REFRESH_ON = ['RUN_', 'COMPARISON_', 'CRASH_']

/** True while a run or a comparison executes: the module is BUSY or the latest one is still RUNNING. */
export function isActive(overview) {
  return overview?.status === 'BUSY'
    || overview?.latestRun?.state === 'RUNNING'
    || overview?.latestComparison?.state === 'RUNNING'
}

/**
 * Experiment 6's live data for the page: the module overview.
 *
 * Refreshes on the module's RUN_*, COMPARISON_* and CRASH_* events, on every cluster event
 * (crash, recover, reset) and after a STOMP reconnect; polls every 500 ms only while isActive.
 * Only the newest refresh may update state: a slower, older response (for example a late poll
 * after the run finished) and any response after unmount are ignored. Every interval and
 * subscription is removed on unmount or when the api changes.
 *
 * @param {object} [options]
 * @param {object} [options.api] see createLoadBalancingApi
 */
export function useLoadBalancing({ api = loadBalancingApi } = {}) {
  const [overview, setOverview] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { subscribe, onConnect } = useStomp()
  const generationRef = useRef(0)
  const isInitialConnectRef = useRef(true)

  const refresh = useCallback(async () => {
    const generation = ++generationRef.current
    try {
      const next = await api.getOverview()
      if (generation !== generationRef.current) {
        return   // a newer refresh or an unmount has superseded this one
      }
      setOverview(next)
      setError(null)
    } catch (err) {
      if (generation === generationRef.current) {
        setError(err)
      }
    } finally {
      if (generation === generationRef.current) {
        setLoading(false)
      }
    }
  }, [api])

  useEffect(() => {
    refresh()
    return () => {
      generationRef.current += 1
    }
  }, [refresh])

  useEffect(() => {
    const unsubscribeModule = subscribe(MODULE_TOPIC, (event) => {
      const type = typeof event?.type === 'string' ? event.type : ''
      if (REFRESH_ON.some((prefix) => type.startsWith(prefix))) {
        refresh()
      }
    })
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

  const active = isActive(overview)
  useEffect(() => {
    if (!active) {
      return undefined
    }
    const interval = setInterval(refresh, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [active, refresh])

  return { overview, loading, error, active, refresh }
}
