import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { multithreadingApi } from './multithreadingApi'

export const MODULE_TOPIC = '/topic/modules/multithreading'
export const CLUSTER_TOPIC = '/topic/cluster'
/** Poll this often, and only while there is activity. */
export const POLL_INTERVAL_MS = 1000
/** Requests fetched for the selected node: enough for the thread tally; the table shows fewer. */
export const REQUEST_LIMIT = 100
/** Throughput samples kept for the chart. */
export const THROUGHPUT_SAMPLES = 60

/** The overview's entry for one node, or undefined. */
export function findNode(overview, nodeId) {
  return overview?.nodes?.find((node) => node.nodeId === nodeId)
}

/**
 * True while there is something to watch: a batch draining or the backpressure demo running
 * (module RUNNING or BUSY), or work active or queued on the selected node (for example sent
 * over TCP by another module).
 */
export function isActive(overview, nodeId) {
  if (overview?.status === 'RUNNING' || overview?.status === 'BUSY') {
    return true
  }
  const stats = findNode(overview, nodeId)?.stats
  return Boolean(stats && (stats.activeThreads > 0 || stats.queuedRequests > 0))
}

/**
 * Experiment 2's live data for the page: the overview, the selected node's recent requests
 * (null until they have loaded for that node) and a throughput series sampled from its executor.
 *
 * Refreshes on every BATCH_* event of the module, on every cluster event (crash, recover,
 * reset) and after a STOMP reconnect; polls every second only while isActive. Changing the
 * selected node clears its series and requests and ignores any response still in flight for
 * the old node, as does unmounting. Every interval and subscription is removed on unmount.
 *
 * @param {object} options
 * @param {number} options.selectedNodeId
 * @param {object} [options.api] see createMultithreadingApi
 */
export function useMultithreading({ selectedNodeId, api = multithreadingApi }) {
  const [overview, setOverview] = useState(null)
  const [requestState, setRequestState] = useState({ nodeId: null, items: [] })
  const [throughput, setThroughput] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { subscribe, onConnect } = useStomp()
  const generationRef = useRef(0)
  const isInitialConnectRef = useRef(true)

  const refresh = useCallback(async () => {
    const generation = ++generationRef.current
    try {
      const [nextOverview, nextRequests] = await Promise.all([
        api.getOverview(),
        api.getRequests(selectedNodeId, REQUEST_LIMIT),
      ])
      if (generation !== generationRef.current) {
        return   // a newer refresh, another node or an unmount has superseded this one
      }
      setOverview(nextOverview)
      setRequestState({ nodeId: selectedNodeId, items: Array.isArray(nextRequests) ? nextRequests : [] })
      setError(null)
      const rate = findNode(nextOverview, selectedNodeId)?.stats?.requestsPerSecond
      if (typeof rate === 'number' && Number.isFinite(rate)) {
        setThroughput((samples) => [...samples, { at: Date.now(), value: rate }].slice(-THROUGHPUT_SAMPLES))
      }
    } catch (err) {
      if (generation === generationRef.current) {
        setError(err)
      }
    } finally {
      if (generation === generationRef.current) {
        setLoading(false)
      }
    }
  }, [api, selectedNodeId])

  // A new node: start its series afresh, then load. The cleanup invalidates anything in flight.
  useEffect(() => {
    setThroughput([])
    refresh()
    return () => {
      generationRef.current += 1
    }
  }, [refresh])

  useEffect(() => {
    const unsubscribeModule = subscribe(MODULE_TOPIC, (event) => {
      if (typeof event?.type === 'string' && event.type.startsWith('BATCH_')) {
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

  const active = isActive(overview, selectedNodeId)
  useEffect(() => {
    if (!active) {
      return undefined
    }
    const interval = setInterval(refresh, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [active, refresh])

  // null until this node's own requests have arrived, so another node's rows never show here
  const requests = requestState.nodeId === selectedNodeId ? requestState.items : null

  return { overview, requests, throughput, loading, error, active, refresh }
}
