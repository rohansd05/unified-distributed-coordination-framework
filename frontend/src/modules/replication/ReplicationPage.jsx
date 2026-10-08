import { useEffect, useRef, useState } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { Button } from '@/components/ui/button'
import { useModuleEvents } from '@/hooks/useModuleEvents'
import { useStomp } from '@/hooks/useStomp'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { HealthTable } from './HealthTable'
import { formatMillis, modelLabel, moduleStatusLabel } from './labels'
import { PendingWindow } from './PendingWindow'
import { ReplicaGrid } from './ReplicaGrid'
import { ReplicationControls } from './ReplicationControls'
import { gridModel, pendingWindow, timelineModel } from './replicaModel'
import { replicationApi } from './replicationApi'
import { TakeoverTimeline } from './TakeoverTimeline'
import { useReplication } from './useReplication'

/** Events read for the primary timeline; PRIMARY_SELECTED is rare among the module's events. */
export const TIMELINE_EVENT_LIMIT = 500

const HOW_IT_WORKS = [
  'Every node keeps its own copy of the same small key-value store. One node, the primary, accepts each write and sends it over TCP to every other node, the backups. Until the leader election is wired in, the primary is simply the lowest-numbered node that is up.',
  'A synchronous write waits until every backup has answered before it tells the client the write succeeded. An asynchronous write tells the client at once and sends the update afterwards, so for a short time a backup can still hold the old value.',
  'When two versions of a key meet, each replica keeps the one with the higher Lamport time, and the node id breaks a tie. That rule also lets a backup refuse an older update that arrives late.',
  'A crashed backup misses writes and stays behind after it recovers. Anti-entropy repairs it: the primary pushes its whole store, and the backup keeps only what is newer. When the primary changes, the new primary first pulls every live replica\'s store, so it holds what it missed, then pushes its store to the backups.',
]

const WHAT_TO_NOTICE = [
  'Write asynchronously and read a backup straight away: while the replication window is open, the backup can still return the old value. A synchronous write never leaves that window, but the client waits for every backup.',
  'Crash a backup and keep writing: it falls behind, and it stays behind after you recover it until anti-entropy pushes the primary\'s store to it.',
]

/**
 * Experiment 5, Consistency and Replication (docs/HANDOFF.md 7): write through the primary
 * synchronously or asynchronously, read any replica, compare every replica side by side,
 * crash and recover backups, repair with anti-entropy, deliver a stale update out of order, and
 * watch the primary change. Built on the shared kit; live through /topic/modules/replication.
 *
 * Honest by construction: every number comes from the backend; a figure that was not measured
 * shows "—" and never 0; the only simulated thing, the asynchronous push delay, carries the
 * Simulated badge with the backend's reason; the last-writer-wins caveat is shown as the backend
 * wrote it.
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry (lab, id, title, concept)
 * @param {object} [props.api] see createReplicationApi; for tests
 */
export function ReplicationPage({ experiment, api = replicationApi }) {
  const { overview, replicas, loading, error, replicasError, refresh } = useReplication({ api })
  const { events } = useModuleEvents({ moduleId: experiment.id, limit: TIMELINE_EVENT_LIMIT, api })
  const { status: connection } = useStomp()
  const [message, setMessage] = useState('')
  const announcement = usePoliteAnnouncement(message)

  const grid = gridModel(replicas)
  const timeline = timelineModel(events, overview)
  const replicationWindow = pendingWindow(overview)
  const keys = grid.rows.map((row) => row.key)
  const busy = overview?.status === 'BUSY'
  const hasData = Boolean(overview)
  const unreachable = connection === 'reconnecting' || connection === 'error' || Boolean(error)

  // Announce the window closing: the latest write went from PENDING to COMPLETE.
  const openWriteRef = useRef(null)
  const latestWrite = overview?.latestWrite ?? null
  useEffect(() => {
    if (latestWrite?.replicationState === 'PENDING') {
      openWriteRef.current = latestWrite.writeId
      return
    }
    if (openWriteRef.current && latestWrite?.writeId === openWriteRef.current) {
      setMessage(`Replication window closed: every backup has answered for ${latestWrite.item.key}.`)
    }
    openWriteRef.current = null
  }, [latestWrite])

  const healthRows = Array.isArray(overview?.health) ? overview.health : []
  const staleRejections = healthRows.length > 0
    ? healthRows.reduce((sum, row) => sum + (row.staleRejections ?? 0), 0) : null
  const antiEntropy = overview?.latestAntiEntropy ?? null

  const controls = (
    <div className="relative space-y-4">
      <p aria-live="polite" className="sr-only">{announcement}</p>
      {unreachable && (
        <div role="alert" className="rounded-lg border border-warning/60 bg-warning/10 p-4 text-sm">
          <p className="font-semibold">The backend is not reachable.</p>
          <p className="mt-1 text-muted-foreground">
            Start it again with <code className="font-mono text-foreground">cd backend; .\mvnw.cmd spring-boot:run</code>.
            This page reconnects and refreshes by itself.
          </p>
          {error?.detail && <p className="mt-1 text-xs text-muted-foreground">Last error: {error.detail}</p>}
        </div>
      )}
      {!hasData && loading && (
        <div className="h-40 animate-pulse rounded-lg bg-card/60 motion-reduce:animate-none" aria-busy="true">
          <span className="sr-only">Loading the replication module…</span>
        </div>
      )}
      {!hasData && !loading && error && (
        <div className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load the replication module.</p>
          <p className="mt-1 text-muted-foreground">{error.detail || error.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}
      {hasData && (
        <>
          <p className="text-sm" data-testid="module-status">
            Module status: <span className="font-medium">{moduleStatusLabel(overview.status)}</span>
            {overview.actionInProgress && <span className="text-muted-foreground"> ({overview.actionInProgress})</span>}
            <span className="text-muted-foreground">
              {'. '}
              {overview.primaryNodeId != null
                ? `The primary is node ${overview.primaryNodeId}, the lowest live node.`
                : 'Every node is crashed, so there is no primary.'}
            </span>
          </p>
          <ReplicationControls overview={overview} keys={keys} api={api} disabled={busy}
            onAnnounce={setMessage} onDone={refresh} />
        </>
      )}
    </div>
  )

  const visualisation = (
    <div className="space-y-5">
      <PendingWindow window={replicationWindow} />
      <ReplicaGrid model={grid} loading={loading} error={replicasError} onRetry={refresh} />
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Who is primary</h3>
        <TakeoverTimeline model={timeline} />
      </div>
    </div>
  )

  const measurements = (
    <div className="space-y-4">
      <div className="grid grid-cols-2 divide-x divide-y rounded-lg border bg-card sm:grid-cols-3 sm:divide-y-0 lg:grid-cols-5">
        <MetricCard label="Latest write confirmed after" value={formatMillis(latestWrite?.confirmMillis)} unit="ms"
          hint={latestWrite ? `${modelLabel(latestWrite.model)} write` : 'No write yet'} />
        <MetricCard label="Asynchronous push delay" value={overview?.asyncDelayMillis} unit="ms"
          simulated={overview?.asyncDelayReason ?? true} hint="Before each asynchronous push" />
        <MetricCard label="Differences from the primary" value={grid.divergences} hint="Reachable replicas only" />
        <MetricCard label="Latest anti-entropy" value={antiEntropy?.applied} unit={antiEntropy ? 'applied' : undefined}
          hint={antiEntropy ? `To node ${antiEntropy.targetNodeId}, of ${antiEntropy.pushed} pushed` : 'Not run yet'} />
        <MetricCard label="Stale rejections" value={staleRejections} hint="Older updates refused by backups" />
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Replication health</h3>
        <HealthTable overview={overview} />
      </div>
      <p className="text-xs text-muted-foreground">
        Every latency is a measured round trip over TCP. Only the asynchronous push delay is simulated.
      </p>
    </div>
  )

  const whatToNotice = overview?.conflictRuleNote ? [...WHAT_TO_NOTICE, overview.conflictRuleNote] : WHAT_TO_NOTICE

  return (
    <ExperimentLayout
      experiment={experiment}
      howItWorks={HOW_IT_WORKS.map((sentence) => <p key={sentence}>{sentence}</p>)}
      controls={controls}
      visualisation={visualisation}
      measurements={measurements}
      whatToNotice={whatToNotice}
    />
  )
}
