import { useCallback, useEffect, useState } from 'react'
import { api as defaultApi } from '@/services/api'
import { useStomp } from '@/hooks/useStomp'
import { INITIAL_LIMIT, mergeEvents } from './timelineModel'

/**
 * Every module's events for the Timeline page: GET /api/events?limit=500 on mount and after each
 * (re)connect, plus /topic/events live, merged by sequence and capped (see mergeEvents).
 *
 * @returns {{ events: Array<object>, loading: boolean, error: Error | null, status: string }}
 *   events in bus-sequence order; status is the shared STOMP connection status
 */
export function useTimelineEvents({ api = defaultApi } = {}) {
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { subscribe, onConnect, status } = useStomp()

  const load = useCallback(async () => {
    try {
      const data = await api.getEvents({ limit: INITIAL_LIMIT })
      setEvents((prev) => mergeEvents(prev, data))
      setError(null)
    } catch (err) {
      setError(err)
    } finally {
      setLoading(false)
    }
  }, [api])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => subscribe('/topic/events', (event) => {
    if (event && typeof event === 'object') setEvents((prev) => mergeEvents(prev, [event]))
  }), [subscribe])

  // Merging is idempotent, so every connect reloads: it fills whatever was missed while away.
  useEffect(() => onConnect(() => load()), [onConnect, load])

  return { events, loading, error, status }
}
