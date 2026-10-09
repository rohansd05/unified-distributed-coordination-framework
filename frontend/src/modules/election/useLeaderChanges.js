import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { causalCompare } from '@/hooks/useEventStream'
import { electionApi } from './electionApi'

export const LEADER_CHANGED = 'LEADER_CHANGED'
/** Cluster events read once to find the leader changes among them. */
export const CLUSTER_EVENT_LIMIT = 200

const leaderChangesOf = (events) => events.filter((event) => event?.type === LEADER_CHANGED)

/**
 * The cluster log's LEADER_CHANGED events, live: one GET /api/events?module=cluster, filtered,
 * then each LEADER_CHANGED from /topic/cluster, deduplicated by sequence and in causal order.
 * Read straight from the cluster log, never copied into the election module's log. Cleared on
 * CLUSTER_RESET; reloaded after a STOMP reconnect.
 *
 * @param {object} [options]
 * @param {object} [options.api] needs getEvents({ module, limit })
 * @returns {{ changes: Array<object>, loading: boolean, error: Error|null }}
 */
export function useLeaderChanges({ api = electionApi } = {}) {
  const [changes, setChanges] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { subscribe, onConnect } = useStomp()
  const generationRef = useRef(0)
  const isInitialConnectRef = useRef(true)

  const load = useCallback(async () => {
    const generation = ++generationRef.current
    try {
      const events = await api.getEvents({ module: 'cluster', limit: CLUSTER_EVENT_LIMIT })
      if (generation === generationRef.current) {
        setChanges(leaderChangesOf(Array.isArray(events) ? events : []).sort(causalCompare))
        setError(null)
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
  }, [api])

  useEffect(() => {
    load()
    return () => {
      generationRef.current += 1
    }
  }, [load])

  useEffect(() => subscribe('/topic/cluster', (event) => {
    if (event?.type === 'CLUSTER_RESET') {
      setChanges([])
    } else if (event?.type === LEADER_CHANGED) {
      setChanges((previous) => (previous.some((e) => e.sequence === event.sequence)
        ? previous
        : [...previous, event].sort(causalCompare)))
    }
  }), [subscribe])

  useEffect(() => onConnect(() => {
    if (isInitialConnectRef.current) {
      isInitialConnectRef.current = false
      return
    }
    load()
  }), [onConnect, load])

  return { changes, loading, error }
}
