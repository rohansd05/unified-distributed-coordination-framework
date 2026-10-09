import { chartModel, num, resultRows } from './runModel'

const BAR_HEIGHT = 18
const GAP = 6
const LABEL_WIDTH = 120
const WIDTH = 520

/**
 * A hand-drawn SVG bar chart (no chart library): one horizontal bar per key, largest first, at
 * most 20. It is one image with an aria-label that states the data; the table beside it is the
 * accessible, complete alternative. Values the backend did not send are left out, never drawn
 * as 0.
 */
function ResultsChart({ model }) {
  if (model.bars.length === 0) {
    return null
  }
  const max = Math.max(...model.bars.map((bar) => bar.value))
  const height = model.bars.length * (BAR_HEIGHT + GAP)
  const scale = (value) => (max > 0 ? ((WIDTH - LABEL_WIDTH - 70) * value) / max : 0)
  return (
    <div className="overflow-x-auto rounded-lg border bg-card p-3">
      <svg
        role="img"
        aria-label={model.summary}
        data-testid="results-chart"
        viewBox={`0 0 ${WIDTH} ${height}`}
        width="100%"
        className="min-w-[22rem]"
      >
        {model.bars.map((bar, index) => {
          const y = index * (BAR_HEIGHT + GAP)
          return (
            <g key={bar.key}>
              <text x={LABEL_WIDTH - 6} y={y + 13} textAnchor="end" className="fill-muted-foreground font-mono text-[11px]">
                {bar.key.length > 16 ? `${bar.key.slice(0, 15)}…` : bar.key}
              </text>
              <rect x={LABEL_WIDTH} y={y} width={Math.max(1, scale(bar.value))} height={BAR_HEIGHT} rx="3" className="fill-primary/80" />
              <text x={LABEL_WIDTH + scale(bar.value) + 6} y={y + 13} className="fill-foreground text-[11px] tabular-nums">
                {bar.value}
              </text>
            </g>
          )
        })}
      </svg>
      <p className="mt-2 text-xs text-muted-foreground">
        {model.metric === 'average' ? 'Average latency in ms per node.' : 'Count per key.'}
        {model.omitted > 0 ? ` The ${model.omitted} smaller listed keys are in the table only.` : ''}
      </p>
    </div>
  )
}

/**
 * The run's results: the backend's rows (sorted by key, at most result-rows-max) in a table, a
 * chart of the largest values, and a plain note when the list is truncated. An empty result
 * shows the backend's own notice, word for word.
 *
 * @param {object} props
 * @param {object|null} props.run the shown RunDto
 * @param {number|null} [props.rowsMax] limits.resultRowsMax from the overview
 */
export function ResultsPanel({ run, rowsMax = null }) {
  const report = run?.report ?? null
  if (!run || run.state === 'RUNNING') {
    return <p className="text-sm text-muted-foreground">Results appear here when a run completes.</p>
  }
  if (run.state === 'FAILED' || !report) {
    return <p className="text-sm text-muted-foreground">This run failed, so it has no results.</p>
  }
  const rows = resultRows(run)
  const total = num(report.resultKeys)
  if (rows.length === 0) {
    return (
      <div data-testid="results-empty" className="rounded-lg border border-dashed bg-card p-4 text-sm">
        <p className="font-medium">No result keys.</p>
        {run.notice && <p className="mt-1 text-muted-foreground">{run.notice}</p>}
      </div>
    )
  }
  const model = chartModel(run)
  const average = model.metric === 'average'
  return (
    <div data-testid="results-panel" className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
      <div className="space-y-2">
        <div className="max-h-80 overflow-auto rounded-lg border">
          <table className="w-full text-sm">
            <caption className="sr-only">Results of {run.jobTitle ?? run.jobId}</caption>
            <thead className="sticky top-0 bg-navy/90 text-left text-xs text-muted-foreground">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">Key</th>
                <th scope="col" className="px-3 py-2 font-medium">{average ? 'Average' : 'Value'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/60">
              {rows.map((row) => (
                <tr key={row.key}>
                  <td className="px-3 py-1.5 font-mono text-xs">{row.key}</td>
                  <td className="px-3 py-1.5 tabular-nums">{row.display}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="text-xs text-muted-foreground" data-testid="results-count">
          {report.resultsTruncated
            ? `Showing the first ${rows.length} of ${total ?? '—'} keys, by key; the backend lists at most ${rowsMax ?? rows.length}.`
            : `All ${total ?? rows.length} keys.`}
        </p>
      </div>
      <ResultsChart model={model} />
    </div>
  )
}
