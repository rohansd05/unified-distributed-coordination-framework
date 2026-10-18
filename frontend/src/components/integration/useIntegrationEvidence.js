import { useCallback, useEffect, useRef, useState } from 'react'
import { useStomp } from '@/hooks/useStomp'
import { clockSyncApi } from '@/modules/clocksync/clockSyncApi'
import { electionApi } from '@/modules/election/electionApi'
import { faultToleranceApi } from '@/modules/faulttolerance/faultToleranceApi'
import { loadBalancingApi } from '@/modules/loadbalancing/loadBalancingApi'
import { mapReduceApi } from '@/modules/mapreduce/mapReduceApi'
import { matrixApi } from '@/modules/matrix/matrixApi'
import { mpiApi } from '@/modules/mpi/mpiApi'
import { replicationApi } from '@/modules/replication/replicationApi'
import { SOURCES } from './evidenceModel'

/** The existing read-only endpoints the map uses; injectable for tests. */
export const DEFAULT_READERS = {
  election: () => electionApi.getOverview(),
  clocksync: () => clockSyncApi.getOverview(),
  replication: () => replicationApi.getOverview(),
  mapreduce: () => mapReduceApi.getOverview(),
  mapreduceRuns: () => mapReduceApi.getRuns(),
  faulttolerance: () => faultToleranceApi.getOverview(),
  mpi: () => mpiApi.getOverview(),
  matrix: () => matrixApi.getOverview(),
  loadbalancing: () => loadBalancingApi.getOverview(),
}

/** Wait this long after the last relevant event before reading again (bursts become one read). */
export const SETTLE_MS = 700
/** But never let a steady stream of events hold a read back for longer than this. */
export const MAX_WAIT_MS = 3000

/**
 * Which sources an event makes stale. Events of module `cluster` (LEADER_CHANGED, crashes,
 * recoveries, REPLICATION_ROLES_CHANGED, resets) can move every role, so they refresh everything.
 */
export function staleSources(event) {
  const module = event?.module
  if (!module) return []
  if (module === 'cluster' || module === 'scenario') return Object.keys(SOURCES)
  return Object.entries(SOURCES).filter(([, owner]) => owner === module).map(([id]) => id)
}

/**
 * Reads the module overviews the integration map needs, once on mount and again (only the
 * affected ones) after relevant /topic/events messages settle, and everything on a STOMP
 * reconnect. No polling: an idle cluster costs no requests.
 *
 * @returns {{ overviews: Record<string, {status: string, data: any}> }}
 */
export function useIntegrationEvidence({ readers = DEFAULT_READERS } = {}) {
  const [overviews, setOverviews] = useState(() =>
    Object.fromEntries(Object.keys(readers).map((id) => [id, { status: 'loading', data: null }])))
  const { subscribe, onConnect } = useStomp()
  const pending = useRef(new Set())
  const settleTimer = useRef(null)
  const firstPendingAt = useRef(null)
  const mounted = useRef(true)
  const firstConnect = useRef(true)

  // Per source, only the newest read may land: an older reply that arrives late is dropped.
  const generation = useRef({})

  const read = useCallback((ids) => {
    for (const id of ids) {
      const reader = readers[id]
      if (!reader) continue
      const mine = (generation.current[id] || 0) + 1
      generation.current[id] = mine
      const apply = (value) => {
        if (mounted.current && generation.current[id] === mine) {
          setOverviews((prev) => ({ ...prev, [id]: value }))
        }
      }
      Promise.resolve()
        .then(reader)
        .then((data) => apply({ status: 'ok', data }), () => apply({ status: 'error', data: null }))
    }
  }, [readers])

  const flush = useCallback(() => {
    clearTimeout(settleTimer.current)
    settleTimer.current = null
    firstPendingAt.current = null
    const ids = [...pending.current]
    pending.current.clear()
    read(ids)
  }, [read])

  useEffect(() => {
    mounted.current = true
    read(Object.keys(readers))
    return () => {
      mounted.current = false
      clearTimeout(settleTimer.current)
    }
  }, [read, readers])

  useEffect(() => subscribe('/topic/events', (event) => {
    const ids = staleSources(event)
    if (ids.length === 0) return
    ids.forEach((id) => pending.current.add(id))
    const now = Date.now()
    if (firstPendingAt.current === null) firstPendingAt.current = now
    clearTimeout(settleTimer.current)
    const waited = now - firstPendingAt.current
    settleTimer.current = setTimeout(flush, waited >= MAX_WAIT_MS ? 0 : Math.min(SETTLE_MS, MAX_WAIT_MS - waited))
  }), [subscribe, flush])

  useEffect(() => onConnect(() => {
    if (firstConnect.current) {
      firstConnect.current = false
      return
    }
    read(Object.keys(readers))
  }), [onConnect, read, readers])

  return { overviews }
}
