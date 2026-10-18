import { useState } from 'react'
import { Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { defaultRootText } from '@/modules/mpi/labels'
import { RUN_TIMEOUT_MARGIN_MS, runTimeoutMs } from './matrixApi'
import { validateRun } from './matrixModel'

const inputClass = 'h-9 w-full rounded-md border bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 '
  + 'focus-visible:ring-ring disabled:opacity-50'

const fieldProps = (id, error) => ({
  id,
  'aria-invalid': error ? 'true' : undefined,
  'aria-describedby': error ? `${id}-error` : `${id}-hint`,
})

function Field({ id, label, error, hint, children }) {
  return (
    <div className="min-w-0 space-y-1">
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      {children}
      {error ? <p id={`${id}-error`} data-testid={`${id}-error`} className="text-xs text-destructive">{error}</p>
        : <div id={`${id}-hint`} data-testid={`${id}-hint`} className="text-xs text-muted-foreground">{hint}</div>}
    </div>
  )
}

function sizeHint(overview) {
  const { minSize, maxSize } = overview.limits ?? {}
  const range = `A whole number from ${minSize} to ${maxSize}: the rows and columns of A and B.`
  if (typeof overview.minimumSizeNow !== 'number') {
    return `${range} Every node is down now, so there is no root and a run is answered with a conflict.`
  }
  return `${range} A size below ${overview.minimumSizeNow} (one row per live node now) is refused by the backend and kept in the history.`
}

/** What this page is doing now, in words. */
function pendingText(pending, progress, stopRequested) {
  if (progress) {
    return `Running size ${progress.size} (${progress.index} of ${progress.total})...${stopRequested ? ' The series stops after this run.' : ''}`
  }
  return pending ? `Running size ${pending.size}...` : null
}

/**
 * Choose a size and an optional root, then run one size, or every configured series size one
 * after another. Every limit comes from the overview; the default size is the first served series
 * size. A crashed root stays selectable (marked down) so the backend's real 409 shows. A size
 * below minimumSizeNow is sent: the backend's recorded refusal is part of the demonstration.
 *
 * @param {object} props
 * @param {object} props.overview the MatrixOverviewDto
 * @param {boolean} props.locked a request of this page is in flight, or a timed-out one may still be running
 * @param {{size: number}|null} props.pending the single run in flight
 * @param {{index: number, total: number, size: number}|null} props.progress the series run in flight
 * @param {boolean} props.stopRequested
 * @param {Record<string,string>|null} [props.serverErrors] the 400 errors map, shown under the fields
 * @param {(size: string, rootNode: string) => void} props.onRun
 * @param {(rootNode: string) => void} props.onRunAll
 * @param {() => void} props.onStop
 */
export function MatrixControls({ overview, locked, pending, progress, stopRequested, serverErrors, onRun, onRunAll, onStop }) {
  const sizes = overview.limits?.seriesSizes ?? []
  const [form, setForm] = useState(() => ({ size: sizes.length > 0 ? String(sizes[0]) : '', rootNode: '' }))
  const [clientErrors, setClientErrors] = useState({})
  const errors = { ...(serverErrors ?? {}), ...clientErrors }
  const nodes = overview.participants ?? []
  const status = pendingText(pending, progress, stopRequested)
  const update = (name) => (event) => {
    setForm((current) => ({ ...current, [name]: event.target.value }))
    setClientErrors({})
  }
  function submit(event) {
    event.preventDefault()
    const found = validateRun(form, overview)
    setClientErrors(found)
    if (Object.keys(found).length === 0) onRun(form.size, form.rootNode)
  }
  const defaultRoot = defaultRootText(overview, nodes)
  const wait = runTimeoutMs(overview)
  return (
    <form aria-label="Run a matrix multiplication" noValidate className="space-y-3 rounded-lg border bg-card/60 p-4" onSubmit={submit}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field id="matrix-size" label="Matrix size (n)" error={errors.size} hint={sizeHint(overview)}>
          <input {...fieldProps('matrix-size', errors.size)} className={inputClass} inputMode="numeric" type="number"
            min={overview.limits?.minSize} max={overview.limits?.maxSize} step="1"
            value={form.size} onChange={update('size')} disabled={locked} />
        </Field>
        <Field id="matrix-root" label="Root node (optional)" error={errors.rootNode}
          hint="Without a choice the root is the leader elected by Experiment 4. With no leader yet, the run first asks Experiment 4 for an election, which starts its election service and heartbeats on every live node; if no leader can be elected, the run is refused. Reading this page never starts an election.">
          <select {...fieldProps('matrix-root', errors.rootNode)} className={inputClass} value={form.rootNode}
            onChange={update('rootNode')} disabled={locked}>
            <option value="">{defaultRoot}</option>
            {nodes.map((node) => (
              <option key={node.nodeId} value={String(node.nodeId)}>
                Node {node.nodeId}{node.nodeStatus === 'CRASHED' ? ' (down)' : ''}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" disabled={locked}>Run</Button>
        <Button type="button" variant="outline" disabled={locked || sizes.length === 0} onClick={() => onRunAll(form.rootNode)}>
          Run all sizes ({sizes.join(', ')})
        </Button>
        {progress && (
          <Button type="button" variant="secondary" disabled={stopRequested} onClick={onStop}>Stop after this run</Button>
        )}
      </div>
      {status && (
        <p role="status" data-testid="pending" className="inline-flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" />
          {status}
        </p>
      )}
      <p data-testid="timeout-help" className="text-xs text-muted-foreground">
        Run all sizes sends one run at a time, in the order shown. A started run cannot be cut short: stopping lets the run in
        progress finish and sends no more. The backend bounds one run at {overview.limits?.recommendedMinClientTimeoutMillis} ms
        (from its configured timeouts, not a measurement); this page waits {wait} ms (that plus a {RUN_TIMEOUT_MARGIN_MS} ms
        margin), and if no answer comes it checks the module until the run is over instead of assuming it failed.
      </p>
    </form>
  )
}
