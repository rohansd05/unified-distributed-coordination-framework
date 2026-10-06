import { useCallback, useEffect, useRef, useState } from 'react'
import { api as defaultApi } from '@/services/api'
import { useStomp } from '@/hooks/useStomp'

/**
 * Causal comparator for events:
 * 1. lamportTime ascending
 * 2. nodeId ascending
 * 3. sequence ascending
 */
export function causalCompare(a, b) {
  if (a.lamportTime !== b.lamportTime) {
    return a.lamportTime - b.lamportTime
  }
  if (a.nodeId !== b.nodeId) {
    return a.nodeId - b.nodeId
  }
  return a.sequence - b.sequence
}

/**
 * Streams cluster events up to a given limit.
 *
 * Loads initial history via GET /api/events?limit={limit}, listens to /topic/events,
 * deduplicates by sequence, orders causally (lamportTime, nodeId, sequence),
 * replaces stream on CLUSTER_RESET, and re-loads from REST upon STOMP reconnect.
 *
 * @param {object} [options]
 * @param {number} [options.limit=50] Maximum number of events to retain
 * @param {object} [options.api=defaultApi] API client
 * @returns {{ events: Array<object>, loading: boolean, error: Error | null }}
 */
export function useEventStream({ limit = 50, api = defaultApi } = {}) {
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const { subscribe, onConnect } = useStomp()
  const isInitialConnectRef = useRef(true)

  const loadFromRest = useCallback(async () => {
    try {
      const data = await api.getEvents({ limit })
      const sorted = [...data].sort(causalCompare)
      setEvents(sorted.length > limit ? sorted.slice(-limit) : sorted)
      setError(null)
    } catch (err) {
      setError(err)
    } finally {
      setLoading(false)
    }
  }, [api, limit])

  // Initial load on mount
  useEffect(() => {
    loadFromRest()
  }, [loadFromRest])

  // Live event subscription via STOMP
  useEffect(() => {
    const unsub = subscribe('/topic/events', (event) => {
      if (!event) return

      if (event.type === 'CLUSTER_RESET') {
        setEvents([event])
        return
      }

      setEvents((prev) => {
        if (prev.some((e) => e.sequence === event.sequence)) {
          return prev
        }
        const next = [...prev, event]
        next.sort(causalCompare)
        return next.length > limit ? next.slice(-limit) : next
      })
    })

    return unsub
  }, [subscribe, limit])

  // Re-load from REST on STOMP reconnect
  useEffect(() => {
    const unsub = onConnect(async () => {
      if (isInitialConnectRef.current) {
        isInitialConnectRef.current = false
        return
      }
      loadFromRest()
    })

    return unsub
  }, [onConnect, loadFromRest])

  return { events, loading, error }
}
