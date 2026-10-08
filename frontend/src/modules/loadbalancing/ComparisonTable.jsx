import { cn } from '@/lib/utils'
import { findingSentences, ms } from './finding'
import { num } from './flowModel'
import { STRATEGY_ORDER, strategyLabel } from './labels'

function Bar({ value, max, label }) {
  const width = value !== null && max ? Math.max(2, (value / max) * 100) : 0
  return (
    <div className="flex items-center gap-2">
      <span aria-hidden="true" className="block h-2 w-20 overflow-hidden rounded-full bg-muted">
        <span className="block h-2 rounded-full bg-primary" style={{ width: `${width}%` }} />
      </span>
      <span className="tabular-nums">{label}</span>
    </div>
  )
}

function NodeSplit({ nodes }) {
  const list = Array.isArray(nodes) ? nodes : []
  const top = Math.max(1, ...list.map((node) => num(node.requests) ?? 0))
  return (
    <ul aria-label="Requests per node" className="flex items-end gap-1.5">
      {list.map((node) => {
        const requests = num(node.requests)
        return (
          <li key={node.nodeId} className="flex w-7 flex-col items-center gap-0.5">
            <span aria-hidden="true" className="flex h-8 w-3 items-end rounded-sm bg-muted">
              <span className="block w-3 rounded-sm bg-primary" style={{ height: `${((requests ?? 0) / top) * 100}%` }} />
            </span>
            <span className="text-[11px] tabular-nums">
              <span className="sr-only">Node {node.nodeId}: </span>
              {requests ?? '—'}
            </span>
            <span aria-hidden="true" className="text-[10px] text-muted-foreground">{node.nodeId}</span>
          </li>
        )
      })}
    </ul>
  )
}

/**
 * "Compare all four": one row per strategy, in the order the backend runs them, with the
 * finish time (makespan), latencies, request-count spread, the requests each node served and
 * failures; the fastest and slowest rows are marked with words. Below it, the finding
 * sentences (findingSentences). Running comparisons show the phases measured so far; a failed
 * one shows its error. The table scrolls sideways inside its own box on a narrow screen.
 *
 * @param {object} props
 * @param {object|null} props.comparison the overview's latestComparison
 * @param {string} [props.note] the backend's warm-up note
 */
export function ComparisonTable({ comparison, note }) {
  if (!comparison) {
    return (
      <p data-testid="comparison-table" className="rounded-lg border border-dashed bg-card p-4 text-sm text-muted-foreground">
        No comparison yet. Press Compare all four: the same batch runs once with each strategy, and the results line up here.
      </p>
    )
  }

  const phases = Array.isArray(comparison.phases) ? comparison.phases : []
  const finished = comparison.state === 'FINISHED'
  const finding = finished ? comparison.finding : null
  const longest = Math.max(0, ...phases.map((phase) => num(phase.makespanMillis) ?? 0))
  const sentences = findingSentences(comparison)

  return (
    <div data-testid="comparison-table" className="space-y-3">
      {comparison.state === 'RUNNING' && (
        <p className="text-sm" role="status">
          Comparison running: {phases.length} of {STRATEGY_ORDER.length} strategies measured.
        </p>
      )}
      {comparison.state === 'FAILED' && (
        <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          <span className="font-semibold text-destructive">The comparison failed.</span> {comparison.error ?? ''}
        </p>
      )}

      <div className="relative overflow-x-auto rounded-lg border bg-card">
        <table className="w-full min-w-[46rem] text-left text-sm">
          <caption className="sr-only">
            The same batch of {comparison.requestCount} requests run once with each strategy
          </caption>
          <thead className="text-xs text-muted-foreground">
            <tr className="border-b">
              <th scope="col" className="px-3 py-2 font-medium">Strategy</th>
              <th scope="col" className="px-3 py-2 font-medium">Finish time</th>
              <th scope="col" className="px-3 py-2 font-medium">Average</th>
              <th scope="col" className="px-3 py-2 font-medium">p95</th>
              <th scope="col" className="px-3 py-2 font-medium">Slowest request</th>
              <th scope="col" className="px-3 py-2 font-medium">Spread</th>
              <th scope="col" className="px-3 py-2 font-medium">Requests per node</th>
              <th scope="col" className="px-3 py-2 font-medium">Failed</th>
            </tr>
          </thead>
          <tbody>
            {STRATEGY_ORDER.map((strategy) => {
              const phase = phases.find((p) => p.strategy === strategy)
              if (!phase) {
                return (
                  <tr key={strategy} data-testid={`row-${strategy}`} className="border-b last:border-0 text-muted-foreground">
                    <th scope="row" className="px-3 py-2 font-medium">{strategyLabel(strategy)}</th>
                    <td colSpan={7} className="px-3 py-2">
                      {comparison.state === 'RUNNING' ? 'Waiting to run' : 'Not run'}
                    </td>
                  </tr>
                )
              }
              const marks = []
              if (finding?.fastest === strategy) marks.push('Fastest')
              if (finding?.slowest === strategy) marks.push('Slowest')
              return (
                <tr key={strategy} data-testid={`row-${strategy}`} className="border-b last:border-0">
                  <th scope="row" className="px-3 py-2 font-medium">
                    {strategyLabel(strategy)}
                    {marks.map((mark) => (
                      <span
                        key={mark}
                        className={cn(
                          'ml-2 rounded border px-1.5 py-0.5 text-[11px] font-medium',
                          mark === 'Fastest' ? 'border-success/60 text-success' : 'border-warning/60 text-warning',
                        )}
                      >
                        {mark}
                      </span>
                    ))}
                  </th>
                  <td className="px-3 py-2">
                    <Bar value={num(phase.makespanMillis)} max={longest} label={ms(phase.makespanMillis)} />
                  </td>
                  <td className="px-3 py-2 tabular-nums">{ms(phase.averageLatencyMillis)}</td>
                  <td className="px-3 py-2 tabular-nums">{ms(phase.p95LatencyMillis)}</td>
                  <td className="px-3 py-2 tabular-nums">{ms(phase.maxLatencyMillis)}</td>
                  <td className="px-3 py-2 tabular-nums">{num(phase.loadSpread) ?? '—'}</td>
                  <td className="px-3 py-2"><NodeSplit nodes={phase.nodes} /></td>
                  <td className="px-3 py-2 tabular-nums">{num(phase.failures) ?? '—'}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {sentences.length > 0 && (
        <ul data-testid="finding" className="space-y-1.5 text-sm">
          {sentences.map((sentence) => (
            <li key={sentence} className="rounded-md border-l-4 border-primary bg-card px-3 py-2">{sentence}</li>
          ))}
        </ul>
      )}
      {note && <p className="text-xs leading-relaxed text-muted-foreground">{note}</p>}
    </div>
  )
}
