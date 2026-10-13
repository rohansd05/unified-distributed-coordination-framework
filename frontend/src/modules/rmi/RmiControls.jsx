import { useState } from 'react'
import { Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { methodLabel, nodeStatusText } from './labels'
import { callBody, parameterOf, validateCall } from './rmiModel'

const inputClass = 'h-9 w-full rounded-md border bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 '
  + 'focus-visible:ring-ring disabled:opacity-50'

/** The demo's default hash work; never above the overview's own maximum. */
const DEFAULT_ROUNDS = 100000

function Field({ id, label, error, hint, children }) {
  return (
    <div className="min-w-0 space-y-1">
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      {children}
      {error ? <p id={`${id}-error`} data-testid={`${id}-error`} className="text-xs text-destructive">{error}</p>
        : hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  )
}

const fieldProps = (id, error) => ({
  id,
  'aria-invalid': error ? 'true' : undefined,
  'aria-describedby': error ? `${id}-error` : undefined,
})

/** The first default form for an overview: the lowest live caller, the next node as target, a ping. */
function defaultsFor(overview) {
  const nodes = overview.nodes ?? []
  const caller = nodes.find((node) => node.nodeStatus !== 'CRASHED') ?? nodes[0]
  const target = nodes.find((node) => node.nodeId !== caller?.nodeId) ?? caller
  const maxRounds = parameterOf(overview, 'COMPUTE_HASH', 'rounds')?.maximum ?? overview.limits?.maxHashRounds
  return {
    fromNode: caller ? String(caller.nodeId) : '',
    targetNode: target ? String(target.nodeId) : '',
    method: 'PING',
    text: 'hello, RMI',
    rounds: String(typeof maxRounds === 'number' ? Math.min(DEFAULT_ROUNDS, maxRounds) : DEFAULT_ROUNDS),
  }
}

/**
 * Choose a caller, a target and a method with its parameters, then invoke it through RMI or
 * compare it with a direct call. A crashed node cannot be the caller (disabled, marked down); a
 * crashed target is allowed, because calling it shows the real failure. Every limit comes from
 * the overview. Crash and recover are the shared cluster actions, so they affect every module.
 *
 * Buttons are disabled only while this page has a request in flight; a BUSY overview from
 * another tab leaves them enabled, so the server's 409 can be seen.
 *
 * @param {object} props
 * @param {object} props.overview the RmiOverviewDto
 * @param {string|null} props.pending what this page is doing, in words; null when idle
 * @param {Record<string,string>|null} [props.serverErrors] the 400 errors map, shown beside the fields
 * @param {(body: object) => void} props.onInvoke
 * @param {(body: object) => void} props.onCompare
 * @param {() => void} props.onRefreshRegistries
 * @param {(nodeId: number) => void} props.onCrash
 * @param {(nodeId: number) => void} props.onRecover
 */
export function RmiControls({ overview, pending, serverErrors, onInvoke, onCompare, onRefreshRegistries, onCrash, onRecover }) {
  const [form, setForm] = useState(() => defaultsFor(overview))
  const [clientErrors, setClientErrors] = useState({})
  const nodes = overview.nodes ?? []
  const busy = Boolean(pending)
  const textParameter = parameterOf(overview, 'ECHO', 'text')
  const roundsParameter = parameterOf(overview, 'COMPUTE_HASH', 'rounds')
  const methodEntry = (overview.methods ?? []).find((entry) => entry.method === form.method)

  const errors = { ...(serverErrors ?? {}), ...clientErrors }
  const update = (name) => (event) => {
    setForm((current) => ({ ...current, [name]: event.target.value }))
    setClientErrors({})
  }

  function submit(action) {
    const found = validateCall(form, overview)
    setClientErrors(found)
    if (Object.keys(found).length === 0) action(callBody(form))
  }

  return (
    <div className="space-y-4">
      <form aria-label="Call a node" noValidate className="space-y-3 rounded-lg border bg-card/60 p-4"
        onSubmit={(event) => { event.preventDefault(); submit(onInvoke) }}>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field id="rmi-from" label="Calling node" error={errors.fromNode}>
            <select {...fieldProps('rmi-from', errors.fromNode)} className={inputClass} value={form.fromNode}
              onChange={update('fromNode')} disabled={busy}>
              {nodes.map((node) => (
                <option key={node.nodeId} value={String(node.nodeId)} disabled={node.nodeStatus === 'CRASHED'}>
                  Node {node.nodeId}{node.nodeStatus === 'CRASHED' ? ' (down)' : ''}
                </option>
              ))}
            </select>
          </Field>
          <Field id="rmi-target" label="Target node" error={errors.targetNode}
            hint="A crashed target is allowed: calling it shows the real failure.">
            <select {...fieldProps('rmi-target', errors.targetNode)} className={inputClass} value={form.targetNode}
              onChange={update('targetNode')} disabled={busy}>
              {nodes.map((node) => (
                <option key={node.nodeId} value={String(node.nodeId)}>
                  Node {node.nodeId}{node.nodeStatus === 'CRASHED' ? ' (down)' : ''}
                </option>
              ))}
            </select>
          </Field>
          <Field id="rmi-method" label="Method" error={errors.method} hint={methodEntry?.description}>
            <select {...fieldProps('rmi-method', errors.method)} className={inputClass} value={form.method}
              onChange={update('method')} disabled={busy}>
              {(overview.methods ?? []).map((entry) => (
                <option key={entry.method} value={entry.method}>{methodLabel(entry.method)}</option>
              ))}
            </select>
          </Field>
        </div>
        {form.method === 'ECHO' && (
          <Field id="rmi-text" label="Text to echo" error={errors.text}
            hint={textParameter ? `${textParameter.description} At most ${textParameter.maximum}.` : undefined}>
            <input {...fieldProps('rmi-text', errors.text)} className={inputClass} value={form.text}
              onChange={update('text')} disabled={busy} />
          </Field>
        )}
        {form.method === 'COMPUTE_HASH' && (
          <Field id="rmi-rounds" label="SHA-256 rounds" error={errors.rounds}
            hint={roundsParameter ? `${roundsParameter.description} From ${roundsParameter.minimum} to ${roundsParameter.maximum}.` : undefined}>
            <input {...fieldProps('rmi-rounds', errors.rounds)} className={inputClass} type="number" inputMode="numeric"
              min={roundsParameter?.minimum} max={roundsParameter?.maximum} value={form.rounds}
              onChange={update('rounds')} disabled={busy} />
          </Field>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" disabled={busy}>Invoke call</Button>
          <Button type="button" variant="secondary" disabled={busy} onClick={() => submit(onCompare)}>
            Compare local and remote
          </Button>
          <Button type="button" variant="outline" disabled={busy} onClick={onRefreshRegistries}>Refresh registries</Button>
          {busy && (
            <span role="status" data-testid="pending" className="inline-flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" />
              {pending}
            </span>
          )}
        </div>
        {typeof overview.limits?.callTimeoutMillis === 'number' && (
          <p className="text-xs text-muted-foreground">
            Each read on an RMI connection waits at most {overview.limits.callTimeoutMillis} ms
            (connect {overview.limits.connectTimeoutMillis} ms).
          </p>
        )}
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
