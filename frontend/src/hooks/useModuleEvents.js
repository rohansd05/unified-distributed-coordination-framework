import { useCallback, useEffect, useRef, useState } from 'react'
import { api as defaultApi } from '@/services/api'
import { useStomp } from '@/hooks/useStomp'
import { causalCompare } from '@/hooks/useEventStream'

/** Merges one event into a causally ordered list: drops a duplicate sequence, keeps the newest `limit`. */
function addEvent(list, event, limit) {
  if (list.some((existing) => existing.sequence === event.sequence)) {
    return list
  }
  const next = [...list, event].sort(causalCompare)
  return next.length > limit ? next.slice(-limit) : next
}

/**
 * One module's events, live: loads GET /api/events?module=<id>&limit=<limit>, then appends
 * events from /topic/modules/<id>, deduplicated by sequence and ordered causally
 * (lamportTime, nodeId, sequence).
 *
 * A cluster reset clears the list: CLUSTER_RESET is published on /topic/cluster (module
 * "cluster"), not on the module's topic, so that topic is watched for it too. After a STOMP
 * reconnect the history is loaded again. Responses that arrive after unmount, or after
 * moduleId changed, are ignored.
 *
 * @param {object} options
 * @param {string} options.moduleId
 * @param {number} [options.limit=50]
 * @param {object} [options.api] needs getEvents({ module, limit })
 * @returns {{ events: Array<object>, loading: boolean, error: Error|null }}
 */
export function useModuleEvents({ moduleId, limit = 50, api = defaultApi }) {
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { subscribe, onConnect } = useStomp()
  const generationRef = useRef(0)
  const isInitialConnectRef = useRef(true)

  const load = useCallback(async () => {
    const generation = ++generationRef.current
    try {
      const data = await api.getEvents({ module: moduleId, limit })
      if (generation !== generationRef.current) {
        return
      }
      const sorted = [...(Array.isArray(data) ? data : [])].sort(causalCompare)
      setEvents(sorted.length > limit ? sorted.slice(-limit) : sorted)
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
  }, [api, moduleId, limit])

  useEffect(() => {
    load()
    return () => {
      generationRef.current += 1   // ignore a response still in flight
    }
  }, [load])

  useEffect(() => {
    const unsubscribeModule = subscribe(`/topic/modules/${moduleId}`, (event) => {
      if (!event || event.module !== moduleId) {
        return
      }
      setEvents((previous) => addEvent(previous, event, limit))
    })
    const unsubscribeCluster = subscribe('/topic/cluster', (event) => {
      if (event?.type === 'CLUSTER_RESET') {
        setEvents([])
      }
    })
    return () => {
      unsubscribeModule()
      unsubscribeCluster()
    }
  }, [subscribe, moduleId, limit])

  useEffect(() => {
    return onConnect(() => {
      if (isInitialConnectRef.current) {
        isInitialConnectRef.current = false
        return
      }
      load()
    })
  }, [onConnect, load])

  return { events, loading, error }
}
