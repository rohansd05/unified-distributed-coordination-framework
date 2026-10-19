import { useCallback, useEffect, useRef, useState } from 'react'
import { api as defaultApi } from '@/services/api'
import { useStomp } from '@/hooks/useStomp'
import { ClusterContext } from './ClusterContext'

export const WAKE_SLOW_MS = 3000
export const WAKE_RETRY_MS = 3000

/**
 * Provides live cluster state, system information, and module statuses.
 *
 * Fetches initial data on mount, debounces re-fetches of /api/cluster upon
 * receiving /topic/cluster messages (150 ms debounce), and re-fetches cluster
 * and module statuses on every STOMP reconnect.
 *
 * Cold start (14.1): free hosting sleeps the backend. If the first health check fails or takes
 * longer than WAKE_SLOW_MS, `wakingSince` is set and /api/system/health is retried every
 * WAKE_RETRY_MS; once it answers, `wakingSince` clears and everything is fetched again. This runs
 * once per page load; later outages show in the top bar's connection status.
 */
export function ClusterProvider({ api = defaultApi, children }) {
  const [info, setInfo] = useState(null)
  const [cluster, setCluster] = useState(null)
  const [modules, setModules] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [wakingSince, setWakingSince] = useState(null)

  const { subscribe, onConnect } = useStomp()
  const clusterDebounceTimerRef = useRef(null)
  const isInitialConnectRef = useRef(true)

  const fetchAll = useCallback(async () => {
    try {
      const [infoRes, clusterRes, modulesRes] = await Promise.all([
        api.getSystemInfo(),
        api.getCluster(),
        api.getModules(),
      ])
      setInfo(infoRes)
      setCluster(clusterRes)
      setModules(modulesRes)
      setError(null)
    } catch (err) {
      setError(err)
      setModules(null)
    } finally {
      setLoading(false)
    }
  }, [api])

  // Initial fetch on mount
  useEffect(() => {
    fetchAll()
  }, [fetchAll])

  // Cold-start watch: first health check slow or failing -> waking, retry until it answers.
  useEffect(() => {
    let cancelled = false
    let waking = false
    let retryTimer = null
    const startedAt = Date.now()
    const markWaking = () => {
      if (!waking) {
        waking = true
        setWakingSince(startedAt)
      }
    }
    const slowTimer = setTimeout(markWaking, WAKE_SLOW_MS)
    const check = async () => {
      try {
        await api.getHealth()
        clearTimeout(slowTimer)
        if (cancelled || !waking) return
        setWakingSince(null)
        fetchAll()
      } catch {
        if (cancelled) return
        markWaking()
        retryTimer = setTimeout(check, WAKE_RETRY_MS)
      }
    }
    check()
    return () => {
      cancelled = true
      clearTimeout(slowTimer)
      clearTimeout(retryTimer)
    }
  }, [api, fetchAll])

  // Debounced cluster refresh upon /topic/cluster events
  const scheduleClusterFetch = useCallback(() => {
    if (clusterDebounceTimerRef.current) {
      clearTimeout(clusterDebounceTimerRef.current)
    }
    clusterDebounceTimerRef.current = setTimeout(async () => {
      try {
        const clusterRes = await api.getCluster()
        setCluster(clusterRes)
      } catch (err) {
        console.error('Failed to update cluster after event:', err)
      }
    }, 150)
  }, [api])

  useEffect(() => {
    const unsub = subscribe('/topic/cluster', () => {
      scheduleClusterFetch()
    })
    return () => {
      unsub()
      if (clusterDebounceTimerRef.current) {
        clearTimeout(clusterDebounceTimerRef.current)
      }
    }
  }, [subscribe, scheduleClusterFetch])

  // Re-fetch cluster and modules on STOMP reconnect
  useEffect(() => {
    const unsub = onConnect(async () => {
      if (isInitialConnectRef.current) {
        isInitialConnectRef.current = false
        return
      }
      try {
        const [clusterRes, modulesRes] = await Promise.all([
          api.getCluster(),
          api.getModules(),
        ])
        setCluster(clusterRes)
        setModules(modulesRes)
      } catch (err) {
        console.error('Failed to refresh cluster/modules after reconnect:', err)
      }
    })
    return unsub
  }, [onConnect, api])

  return (
    <ClusterContext.Provider
      value={{
        info,
        cluster,
        modules,
        loading,
        error,
        wakingSince,
        refresh: fetchAll,
      }}
    >
      {children}
    </ClusterContext.Provider>
  )
}
