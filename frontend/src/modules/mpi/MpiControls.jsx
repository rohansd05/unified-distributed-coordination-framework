import { useState } from 'react'
import { Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { NotAvailable } from '@/modules/rmi/CallResult'
import { defaultRootText, nodeStatusText, operationLabel } from './labels'
import { COLLECTIVE_TIMEOUT_MARGIN_MS, collectiveTimeoutMs } from './mpiApi'
import { collectiveBody, operationEntry, validateCollective } from './mpiModel'

const inputClass = 'h-9 w-full rounded-md border bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 '
  + 'focus-visible:ring-ring disabled:opacity-50'

/** The demo's default vector length; never above the overview's own maximum. */
const DEFAULT_COUNT = 1000

function Field({ id, label, error, hint, children }) {
  return (
    <div className="min-w-0 space-y-1">
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      {children}
      {error ? <p id={`${id}-error`} data-testid={`${id}-error`} className="text-xs text-destructive">{error}</p>
        : hint && <div data-testid={`${id}-hint`} className="text-xs text-muted-foreground">{hint}</div>}
    </div>
  )
}

const fieldProps = (id, error) => ({
  id,
  'aria-invalid': error ? 'true' : undefined,
  'aria-describedby': error ? `${id}-error` : undefined,
})

function defaultsFor(overview) {
  const max = overview.limits?.maxPayloadElements
  return {
    operation: 'BROADCAST',
    count: String(typeof max === 'number' ? Math.min(DEFAULT_COUNT, max) : DEFAULT_COUNT),
    seed: '',
    rootNode: '',
  }
}

/** "Broadcast needs at least 1." with the consequence for scatter and gather; n/a when every node is down. */
function MinimumHint({ entry }) {
  if (!entry) return null
  if (entry.minimumCount === null || entry.minimumCount === undefined) {
    return <p>Minimum: <NotAvailable reason="Every node is down, so there is no live participant" /></p>
  }
  return (
    <p>
      {operationLabel(entry.operation)} needs at least {entry.minimumCount}
      {entry.minimumCount > 1 ? ' (one per live node); fewer is refused by the backend and kept in the history.' : '.'}
    </p>
  )
}

/**
 * The page's own explanation of how long it waits for POST /collectives. Every figure is the
 * overview's (derived bounds, not measurements); only the margin is the page's.
 */
function TimeoutHelp({ limits, overview }) {
  const figures = [limits?.collectiveDeadlineMillis, limits?.worstCaseTransferMillis, limits?.connectTimeoutMillis,
    limits?.callTimeoutMillis, limits?.recommendedMinClientTimeoutMillis]
  if (!figures.every((value) => typeof value === 'number')) return null
  return (
    <p data-testid="timeout-help" className="text-xs text-muted-foreground">
      A collective can take up to its deadline ({limits.collectiveDeadlineMillis} ms) plus one worst-case transfer
      ({limits.worstCaseTransferMillis} ms = 3 x (connect {limits.connectTimeoutMillis} ms + call {limits.callTimeoutMillis} ms):
      a lookup, RMI&apos;s distributed garbage collection call and the method call): {limits.recommendedMinClientTimeoutMillis} ms.
      These are bounds from the configured timeouts, not measurements. This page waits {collectiveTimeoutMs(overview)} ms
      (that plus a {COLLECTIVE_TIMEOUT_MARGIN_MS} ms margin).
    </p>
  )
}

/**
 * Choose an operation, a vector length, an optional seed and an optional root, then run the
 * collective over every live node. Every limit comes from the overview. A crashed root stays
 * selectable (marked down) so the backend's real 409 shows. Crash and recover are the shared
 * cluster actions, as on the Experiment 1 and Cluster pages, so they affect every module.
 *
 * Buttons are disabled only while this page has a request in flight; a BUSY overview from another
 * tab leaves them enabled, so the server's 409 can be seen.
 *
 * @param {object} props
 * @param {object} props.overview the MpiOverviewDto
 * @param {string|null} props.pending what this page is doing, in words; null when idle
 * @param {Record<string,string>|null} [props.serverErrors] the 400 errors map, shown beside the fields
 * @param {(body: object) => void} props.onRun
 * @param {(nodeId: number) => void} props.onCrash
 * @param {(nodeId: number) => void} props.onRecover
 */
export function MpiControls({ overview, pending, serverErrors, onRun, onCrash, onRecover }) {
  const [form, setForm] = useState(() => defaultsFor(overview))
  const [clientErrors, setClientErrors] = useState({})
  const nodes = overview.participants ?? []
  const limits = overview.limits
  const busy = Boolean(pending)
  const entry = operationEntry(overview, form.operation)

  const errors = { ...(serverErrors ?? {}), ...clientErrors }
  const update = (name) => (event) => {
    setForm((current) => ({ ...current, [name]: event.target.value }))
    setClientErrors({})
  }

  function submit(event) {
    event.preventDefault()
    const found = validateCollective(form, overview)
    setClientErrors(found)
    if (Object.keys(found).length === 0) onRun(collectiveBody(form))
  }

  const defaultRoot = defaultRootText(overview, nodes)

  return (
    <div className="space-y-4">
      <form aria-label="Run a collective" noValidate className="space-y-3 rounded-lg border bg-card/60 p-4" onSubmit={submit}>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field id="mpi-operation" label="Operation" error={errors.operation} hint={entry?.description}>
            <select {...fieldProps('mpi-operation', errors.operation)} className={inputClass} value={form.operation}
              onChange={update('operation')} disabled={busy}>
              {(overview.operations ?? []).map((op) => (
                <option key={op.operation} value={op.operation}>{operationLabel(op.operation)}</option>
              ))}
            </select>
          </Field>
          <Field id="mpi-count" label="Element count" error={errors.count}
            hint={<><p>A whole number from 1 to {limits?.maxPayloadElements}.</p><MinimumHint entry={entry} /></>}>
            <input {...fieldProps('mpi-count', errors.count)} className={inputClass} inputMode="numeric"
              value={form.count} onChange={update('count')} disabled={busy} />
          </Field>
          <Field id="mpi-seed" label="Seed (optional)" error={errors.seed}
            hint={`A whole number from 0 to ${limits?.maxSeed}. Empty: the backend uses the collective id.`}>
            <input {...fieldProps('mpi-seed', errors.seed)} className={inputClass} inputMode="numeric"
              value={form.seed} onChange={update('seed')} disabled={busy} />
          </Field>
          <Field id="mpi-root" label="Root node"
            hint="Without a choice the root is the leader elected by Experiment 4. With no leader yet, the collective first asks Experiment 4 for an election, which starts its election service and heartbeats on every live node; if no leader can be elected, the collective is refused. Reading this page never starts an election.">
            <select {...fieldProps('mpi-root', null)} className={inputClass} value={form.rootNode}
              onChange={update('rootNode')} disabled={busy}>
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
          <Button type="submit" disabled={busy}>Run collective</Button>
          {busy && (
            <span role="status" data-testid="pending" className="inline-flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" />
              {pending}
            </span>
          )}
        </div>
        <TimeoutHelp limits={limits} overview={overview} />
      </form>

      <div className="space-y-2 rounded-lg border bg-card/60 p-4">
        <p className="text-sm font-medium">Crash or recover a node</p>
        <p className="text-xs text-muted-foreground">The same action as on the Cluster page: it affects every experiment on that node.</p>
        <ul className="flex flex-wrap gap-2">
          {nodes.map((node) => {
            const crashed = node.nodeStatus === 'CRASHED'
            return (
              <li key={node.nodeId}>
                <Button type="button" size="sm" variant={crashed ? 'outline' : 'destructive'} disabled={busy}
                  onClick={() => (crashed ? onRecover(node.nodeId) : onCrash(node.nodeId))}>
                  {crashed ? `Recover node ${node.nodeId}` : `Crash node ${node.nodeId}`}
                </Button>
                <span className="sr-only">, now {nodeStatusText(node.nodeStatus).toLowerCase()}</span>
              </li>
            )
          })}
        </ul>
      </div>
    </div>
  )
}
