import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { Button } from '@/components/ui/button'
import { isNodeCrashed } from '@/lib/clusterStatus'
import { cn } from '@/lib/utils'
import {
  applyResultLabel,
  cellStateLabel,
  cellStateMeaning,
  formatMillis,
  modelLabel,
  nodeStatusText,
  problemText,
  pushStatusLabel,
  roleLabel,
  valueText,
} from './labels'
import { StateGlyph } from './ReplicaGrid'
import { liveBackups } from './replicaModel'

const inputClass = 'h-9 w-full rounded-md border bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 '
  + 'focus-visible:ring-ring disabled:opacity-50'

/**
 * One action's state: pending, the result, or the error (an ApiError from services/api.js).
 * After it ends, focus moves to the result panel or the error, so a keyboard or screen-reader
 * user lands on what happened. Nothing updates after unmount.
 */
function useAction(perform, onDone) {
  const [state, setState] = useState({ pending: false, error: null, result: null })
  const mountedRef = useRef(true)
  const resultRef = useRef(null)
  const errorRef = useRef(null)
  const [focusTarget, setFocusTarget] = useState(null)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => {
    if (focusTarget === 'result') resultRef.current?.focus()
    if (focusTarget === 'error') errorRef.current?.focus()
    if (focusTarget) setFocusTarget(null)
  }, [focusTarget])

  async function run(...args) {
    setState((current) => ({ ...current, pending: true, error: null }))
    try {
      const result = await perform(...args)
      if (!mountedRef.current) return
      setState({ pending: false, error: null, result })
      setFocusTarget('result')
      onDone?.(result)
    } catch (err) {
      if (!mountedRef.current) return
      setState({ pending: false, error: err, result: null })
      setFocusTarget('error')
    }
  }

  return { ...state, fieldErrors: state.error?.errors ?? {}, run, resultRef, errorRef }
}

function ActionError({ action }) {
  if (!action.error) return null
  return (
    <p ref={action.errorRef} tabIndex={-1} role="alert" data-testid="action-error" className="text-sm text-destructive focus:outline-none">
      {problemText(action.error)}
    </p>
  )
}

function ResultPanel({ action, label, children }) {
  if (!action.result) return null
  return (
    <div
      ref={action.resultRef}
      tabIndex={-1}
      role="region"
      aria-label={label}
      data-testid="action-result"
      className="rounded-md border bg-background/60 p-3 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      {children}
    </div>
  )
}

function Field({ id, label, error, hint, children }) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      {children}
      {error ? <p id={`${id}-error`} className="text-xs text-destructive">{error}</p>
        : hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  )
}

function fieldProps(id, error) {
  return { id, 'aria-invalid': error ? 'true' : undefined, 'aria-describedby': error ? `${id}-error` : undefined }
}

function Panel({ title, children, className }) {
  return (
    <fieldset className={cn('min-w-0 space-y-3 rounded-lg border bg-card/60 p-4', className)}>
      <legend className="px-1 text-sm font-semibold">{title}</legend>
      {children}
    </fieldset>
  )
}

// ------------------------------------------------------------------ write

function WriteResult({ write }) {
  const confirm = formatMillis(write.confirmMillis)
  return (
    <div className="space-y-1.5">
      <p>
        {modelLabel(write.model)} write of <span className="font-mono">{write.item.key}</span> = {valueText(write.item.value)} confirmed
        by node {write.primaryNodeId}{confirm ? ` after ${confirm} ms` : ''} (Lamport <span className="font-mono">{write.item.lamportTime}</span>).
      </p>
      {write.replicationState === 'PENDING' ? (
        <p className="text-muted-foreground">The backups have not answered yet: watch the replication window below.</p>
      ) : (
        <ul className="space-y-0.5 text-xs" aria-label="Pushes to the backups">
          {write.pushes.map((push) => (
            <li key={push.backupNodeId}>
              Node {push.backupNodeId}: {pushStatusLabel(push.status).toLowerCase()}
              {push.result ? `, ${applyResultLabel(push.result).toLowerCase()}` : ''}
              {formatMillis(push.latencyMillis) ? ` in ${formatMillis(push.latencyMillis)} ms` : ''}
              {push.detail ? ` (${push.detail})` : ''}
            </li>
          ))}
        </ul>
      )}
      {write.takeover?.previousPrimaryNodeId != null && (
        <p className="text-xs">
          This write moved the primary: node {write.takeover.newPrimaryNodeId} took over from node {write.takeover.previousPrimaryNodeId}.
        </p>
      )}
    </div>
  )
}

function WriteForm({ api, models, disabled, onAnnounce, onDone }) {
  const id = useId()
  const [form, setForm] = useState({ key: '', value: '', model: 'SYNCHRONOUS' })
  const action = useAction((body) => api.write(body), (write) => {
    onAnnounce(`${modelLabel(write.model)} write of ${write.item.key} confirmed by node ${write.primaryNodeId}.`)
    onDone()
  })
  const errors = action.fieldErrors

  return (
    <Panel title="Write through the primary">
      <form
        noValidate
        aria-label="Write a value"
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          action.run({ key: form.key, value: form.value, model: form.model })
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field id={`${id}-key`} label="Key" error={errors.key}>
            <input {...fieldProps(`${id}-key`, errors.key)} className={inputClass} value={form.key}
              onChange={(event) => setForm((current) => ({ ...current, key: event.target.value }))} />
          </Field>
          <Field id={`${id}-value`} label="Value" error={errors.value}>
            <input {...fieldProps(`${id}-value`, errors.value)} className={inputClass} value={form.value}
              onChange={(event) => setForm((current) => ({ ...current, value: event.target.value }))} />
          </Field>
        </div>
        <div role="radiogroup" aria-label="Consistency model" className="space-y-2">
          {models.map((model) => (
            <label key={model.model} className={cn(
              'flex cursor-pointer items-start gap-3 rounded-md border bg-background/40 p-2.5 text-sm focus-within:ring-2 focus-within:ring-ring',
              form.model === model.model && 'border-primary bg-primary/10',
            )}>
              <input type="radio" name={`${id}-model`} value={model.model} checked={form.model === model.model}
                onChange={() => setForm((current) => ({ ...current, model: model.model }))} className="mt-1 accent-primary" />
              <span className="space-y-0.5">
                <span className="flex flex-wrap items-center gap-2 font-medium">
                  {modelLabel(model.model)}
                  {model.simulated && <SimulatedBadge reason={model.simulatedReason ?? undefined} />}
                </span>
                <span className="block text-xs text-muted-foreground">{model.description}</span>
                <span className="block text-xs text-muted-foreground">{model.guarantee}</span>
              </span>
            </label>
          ))}
          {errors.model && <p className="text-xs text-destructive">{errors.model}</p>}
        </div>
        <Button type="submit" disabled={disabled || action.pending}>{action.pending ? 'Writing…' : 'Write'}</Button>
        <ActionError action={action} />
        <ResultPanel action={action} label="Write result">{action.result && <WriteResult write={action.result} />}</ResultPanel>
      </form>
    </Panel>
  )
}

// ------------------------------------------------------------------ read

function ReadResult({ read }) {
  return (
    <div className="space-y-1">
      <p className="flex flex-wrap items-center gap-1.5">
        <StateGlyph state={read.state} />
        <span className="font-medium">{cellStateLabel(read.state)}</span>
        <span className="text-muted-foreground">(node {read.nodeId} {cellStateMeaning(read.state)})</span>
      </p>
      {read.reachable ? (
        <p>
          Node {read.nodeId} {read.item
            ? <>holds {valueText(read.item.value)} (Lamport <span className="font-mono">{read.item.lamportTime}</span>, from node {read.item.originNode}).</>
            : <>does not hold <span className="font-mono">{read.key}</span>.</>}
        </p>
      ) : (
        <p>Node {read.nodeId} did not answer: {read.error}</p>
      )}
      {read.referenceNodeId != null && read.referenceNodeId !== read.nodeId && (
        <p className="text-xs text-muted-foreground">
          The primary, node {read.referenceNodeId}, {read.referenceItem
            ? <>holds {valueText(read.referenceItem.value)} (Lamport <span className="font-mono">{read.referenceItem.lamportTime}</span>).</>
            : 'does not hold it, or could not be read.'}
        </p>
      )}
    </div>
  )
}

function ReadForm({ api, nodes, keys, onAnnounce }) {
  const id = useId()
  const [nodeId, setNodeId] = useState('')
  const [key, setKey] = useState('')
  const chosen = nodeId === '' ? nodes[1]?.nodeId ?? nodes[0]?.nodeId ?? '' : nodeId
  const action = useAction(() => api.read(Number(chosen), key), (read) =>
    onAnnounce(`Node ${read.nodeId}: ${cellStateLabel(read.state).toLowerCase()} for ${read.key}.`))

  return (
    <Panel title="Read one replica">
      <form noValidate aria-label="Read a replica" className="space-y-3"
        onSubmit={(event) => { event.preventDefault(); action.run() }}>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field id={`${id}-node`} label="Replica">
            <select id={`${id}-node`} className={inputClass} value={chosen} onChange={(event) => setNodeId(event.target.value)}>
              {nodes.map((node) => (
                <option key={node.nodeId} value={node.nodeId}>Node {node.nodeId} ({roleLabel(node.role).toLowerCase()})</option>
              ))}
            </select>
          </Field>
          <Field id={`${id}-key`} label="Key" error={action.fieldErrors.key}>
            <input {...fieldProps(`${id}-key`, action.fieldErrors.key)} className={inputClass} value={key} list={`${id}-keys`}
              onChange={(event) => setKey(event.target.value)} />
            <datalist id={`${id}-keys`}>{keys.map((k) => <option key={k} value={k} />)}</datalist>
          </Field>
        </div>
        <Button type="submit" variant="outline" disabled={action.pending || chosen === ''}>{action.pending ? 'Reading…' : 'Read'}</Button>
        <ActionError action={action} />
        <ResultPanel action={action} label="Read result">{action.result && <ReadResult read={action.result} />}</ResultPanel>
      </form>
    </Panel>
  )
}

// ------------------------------------------------------------------ nodes

function NodeActions({ api, nodes, disabled, onAnnounce, onDone }) {
  const [overrides, setOverrides] = useState({})
  const [pendingNode, setPendingNode] = useState(null)
  const [error, setError] = useState(null)
  const [focusRequest, setFocusRequest] = useState(null)
  const buttonRefs = useRef({})
  const rowRefs = useRef({})
  const errorRef = useRef(null)

  // A response's status and role win until the overview agrees with them.
  useEffect(() => {
    setOverrides((current) => {
      const next = { ...current }
      for (const node of nodes) {
        const override = next[node.nodeId]
        if (override && override.nodeStatus === node.nodeStatus && override.role === node.role) delete next[node.nodeId]
      }
      return Object.keys(next).length === Object.keys(current).length ? current : next
    })
  }, [nodes])

  // Set in the same render as its target (drawn from the response's status and role): the counterpart
  // button, the node's own label when it has none (a recovered node that is now the primary), or the error.
  useEffect(() => {
    if (!focusRequest) return
    const target = focusRequest.kind === 'error' ? errorRef.current
      : buttonRefs.current[`${focusRequest.kind}-${focusRequest.nodeId}`] ?? rowRefs.current[focusRequest.nodeId]
    target?.focus()
    setFocusRequest(null)
  }, [focusRequest])

  async function act(nodeId, kind) {
    setPendingNode(nodeId)
    setError(null)
    try {
      const dto = kind === 'crash' ? await api.crashBackup(nodeId) : await api.recover(nodeId)
      setOverrides((current) => ({ ...current, [nodeId]: { nodeStatus: dto.nodeStatus, role: dto.role } }))
      setFocusRequest({ kind: kind === 'crash' ? 'recover' : 'crash', nodeId })
      onAnnounce(kind === 'crash'
        ? `Node ${nodeId} crashed. Its replication port is closed, for every experiment.`
        : `Node ${nodeId} recovered${dto.role === 'PRIMARY' ? ' and is the primary again' : ''}.`)
      onDone()
    } catch (err) {
      setError(err)
      setFocusRequest({ kind: 'error' })
    } finally {
      setPendingNode(null)
    }
  }

  return (
    <Panel title="Nodes">
      <ul className="space-y-2" aria-label="Nodes">
        {nodes.map((node) => {
          const status = overrides[node.nodeId]?.nodeStatus ?? node.nodeStatus
          const role = overrides[node.nodeId]?.role ?? node.role
          const crashed = isNodeCrashed({ status })
          const isPrimary = role === 'PRIMARY'
          return (
            <li key={node.nodeId} data-testid="node-action-row" className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <span ref={(el) => { rowRefs.current[node.nodeId] = el }} tabIndex={-1} data-testid="node-label"
                className="rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                <span className="font-medium">Node {node.nodeId}</span>
                <span className="text-muted-foreground">, {roleLabel(role).toLowerCase()}, {nodeStatusText(status).toLowerCase()}</span>
              </span>
              {isPrimary && !crashed ? (
                <span className="text-xs text-muted-foreground">
                  Crash the primary from the <Link to="/cluster" className="text-primary underline underline-offset-2">Cluster page</Link>.
                </span>
              ) : crashed ? (
                <Button ref={(el) => { buttonRefs.current[`recover-${node.nodeId}`] = el }} type="button" size="sm"
                  variant="outline" disabled={disabled || pendingNode !== null} onClick={() => act(node.nodeId, 'recover')}>
                  {pendingNode === node.nodeId ? 'Recovering…' : `Recover node ${node.nodeId}`}
                </Button>
              ) : (
                <Button ref={(el) => { buttonRefs.current[`crash-${node.nodeId}`] = el }} type="button" size="sm"
                  variant="outline" className="border-destructive/60 text-destructive hover:bg-destructive/10"
                  disabled={disabled || pendingNode !== null} onClick={() => act(node.nodeId, 'crash')}>
                  {pendingNode === node.nodeId ? 'Crashing…' : `Crash node ${node.nodeId}`}
                </Button>
              )}
            </li>
          )
        })}
      </ul>
      <p className="text-xs text-muted-foreground">A crash or recover here acts on the whole node, for every experiment.</p>
      {error && (
        <p ref={errorRef} tabIndex={-1} role="alert" data-testid="action-error" className="text-sm text-destructive focus:outline-none">
          {problemText(error)}
        </p>
      )}
    </Panel>
  )
}

// ------------------------------------------------------------------ anti-entropy and stale updates

function BackupSelect({ id, label, backups, value, onChange, error }) {
  return (
    <Field id={id} label={label} error={error}>
      <select {...fieldProps(id, error)} className={inputClass} value={value} disabled={backups.length === 0}
        onChange={(event) => onChange(event.target.value)}>
        {backups.length === 0 && <option value="">No live backup</option>}
        {backups.map((node) => <option key={node.nodeId} value={node.nodeId}>Node {node.nodeId}</option>)}
      </select>
    </Field>
  )
}

function AntiEntropyForm({ api, backups, disabled, onAnnounce, onDone }) {
  const id = useId()
  const [target, setTarget] = useState('')
  const chosen = backups.some((node) => String(node.nodeId) === target) ? target : String(backups[0]?.nodeId ?? '')
  const action = useAction(() => api.antiEntropy(Number(chosen)), (report) => {
    onAnnounce(report.completed
      ? `Anti-entropy to node ${report.targetNodeId}: ${report.applied} of ${report.pushed} items applied.`
      : `Anti-entropy to node ${report.targetNodeId} stopped: ${report.failure}`)
    onDone()
  })
  const report = action.result

  return (
    <form noValidate aria-label="Run anti-entropy" className="space-y-3"
      onSubmit={(event) => { event.preventDefault(); action.run() }}>
      <BackupSelect id={`${id}-target`} label="Push the primary's store to" backups={backups} value={chosen}
        onChange={setTarget} error={action.fieldErrors.targetNodeId} />
      <Button type="submit" variant="outline" disabled={disabled || action.pending || chosen === ''}>
        {action.pending ? 'Running…' : 'Run anti-entropy'}
      </Button>
      <ActionError action={action} />
      <ResultPanel action={action} label="Anti-entropy result">
        {report && (report.completed ? (
          <p>
            Node {report.sourceNodeId} pushed {report.pushed} items to node {report.targetNodeId}: {report.applied} applied,
            {' '}{report.alreadyCurrent} already held, {report.stale} stale{formatMillis(report.latencyMillis) ? `, in ${formatMillis(report.latencyMillis)} ms` : ''}.
          </p>
        ) : (
          <p>Stopped after {report.chunksAcknowledged} of {report.chunksPlanned} messages: {report.failure}</p>
        ))}
      </ResultPanel>
    </form>
  )
}

function StaleForm({ api, backups, keys, disabled, onAnnounce, onDone }) {
  const id = useId()
  const [target, setTarget] = useState('')
  const [key, setKey] = useState('')
  const [staleValue, setStaleValue] = useState('')
  const chosen = backups.some((node) => String(node.nodeId) === target) ? target : String(backups[0]?.nodeId ?? '')
  const action = useAction(() => api.injectStale({ backupNodeId: Number(chosen), key, staleValue }), (injection) => {
    onAnnounce(injection.rejected
      ? `Node ${injection.backupNodeId} rejected the stale version of ${injection.key}.`
      : `Node ${injection.backupNodeId} answered: ${pushStatusLabel(injection.push.status).toLowerCase()}.`)
    onDone()
  })
  const errors = action.fieldErrors
  const injection = action.result

  return (
    <form noValidate aria-label="Deliver a stale update" className="space-y-3"
      onSubmit={(event) => { event.preventDefault(); action.run() }}>
      <div className="grid gap-3 sm:grid-cols-3">
        <BackupSelect id={`${id}-backup`} label="Deliver to" backups={backups} value={chosen} onChange={setTarget}
          error={errors.backupNodeId} />
        <Field id={`${id}-key`} label="Key" error={errors.key}>
          <input {...fieldProps(`${id}-key`, errors.key)} className={inputClass} value={key} list={`${id}-keys`}
            onChange={(event) => setKey(event.target.value)} />
          <datalist id={`${id}-keys`}>{keys.map((k) => <option key={k} value={k} />)}</datalist>
        </Field>
        <Field id={`${id}-value`} label="Old value" error={errors.staleValue}>
          <input {...fieldProps(`${id}-value`, errors.staleValue)} className={inputClass} value={staleValue}
            onChange={(event) => setStaleValue(event.target.value)} />
        </Field>
      </div>
      <Button type="submit" variant="outline" disabled={disabled || action.pending || chosen === ''}>
        {action.pending ? 'Delivering…' : 'Deliver stale update'}
      </Button>
      <ActionError action={action} />
      <ResultPanel action={action} label="Stale update result">
        {injection && (
          <p>
            {injection.rejected
              ? `Node ${injection.backupNodeId} rejected it as stale and kept ${valueText(injection.currentItem.value)}: the stale version had Lamport ${injection.staleItem.lamportTime}, one less than ${injection.currentItem.lamportTime}.`
              : `Node ${injection.backupNodeId} answered ${pushStatusLabel(injection.push.status).toLowerCase()}${injection.push.result ? `, ${applyResultLabel(injection.push.result).toLowerCase()}` : ''}${injection.push.detail ? `: ${injection.push.detail}` : ''}.`}
          </p>
        )}
      </ResultPanel>
    </form>
  )
}

/**
 * Every Experiment 5 action, each reachable by keyboard, each disabled while the module is busy:
 * write (synchronous or asynchronous, the latter labelled simulated with the backend's reason),
 * read one replica, crash a backup or recover any crashed node (the primary is crashed from the
 * Cluster page), anti-entropy to a live backup, and a stale update delivered out of order.
 * Results are announced through onAnnounce; a 400's field messages appear beside their fields.
 *
 * @param {object} props
 * @param {object} props.overview a ReplicationOverviewDto
 * @param {string[]} props.keys keys any replica holds, for the key suggestions
 * @param {object} props.api see createReplicationApi
 * @param {boolean} props.disabled the module is busy
 * @param {(message: string) => void} props.onAnnounce
 * @param {() => void} props.onDone refresh after an action
 */
export function ReplicationControls({ overview, keys, api, disabled, onAnnounce, onDone }) {
  const nodes = Array.isArray(overview?.nodes) ? overview.nodes : []
  const models = Array.isArray(overview?.models) ? overview.models : []
  const backups = liveBackups(overview)

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <WriteForm api={api} models={models} disabled={disabled} onAnnounce={onAnnounce} onDone={onDone} />
      <ReadForm api={api} nodes={nodes} keys={keys} onAnnounce={onAnnounce} />
      <NodeActions api={api} nodes={nodes} disabled={disabled} onAnnounce={onAnnounce} onDone={onDone} />
      <Panel title="Repair and test">
        <AntiEntropyForm api={api} backups={backups} disabled={disabled} onAnnounce={onAnnounce} onDone={onDone} />
        <div className="border-t pt-3">
          <StaleForm api={api} backups={backups} keys={keys} disabled={disabled} onAnnounce={onAnnounce} onDone={onDone} />
        </div>
      </Panel>
    </div>
  )
}
