import { useEffect, useId, useRef, useState } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { Button } from '@/components/ui/button'
import { useStomp } from '@/hooks/useStomp'
import { useToast } from '@/hooks/use-toast'
import { cn } from '@/lib/utils'
import { ExecutorView } from './ExecutorView'
import { RequestTable } from './RequestTable'
import { ThreadTally } from './ThreadTally'
import { ThroughputChart } from './ThroughputChart'
import { capacityLabel, formatMillis, moduleStatusLabel, workloadLabel } from './labels'
import { multithreadingApi } from './multithreadingApi'
import { findNode, useMultithreading } from './useMultithreading'

const HOW_IT_WORKS = [
  'Each node runs requests on its own executor, a fixed team of worker threads that work on different requests at the same time.',
  'When every worker is busy, new requests wait in a bounded queue, a waiting line with a fixed number of places.',
  'When that queue is also full, the AbortPolicy refuses the next request straight away and it is marked Rejected; this pushing back is called backpressure.',
  'The hashing work is real SHA-256 computation, while the waiting workloads sleep to stand in for a network or disk delay, which is why they carry a Simulated badge.',
  'A Slow node is slow only because it is configured with one worker thread instead of four and four times the work per request, since every node runs on the same computer, the difference is configured rather than measured.',
]

const WHAT_TO_NOTICE = [
  'One batch is shared out between several worker threads: on a Fast node, compare the thread names in the tally and in the request table. A Slow node has only one thread.',
  'A full queue rejects requests at once instead of slowing the sender; a rejected request never gets a thread.',
  'A Slow node takes longer for the same hashing batch because it is configured with fewer threads and more work per request, not because its hardware is slower.',
]

const DEFAULT_FORM = { count: '100', type: 'CPU_HASH', payloadSize: '50' }

/** A number input with its label and the backend's message for that field, if any. */
function NumberField({ id, label, hint, value, onChange, error, min, max }) {
  const errorId = `${id}-error`
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min={min}
        max={max}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? errorId : undefined}
        className={cn(
          'h-9 w-full rounded-md border bg-background px-3 text-sm tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          error && 'border-destructive',
        )}
      />
      {error ? (
        <p id={errorId} className="text-xs text-destructive">{error}</p>
      ) : (
        <p className="text-xs text-muted-foreground">{hint}</p>
      )}
    </div>
  )
}

/**
 * Experiment 2, Multithreading (docs/HANDOFF.md 7): send a burst of requests to one node,
 * watch its executor drain them on several worker threads, overflow its bounded queue with
 * the backpressure demo, and read the per-request results. Built on the shared kit.
 *
 * Live through /topic/modules/multithreading (see useMultithreading). Honest about missing
 * data: a node without a running executor shows "—" and guidance, never 0.
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry (lab, id, title, concept)
 * @param {object} [props.api] see createMultithreadingApi; for tests
 */
export function MultithreadingPage({ experiment, api = multithreadingApi }) {
  const [selectedNodeId, setSelectedNodeId] = useState(1)
  const { overview, requests, throughput, loading, error, refresh } = useMultithreading({ api, selectedNodeId })
  const { status: connection } = useStomp()
  const { toast } = useToast()
  const [form, setForm] = useState(DEFAULT_FORM)
  const [fieldErrors, setFieldErrors] = useState({})
  const [actionError, setActionError] = useState(null)
  const [pending, setPending] = useState(null)
  const [lastBatch, setLastBatch] = useState(null)
  const mountedRef = useRef(true)
  const formId = useId()

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const nodes = overview?.nodes ?? []
  const node = findNode(overview, selectedNodeId)
  const stats = node?.nodeStatus === 'CRASHED' ? null : node?.stats ?? null
  const workloads = overview?.workloads ?? []
  const unreachable = connection === 'reconnecting' || connection === 'error' || Boolean(error)
  const hasData = Boolean(overview)

  const setField = (name) => (value) => {
    setForm((current) => ({ ...current, [name]: value }))
    setFieldErrors((current) => ({ ...current, [name]: undefined }))
  }

  async function run(kind, call, describe) {
    setPending(kind)
    setActionError(null)
    setFieldErrors({})
    try {
      const batch = await call()
      if (!mountedRef.current) return
      setLastBatch(batch)
      toast({ title: describe(batch), description: `${batch.accepted} accepted, ${batch.rejected} rejected.` })
      refresh()
    } catch (err) {
      if (!mountedRef.current) return
      setFieldErrors(err?.errors ?? {})
      const message = err?.detail || err?.message || 'The request failed.'
      setActionError(message)
      toast({ variant: 'destructive', title: err?.title || 'Request failed', description: message })
    } finally {
      if (mountedRef.current) setPending(null)
    }
  }

  const sendBatch = (event) => {
    event.preventDefault()
    const body = { count: Number(form.count), type: form.type, payloadSize: Number(form.payloadSize) }
    run('batch', () => api.submitBatch(selectedNodeId, body), (batch) => `Batch sent to node ${batch.nodeId}`)
  }

  const runBackpressure = () =>
    run('backpressure', () => api.runBackpressure(selectedNodeId), (batch) => `Backpressure demo on node ${batch.nodeId}`)

  const controls = (
    <div className="space-y-4">
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

      <div className="grid gap-6 lg:grid-cols-2">
        <fieldset className="space-y-3">
          <legend className="text-sm font-medium">Node</legend>
          {!hasData && <p className="text-sm text-muted-foreground">{loading ? 'Loading nodes…' : 'Nodes are not available.'}</p>}
          <div className="grid gap-2 sm:grid-cols-2">
            {nodes.map((candidate) => {
              const crashed = candidate.nodeStatus === 'CRASHED'
              const checked = candidate.nodeId === selectedNodeId
              return (
                <label
                  key={candidate.nodeId}
                  className={cn(
                    'flex cursor-pointer items-start gap-3 rounded-lg border bg-card p-3 text-sm focus-within:ring-2 focus-within:ring-ring',
                    checked && 'border-primary bg-primary/10',
                    crashed && 'border-dashed border-destructive/60',
                  )}
                >
                  <input
                    type="radio"
                    name={`${formId}-node`}
                    value={candidate.nodeId}
                    checked={checked}
                    onChange={() => setSelectedNodeId(candidate.nodeId)}
                    className="mt-1 accent-primary"
                  />
                  <span className="space-y-0.5">
                    <span className="block font-medium">
                      Node {candidate.nodeId}
                      {crashed && <span className="ml-2 text-destructive">Crashed</span>}
                    </span>
                    <span className="block text-xs text-muted-foreground">
                      {capacityLabel(candidate.capacity)}, {candidate.threads} {candidate.threads === 1 ? 'thread' : 'threads'}, {candidate.workMultiplier}× work
                    </span>
                    <span className="block text-xs text-muted-foreground">
                      {candidate.serviceRunning ? 'Executor running' : crashed ? 'Executor shut down' : 'Not started yet'}
                    </span>
                  </span>
                </label>
              )
            })}
          </div>
          {overview?.capacityNote && <p className="text-xs leading-relaxed text-muted-foreground">{overview.capacityNote}</p>}
        </fieldset>

        <form onSubmit={sendBatch} noValidate className="space-y-4" aria-label="Send a batch">
          <div className="grid gap-4 sm:grid-cols-2">
            <NumberField
              id={`${formId}-count`}
              label="Requests in the batch"
              hint="1 to 1000"
              min={1}
              max={1000}
              value={form.count}
              onChange={setField('count')}
              error={fieldErrors.count}
            />
            <NumberField
              id={`${formId}-payload`}
              label="Work units per request"
              hint="1 to 5000, multiplied by the node's work factor"
              min={1}
              max={5000}
              value={form.payloadSize}
              onChange={setField('payloadSize')}
              error={fieldErrors.payloadSize}
            />
          </div>

          <fieldset className="space-y-2" aria-describedby={fieldErrors.type ? `${formId}-type-error` : undefined}>
            <legend className="text-sm font-medium">Workload</legend>
            {workloads.map((workload) => (
              <label key={workload.type} className="flex cursor-pointer items-start gap-3 rounded-md border bg-card p-2.5 text-sm focus-within:ring-2 focus-within:ring-ring">
                <input
                  type="radio"
                  name={`${formId}-workload`}
                  value={workload.type}
                  checked={form.type === workload.type}
                  onChange={() => setField('type')(workload.type)}
                  className="mt-1 accent-primary"
                />
                <span className="space-y-0.5">
                  <span className="flex flex-wrap items-center gap-2 font-medium">
                    {workloadLabel(workload.type)}
                    {workload.simulated && <SimulatedBadge reason={workload.simulatedReason} />}
                  </span>
                  <span className="block text-xs text-muted-foreground">{workload.description}</span>
                </span>
              </label>
            ))}
            {fieldErrors.type && <p id={`${formId}-type-error`} className="text-xs text-destructive">{fieldErrors.type}</p>}
          </fieldset>

          <div className="flex flex-wrap items-center gap-3">
            <Button type="submit" disabled={!hasData || pending !== null}>
              {pending === 'batch' ? 'Sending…' : 'Send batch'}
            </Button>
            <Button type="button" variant="outline" disabled={!hasData || pending !== null} onClick={runBackpressure}>
              {pending === 'backpressure' ? 'Starting…' : 'Run backpressure demo'}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            The backpressure demo sends this node more requests than its threads and queue can hold, all at once, so some are rejected.
          </p>

          <p className="text-sm">
            Module status: <span className="font-medium">{overview ? moduleStatusLabel(overview.status) : '—'}</span>
            {overview?.actionInProgress && <span className="text-muted-foreground"> ({overview.actionInProgress})</span>}
          </p>
          {actionError && <p role="alert" className="text-sm text-destructive">{actionError}</p>}
          {lastBatch && (
            <p className="text-sm" data-testid="last-batch">
              {lastBatch.kind === 'BACKPRESSURE' ? 'Backpressure demo' : 'Batch'}{' '}
              <span className="font-mono">{lastBatch.batchId}</span> on node {lastBatch.nodeId}:{' '}
              {lastBatch.rejected} of {lastBatch.requested} rejected, {lastBatch.accepted} accepted.
            </p>
          )}
        </form>
      </div>
    </div>
  )

  const visualisation = (
    <div className="space-y-4">
      {!hasData && loading && <div className="h-64 animate-pulse rounded-xl bg-card/60 motion-reduce:animate-none" aria-busy="true" />}
      {!hasData && !loading && error && (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load the multithreading data.</p>
          <p className="mt-1 text-muted-foreground">{error.detail || error.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}
      {hasData && (
        <>
          <ExecutorView node={node} />
          <div className="grid gap-4 lg:grid-cols-2">
            <ThroughputChart samples={throughput} />
            <div className="rounded-lg border bg-card p-4">
              <ThreadTally requests={requests} />
            </div>
          </div>
          <div className="space-y-2">
            <h3 className="text-sm font-semibold">Latest requests on node {selectedNodeId}</h3>
            <RequestTable requests={requests} />
          </div>
        </>
      )}
    </div>
  )

  const hasSamples = Boolean(stats) && stats.sampleCount > 0
  const measurements = (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">
        Read from node {selectedNodeId}&apos;s executor{stats ? '' : ', which is not running, so nothing is shown'}.
      </p>
      <div className="grid grid-cols-2 divide-x divide-y rounded-lg border bg-card sm:grid-cols-3 sm:divide-y-0 lg:grid-cols-6">
        <MetricCard label="Busy threads" value={stats?.activeThreads} unit={stats ? `of ${stats.maxPoolSize}` : undefined} />
        <MetricCard label="Queued" value={stats?.queuedRequests} unit={stats ? `of ${stats.queueCapacity}` : undefined} />
        <MetricCard label="Completed tasks" value={stats?.completedTasks} hint="Since the executor started" />
        <MetricCard label="Throughput" value={stats?.requestsPerSecond} unit="per second" hint="Over a sliding window" />
        <MetricCard label="Average latency" value={hasSamples ? formatMillis(stats.averageResponseTimeMillis) : null} unit="ms" />
        <MetricCard label="p95 latency" value={hasSamples ? formatMillis(stats.p95ResponseTimeMillis) : null} unit="ms" />
      </div>
    </div>
  )

  return (
    <ExperimentLayout
      experiment={experiment}
      howItWorks={HOW_IT_WORKS.map((sentence) => <p key={sentence}>{sentence}</p>)}
      controls={controls}
      visualisation={visualisation}
      measurements={measurements}
      whatToNotice={WHAT_TO_NOTICE}
    />
  )
}
