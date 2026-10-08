import { useCallback, useMemo, useState } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { Button } from '@/components/ui/button'
import { isNodeCrashed } from '@/lib/clusterStatus'
import { cn } from '@/lib/utils'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { BerkeleyView } from './BerkeleyView'
import { clockSyncApi } from './clockSyncApi'
import { ClockSyncControls } from './ClockSyncControls'
import { formatDriftRate, formatOffset, formatSpread, moduleStatusText, nodeStatusText } from './labels'
import { SpaceTimeDiagram } from './SpaceTimeDiagram'
import { useClockSync } from './useClockSync'
import { VerificationPanel } from './VerificationPanel'

const HOW_IT_WORKS = [
  'Lamport logical clocks maintain causal event ordering in distributed systems without synchronized physical clocks. Each node ticks its clock on local computation or message transmission. Every message carries the sender\'s Lamport timestamp over real UDP datagrams. When a node receives a message, it updates its clock to max(local, received) + 1 and ticks. This ensures that cause strictly precedes effect.',
  'Berkeley physical clock synchronization uses an active time daemon to periodically poll worker nodes over UDP for their physical clock offsets. The daemon calculates an average offset, discards outliers beyond a threshold, and dispatches individual adjustment values back to each node.',
  'Physical clock drift is simulated mathematically because all nodes execute co-hosted on a single hardware host, but all network coordination occurs over real UDP datagrams on loopback socket bindings.',
  'UDP transport is connectionless and unacknowledged: messages transmitted to crashed nodes report delivery status Unknown, accurately reflecting that packets leave the sender socket without confirmation.',
]

const WHAT_TO_NOTICE = [
  'Lamport rule 3 guarantees causality: every message arrow in the space-time diagram points forward in logical time, meaning the receive timestamp always strictly exceeds the send timestamp.',
  'Berkeley convergence reduces drift spread: one synchronization round polls all nodes over UDP, discards outliers, and visibly brings physical offsets close to zero.',
  'UDP honesty: sending a datagram to a crashed node returns delivery status Unknown, reflecting connectionless transmission where the sender cannot confirm whether the packet was received.',
]

/**
 * Experiment 3: Clock Synchronization (Lamport logical clocks & Berkeley physical sync).
 *
 * Built on the shared experiment-page kit (HANDOFF 8.4).
 * Follows R7 (honesty), R17 (design brief), and E2d layout rules.
 */
export function ClockSyncPage({ experiment, api = clockSyncApi }) {
  const [announcementText, setAnnouncementText] = useState('')
  const announcement = usePoliteAnnouncement(announcementText)

  const handleRoundCompleted = useCallback((round) => {
    if (round) {
      setAnnouncementText(
        `Berkeley synchronization round completed: spread reduced from ${formatSpread(round.spreadBeforeMillis)} to ${formatSpread(round.spreadAfterMillis)}.`
      )
    }
  }, [])

  const {
    overview,
    timeline,
    verification,
    loading,
    verifying,
    error,
    active,
    refresh,
    verify,
  } = useClockSync({ api, onRoundCompleted: handleRoundCompleted })

  const maxLamportValue = useMemo(() => {
    if (!overview?.nodes || overview.nodes.length === 0) return null
    return Math.max(...overview.nodes.map((n) => n.lamportValue ?? 0))
  }, [overview])

  // TODO(L1): replaced by the shared role provider in Phase 9A
  const daemonNodeId = overview?.timeDaemonNodeId ?? null

  const handleActionAnnouncement = useCallback((text) => {
    setAnnouncementText(text)
  }, [])

  const handleActionDone = useCallback(() => {
    refresh()
  }, [refresh])

  const handleVerify = useCallback(async () => {
    try {
      const res = await verify()
      if (res) {
        setAnnouncementText(
          res.passed
            ? `Causal verification passed: 0 causal violations across ${res.totalEventsChecked} events.`
            : `Causal verification failed: ${res.violationsCount} violations detected.`
        )
      }
    } catch {
      setAnnouncementText('Causal verification failed to complete.')
    }
  }, [verify])

  // Controls Section
  const controls = (
    <div className="relative space-y-4">
      {/* Polite live region for screen readers */}
      <div role="status" aria-live="polite" aria-atomic="true" className="sr-only">
        {announcement}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
        <div>
          <span>Module status: </span>
          <span className="font-semibold text-foreground">
            {overview ? moduleStatusText(overview.status) : '—'}
          </span>
          {overview?.actionInProgress && (
            <span className="ml-2 text-xs text-muted-foreground">
              ({overview.actionInProgress})
            </span>
          )}
        </div>
        <div className="text-xs text-muted-foreground">
          {daemonNodeId ? `Active time daemon: Node ${daemonNodeId}` : 'No active time daemon'}
        </div>
      </div>

      <ClockSyncControls
        overview={overview}
        api={api}
        onAnnounce={handleActionAnnouncement}
        onActionDone={handleActionDone}
        isBusy={active}
      />
    </div>
  )

  // Live Visualisation Section
  const visualisation = (
    <div className="relative space-y-6">
      {!overview && loading && (
        <div className="h-64 animate-pulse rounded-xl bg-card/60 motion-reduce:animate-none" aria-busy="true" />
      )}

      {!overview && !loading && error && (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load clock synchronization data.</p>
          <p className="mt-1 text-muted-foreground">{error.detail || error.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}

      {overview && (
        <>
          {/* Main Visual: SVG Space-Time Diagram */}
          <SpaceTimeDiagram
            nodes={overview.nodes}
            events={timeline?.events || []}
            violations={verification?.violations || []}
            retainedEventsCount={timeline?.retainedEventsCount}
            droppedEventsCount={timeline?.droppedEventsCount ?? 0}
          />

          {/* Second Visual: Berkeley Diverging Offsets Visual */}
          <BerkeleyView
            latestRound={overview.latestRound}
            nodes={overview.nodes}
            timeDaemonNodeId={daemonNodeId}
          />
        </>
      )}
    </div>
  )

  // Measurements Section
  const measurements = (
    <div className="relative space-y-6">
      {/* Divided Metric Strip (R17) */}
      <div className="grid grid-cols-2 divide-x divide-y rounded-lg border bg-card sm:grid-cols-3 sm:divide-y-0 lg:grid-cols-6">
        <MetricCard
          label="Max Lamport clock"
          value={maxLamportValue}
          hint="Highest logical clock in cluster"
        />
        <MetricCard
          label="Causal violations"
          value={verification != null ? verification.violationsCount : null}
          hint={verification ? 'From backend invariant check' : 'Run verification below'}
        />
        <MetricCard
          label="Spread before"
          value={overview?.latestRound?.spreadBeforeMillis}
          unit="ms"
          simulated={overview?.latestRound?.simulatedReason || true}
          hint="Physical drift range before round"
        />
        <MetricCard
          label="Spread after"
          value={overview?.latestRound?.spreadAfterMillis}
          unit="ms"
          simulated={overview?.latestRound?.simulatedReason || true}
          hint="Physical drift range after round"
        />
        <MetricCard
          label="Target average"
          value={overview?.latestRound?.averageOffsetMillis}
          unit="ms"
          simulated={overview?.latestRound?.simulatedReason || true}
          hint="Converged cluster average"
        />
        <MetricCard
          label="Retained events"
          value={timeline?.retainedEventsCount}
          hint={timeline?.droppedEventsCount ? `${timeline.droppedEventsCount} dropped` : 'Within capacity'}
        />
      </div>

      {/* Causal Verification Panel */}
      <VerificationPanel
        verification={verification}
        onVerify={handleVerify}
        loading={verifying}
      />

      {/* Live Per-Node Counters and Drift Parameters */}
      <div className="space-y-3">
        <h3 className="text-sm font-semibold text-foreground">Live cluster node clocks and drift</h3>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {(overview?.nodes || []).map((node) => {
            const crashed = isNodeCrashed(node)
            const isDaemon = node.nodeId === daemonNodeId
            const drift = node.simulatedDrift

            return (
              <div
                key={node.nodeId}
                data-testid={`node-card-${node.nodeId}`}
                className={cn(
                  'rounded-lg border bg-card p-3 space-y-2 text-xs transition-colors',
                  crashed ? 'border-destructive/40 bg-destructive/5' : 'border-border/80',
                )}
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-foreground">
                    Node {node.nodeId}
                  </span>
                  <span
                    className={cn(
                      'rounded px-1.5 py-0.5 text-[10px] font-medium',
                      crashed
                        ? 'bg-destructive/20 text-destructive'
                        : 'bg-emerald-500/15 text-emerald-400',
                    )}
                  >
                    {nodeStatusText(node.status)}
                  </span>
                </div>

                {isDaemon && (
                  <div className="text-[10px] font-medium text-primary">
                    Time daemon (coordinator)
                  </div>
                )}

                <div className="space-y-1 pt-1 font-mono">
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Lamport clock:</span>
                    <strong className="text-foreground tabular-nums">
                      {crashed ? <span aria-label="Not available">—</span> : (node.lamportValue ?? '—')}
                    </strong>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Drift offset:</span>
                    <span className="tabular-nums">
                      {crashed ? (
                        <span aria-label="Not available">—</span>
                      ) : (
                        formatOffset(drift?.offsetMillis) ?? <span aria-label="Not available">—</span>
                      )}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Drift rate:</span>
                    <span className="tabular-nums">
                      {crashed ? (
                        <span aria-label="Not available">—</span>
                      ) : (
                        formatDriftRate(drift?.driftRateMsPerSec) ?? <span aria-label="Not available">—</span>
                      )}
                    </span>
                  </div>
                </div>

                {drift?.simulated && (
                  <div className="pt-1">
                    <SimulatedBadge reason={drift.simulatedReason} />
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>

      {/* Backend Honesty and Transport Notes */}
      {overview?.notes && (
        <div className="space-y-1 text-xs text-muted-foreground">
          {overview.notes.map((note, idx) => (
            <p key={idx}>{note.replace(/\bUNKNOWN\b/g, 'Unknown')}</p>
          ))}
        </div>
      )}
    </div>
  )

  return (
    <ExperimentLayout
      experiment={experiment}
      howItWorks={HOW_IT_WORKS.map((text, i) => <p key={i}>{text}</p>)}
      controls={controls}
      visualisation={visualisation}
      measurements={measurements}
      whatToNotice={WHAT_TO_NOTICE}
    />
  )
}
