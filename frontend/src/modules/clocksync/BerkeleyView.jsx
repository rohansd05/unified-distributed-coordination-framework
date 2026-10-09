import { useMemo } from 'react'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { isNodeCrashed } from '@/lib/clusterStatus'
import { cn } from '@/lib/utils'
import { formatMillis, formatOffset, formatSpread } from './labels'

/**
 * Berkeley Physical Clock Synchronization Visualizer (Experiment 3).
 *
 * Requirements:
 * - Second visual: Berkeley offsets before and after a round, with the spread.
 * - Diverging axis centered on zero (offsets can be positive or negative) (Change 6).
 * - R7 Honesty: null or unreached nodes show a dash with an accessible label, never 0.
 * - Unresponsive or crashed nodes in a round listed honestly as not reached, not as zero offset (R7).
 * - Outliers flagged clearly when beyond outlier threshold.
 * - Carries shared SimulatedBadge with backend's reason.
 */
export function BerkeleyView({
  latestRound,
  nodes = [],
  timeDaemonNodeId,
}) {
  // Compute max absolute offset for diverging axis scaling
  const maxAbsOffset = useMemo(() => {
    let maxVal = 100
    if (latestRound?.adjustments) {
      for (const adj of latestRound.adjustments) {
        if (adj.beforeOffsetMillis != null) maxVal = Math.max(maxVal, Math.abs(adj.beforeOffsetMillis))
        if (adj.afterOffsetMillis != null) maxVal = Math.max(maxVal, Math.abs(adj.afterOffsetMillis))
      }
    } else {
      for (const n of nodes) {
        const off = n.simulatedDrift?.offsetMillis
        if (off != null) maxVal = Math.max(maxVal, Math.abs(off))
      }
    }
    return Math.max(maxVal, 250)
  }, [latestRound, nodes])

  const simulatedReason =
    latestRound?.simulatedReason ||
    nodes.find((n) => n.simulatedDrift?.simulatedReason)?.simulatedDrift?.simulatedReason ||
    'Offsets and drift rates are simulated; UDP messages and Berkeley polling rounds are real network traffic.'

  // Map latest round adjustments by nodeId
  const adjustmentMap = useMemo(() => {
    const map = new Map()
    if (latestRound?.adjustments) {
      for (const adj of latestRound.adjustments) {
        map.set(adj.nodeId, adj)
      }
    }
    return map
  }, [latestRound])

  const participatingSet = useMemo(() => {
    return new Set(latestRound?.participatingNodes ?? [])
  }, [latestRound])

  const outlierSet = useMemo(() => {
    return new Set(latestRound?.outlierNodes ?? [])
  }, [latestRound])

  return (
    <div data-testid="berkeley-view" className="relative space-y-5 rounded-xl border bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h3 className="font-semibold text-foreground">Berkeley clock synchronization</h3>
            <SimulatedBadge reason={simulatedReason} />
          </div>
          <p className="text-xs text-muted-foreground">
            {timeDaemonNodeId ? `Coordinated by time daemon (Node ${timeDaemonNodeId}) over real UDP datagrams.` : 'Coordinated by time daemon over real UDP datagrams.'}
          </p>
        </div>

        {latestRound && (
          <div className="flex flex-wrap items-center gap-4 text-xs">
            <div className="rounded-md border border-border/80 bg-background/60 px-3 py-1.5">
              <span className="text-muted-foreground">Spread before: </span>
              <span className="font-semibold tabular-nums text-foreground">
                {formatSpread(latestRound.spreadBeforeMillis) ?? <span aria-label="Not available">—</span>}
              </span>
            </div>
            <div className="rounded-md border border-border/80 bg-background/60 px-3 py-1.5">
              <span className="text-muted-foreground">Spread after: </span>
              <span className="font-semibold tabular-nums text-emerald-400">
                {formatSpread(latestRound.spreadAfterMillis) ?? <span aria-label="Not available">—</span>}
              </span>
            </div>
            <div className="rounded-md border border-border/80 bg-background/60 px-3 py-1.5">
              <span className="text-muted-foreground">Target average: </span>
              <span className="font-semibold tabular-nums text-foreground">
                {formatOffset(latestRound.averageOffsetMillis) ?? '—'}
              </span>
            </div>
          </div>
        )}
      </div>

      {!latestRound ? (
        // Initial state before any round has run
        <div className="space-y-4">
          <p className="text-xs text-muted-foreground">
            Current simulated physical drift offsets before synchronization. Run a Berkeley round to poll all nodes, discard outliers, and compute average adjustments.
          </p>

          <div className="space-y-3">
            {nodes.map((node) => {
              const crashed = isNodeCrashed(node)
              const offset = node.simulatedDrift?.offsetMillis
              const isDaemon = node.nodeId === timeDaemonNodeId
              // Center percentage for 0 ms is 50%
              const pct = offset != null ? (offset / maxAbsOffset) * 45 : 0

              return (
                <div key={node.nodeId} data-testid={`node-drift-${node.nodeId}`} className="space-y-1.5 text-xs">
                  <div className="flex items-center justify-between">
                    <span className="font-medium text-foreground">
                      Node {node.nodeId}
                      {isDaemon && <span className="ml-1 text-[11px] text-primary">(daemon)</span>}
                      {crashed && <span className="ml-1 text-[11px] text-destructive">(crashed)</span>}
                    </span>
                    <span className="font-mono tabular-nums text-muted-foreground">
                      {crashed ? (
                        <span aria-label="Not available" className="text-muted-foreground/60">—</span>
                      ) : (
                        formatOffset(offset) ?? <span aria-label="Not available">—</span>
                      )}
                    </span>
                  </div>

                  {/* Diverging bar centered at 50% */}
                  <div className="relative h-4 w-full rounded bg-muted/40">
                    {/* Zero center line */}
                    <div className="absolute inset-y-0 left-1/2 w-px bg-border z-10" />
                    {!crashed && offset != null && (
                      <div
                        className={cn(
                          'absolute top-1 bottom-1 rounded-sm transition-all',
                          offset >= 0 ? 'bg-sky-500/80' : 'bg-amber-500/80',
                        )}
                        style={{
                          left: offset >= 0 ? '50%' : `${50 + pct}%`,
                          width: `${Math.abs(pct)}%`,
                        }}
                      />
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      ) : (
        // After Berkeley round completed
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1 text-xs">
              <span className="text-muted-foreground">Outlier threshold: </span>
              <span className="font-semibold">{latestRound.outlierThresholdMillis} ms</span>
            </div>
            <div className="space-y-1 text-xs sm:text-right">
              <span className="text-muted-foreground">Round status: </span>
              <span className="font-semibold text-emerald-400">Converged</span>
            </div>
          </div>

          {/* Diverging Axis Scale Header */}
          <div className="relative flex justify-between text-[10px] font-mono text-muted-foreground px-1">
            <span>-{maxAbsOffset} ms</span>
            <span className="font-semibold text-foreground">0 ms (center)</span>
            <span>+{maxAbsOffset} ms</span>
          </div>

          {/* Per-node Before and After Rows */}
          <div className="space-y-3 divide-y divide-border/40">
            {nodes.map((node) => {
              const crashed = isNodeCrashed(node)
              const adj = adjustmentMap.get(node.nodeId)
              const participated = participatingSet.has(node.nodeId)
              const isOutlier = outlierSet.has(node.nodeId) || Boolean(adj?.outlier)
              const isDaemon = node.nodeId === (latestRound.daemonNodeId ?? timeDaemonNodeId)

              // Handle unresponsive/crashed nodes honestly
              const unreached = !participated || crashed || !adj

              const beforeOff = adj?.beforeOffsetMillis
              const afterOff = adj?.afterOffsetMillis
              const adjVal = adj?.adjustmentMillis
              const rtt = adj?.rttMillis

              const beforePct = beforeOff != null ? (beforeOff / maxAbsOffset) * 45 : 0
              const afterPct = afterOff != null ? (afterOff / maxAbsOffset) * 45 : 0

              return (
                <div key={node.nodeId} data-testid={`node-round-${node.nodeId}`} className="pt-2.5 text-xs space-y-1.5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="font-medium text-foreground">
                      Node {node.nodeId}
                      {isDaemon && <span className="ml-1 text-[11px] text-primary">(daemon)</span>}
                      {isOutlier && <span className="ml-1.5 rounded bg-warning/20 px-1 py-0.5 text-[10px] text-warning">Outlier</span>}
                      {unreached && (
                        <span className="ml-1.5 rounded bg-destructive/15 px-1 py-0.5 text-[10px] text-destructive">
                          Not reached
                        </span>
                      )}
                    </span>

                    <div className="flex items-center gap-3 font-mono tabular-nums text-muted-foreground">
                      {unreached ? (
                        <span aria-label="Not reached" className="text-muted-foreground">
                          Not reached (no adjustment)
                        </span>
                      ) : (
                        <>
                          <span>Before: <strong className="text-foreground">{formatOffset(beforeOff)}</strong></span>
                          <span>Adj: <strong className="text-primary">{formatOffset(adjVal)}</strong></span>
                          <span>After: <strong className="text-emerald-400">{formatOffset(afterOff)}</strong></span>
                          {rtt != null && rtt > 0 && <span>RTT: {formatMillis(rtt)}</span>}
                        </>
                      )}
                    </div>
                  </div>

                  {/* Diverging Visual: Before (top track) and After (bottom track) */}
                  {!unreached && (
                    <div className="space-y-1">
                      {/* Before Track */}
                      <div className="relative h-2.5 w-full rounded bg-muted/30" title={`Before: ${formatOffset(beforeOff)}`}>
                        <div className="absolute inset-y-0 left-1/2 w-px bg-border z-10" />
                        {beforeOff != null && (
                          <div
                            className="absolute top-0.5 bottom-0.5 rounded-sm bg-amber-500/80"
                            style={{
                              left: beforeOff >= 0 ? '50%' : `${50 + beforePct}%`,
                              width: `${Math.max(1, Math.abs(beforePct))}%`,
                            }}
                          />
                        )}
                      </div>

                      {/* After Track */}
                      <div className="relative h-2.5 w-full rounded bg-muted/30" title={`After: ${formatOffset(afterOff)}`}>
                        <div className="absolute inset-y-0 left-1/2 w-px bg-border z-10" />
                        {afterOff != null && (
                          <div
                            className="absolute top-0.5 bottom-0.5 rounded-sm bg-emerald-500/90"
                            style={{
                              left: afterOff >= 0 ? '50%' : `${50 + afterPct}%`,
                              width: `${Math.max(1, Math.abs(afterPct))}%`,
                            }}
                          />
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
