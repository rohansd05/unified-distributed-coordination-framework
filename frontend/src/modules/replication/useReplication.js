import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { replicationApi } from './replicationApi'

export const MODULE_TOPIC = '/topic/modules/replication'
export const CLUSTER_TOPIC = '/topic/cluster'
/** Poll this often, and only while something is active: the asynchronous window is about half a second. */
export const POLL_INTERVAL_MS = 300

/** True while an action holds the module, asynchronous pushes are pending, or the latest write is PENDING. */
export function isActive(overview) {
  return overview?.status === 'BUSY'
    || overview?.status === 'RUNNING'
    || overview?.latestWrite?.replicationState === 'PENDING'
}

/**
 * Experiment 5's live data for the page: the module overview and the replicas side by side.
 *
 * Both are read together on every refresh. Refreshes come from any event on the module's topic
 * (a synchronous write alone publishes about ten), every cluster event (crash, recover, reset)
 * and a STOMP reconnect, and from a 300 ms poll only while isActive. Refreshes are coalesced:
 * at most one is in flight and at most one more is queued, so a burst of events costs two
 * reads, never one per event. Reads run one after another, so the newest response always wins;
 * nothing updates state after unmount or after the api changed.
 *
 * @param {object} [options]
 * @param {object} [options.api] see createReplicationApi
 * @returns {{ overview: object|null, replicas: object|null, loading: boolean, error: Error|null,
 *   replicasError: Error|null, active: boolean, refresh: () => Promise<void> }}
 */
export function useReplication({ api = replicationApi } = {}) {
  const [overview, setOverview] = useState(null)
  const [replicas, setReplicas] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [replicasError, setReplicasError] = useState(null)
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
        const [nextOverview, nextReplicas] = await Promise.allSettled([api.getOverview(), api.getReplicas()])
        if (generation !== generationRef.current) {
          return   // unmounted, or the api changed
        }
        if (nextOverview.status === 'fulfilled') {
          setOverview(nextOverview.value)
          setError(null)
        } else {
          setError(nextOverview.reason)
        }
        if (nextReplicas.status === 'fulfilled') {
          setReplicas(nextReplicas.value)
          setReplicasError(null)
        } else {
          setReplicasError(nextReplicas.reason)
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

  const active = isActive(overview)
  useEffect(() => {
    if (!active) {
      return undefined
    }
    const interval = setInterval(refresh, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [active, refresh])

  return { overview, replicas, loading, error, replicasError, active, refresh }
}
