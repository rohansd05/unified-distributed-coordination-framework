import { formatRelativeTime } from '@/components/events/eventFormatters'
import { isAvailable } from '@/components/experiment/isAvailable'
import { formatMillis } from './labels'

/** A figure that has not been measured: a dash, read as "Not measured yet", never 0 (R7). */
function NotMeasured() {
  return <span aria-label="Not measured yet">—</span>
}

function Figure({ value }) {
  return isAvailable(value) ? <>{value}</> : <NotMeasured />
}

const COLUMNS = [
  { key: 'backup', label: 'Backup' },
  { key: 'acks', label: 'Acknowledged' },
  { key: 'applied', label: 'Applied' },
  { key: 'duplicates', label: 'Already held' },
  { key: 'stale', label: 'Stale rejections' },
  { key: 'staleEpoch', label: 'Older epoch' },
  { key: 'failures', label: 'Failures' },
  { key: 'average', label: 'Average latency (ms)' },
  { key: 'max', label: 'Maximum latency (ms)' },
  { key: 'lastSync', label: 'Last sync' },
]

const cellClass = 'px-3 py-2 tabular-nums max-md:flex max-md:justify-between max-md:gap-3 max-md:px-0 max-md:py-1 '
  + 'max-md:before:text-muted-foreground max-md:before:content-[attr(data-label)]'

/**
 * The replication health table (docs/HANDOFF.md 7, Exp 5): one row per backup, measured by the
 * current primary from real acknowledgements and failures only. Counts are real counts; a
 * latency or last-sync time that has not been measured yet is a dash, never 0. It starts empty
 * after every takeover, because the new primary measures from then on. On a phone each row
 * stacks into label and value pairs, so the page never scrolls sideways.
 *
 * @param {object} props
 * @param {object|null} props.overview a ReplicationOverviewDto
 */
export function HealthTable({ overview }) {
  const rows = Array.isArray(overview?.health) ? overview.health : []
  const measuredBy = overview?.healthMeasuredByNodeId ?? null

  if (measuredBy === null) {
    return (
      <p data-testid="health-table" className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
        No primary yet, so nothing has been measured. The first write selects one.
      </p>
    )
  }

  return (
    <div data-testid="health-table" className="relative overflow-hidden rounded-lg border bg-card">
      <table className="w-full text-left text-sm max-md:block">
        <caption className="px-3 py-2 text-left text-xs text-muted-foreground max-md:block">
          Measured by node {measuredBy}, the current primary, from real acknowledgements only. Counts are real counts;
          a dash means not measured yet. The table starts again after every change of primary.
        </caption>
        <thead className="border-y bg-navy/30 text-xs text-muted-foreground max-md:sr-only">
          <tr>
            {COLUMNS.map((column) => (
              <th key={column.key} scope="col" className="px-3 py-2 font-medium">{column.label}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-border/60 max-md:block">
          {rows.map((row) => {
            const average = formatMillis(row.averageLatencyMillis)
            const max = formatMillis(row.maxLatencyMillis)
            return (
              <tr key={row.backupNodeId} data-testid="health-row" className="max-md:block max-md:px-3 max-md:py-2">
                <th scope="row" data-label="Backup" className={`${cellClass} font-medium`}>Node {row.backupNodeId}</th>
                <td data-label="Acknowledged" className={cellClass}><Figure value={row.acks} /></td>
                <td data-label="Applied" className={cellClass}><Figure value={row.applied} /></td>
                <td data-label="Already held" className={cellClass}><Figure value={row.duplicates} /></td>
                <td data-label="Stale rejections" className={cellClass}><Figure value={row.staleRejections} /></td>
                <td data-label="Older epoch" className={cellClass}><Figure value={row.staleEpochRejections} /></td>
                <td data-label="Failures" className={cellClass}><Figure value={row.failures} /></td>
                <td data-label="Average latency (ms)" className={cellClass}><Figure value={average} /></td>
                <td data-label="Maximum latency (ms)" className={cellClass}><Figure value={max} /></td>
                <td data-label="Last sync" className={cellClass}>
                  {row.lastSync ? <time dateTime={row.lastSync}>{formatRelativeTime(row.lastSync)}</time> : <NotMeasured />}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
