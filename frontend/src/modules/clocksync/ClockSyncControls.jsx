import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { isNodeCrashed, isNodeUp } from '@/lib/clusterStatus'
import { cn } from '@/lib/utils'
import {
  deliveryStatusText,
  formatDriftRate,
  formatOffset,
} from './labels'

/**
 * Interactive Controls for Experiment 3 (Clock Synchronization).
 *
 * Rules:
 * - Every action reachable by keyboard.
 * - Results announced via onAnnounce callback (throttled by usePoliteAnnouncement).
 * - Focus management: moves to result panel or error alert after action.
 * - Traffic limits come from overview.limits, not hard-coded values (Change 5).
 * - Displays RFC 7807 ProblemDetail error messages clearly.
 * - Shows UDP honesty note when sending messages to crashed nodes.
 */
export function ClockSyncControls({
  overview,
  api,
  onAnnounce,
  onActionDone,
  isBusy = false,
}) {
  const [activeTab, setActiveTab] = useState('lamport') // 'lamport' | 'berkeley'
  const [pendingAction, setPendingAction] = useState(null)
  const [lastResult, setLastResult] = useState(null)
  const [actionError, setActionError] = useState(null)
  const [fieldErrors, setFieldErrors] = useState({})
  const [focusTarget, setFocusTarget] = useState(null)

  // Local Event Form
  const [localNodeId, setLocalNodeId] = useState(1)
  const [localDesc, setLocalDesc] = useState('')

  // Message Form
  const [msgFromId, setMsgFromId] = useState(1)
  const [msgToId, setMsgToId] = useState(2)
  const [msgPayload, setMsgPayload] = useState('Lamport ping')

  // Traffic Form
  const limits = overview?.limits
  const [trafficSeconds, setTrafficSeconds] = useState(5)
  const [trafficRate, setTrafficRate] = useState(4)

  // Sync limits defaults when overview loads
  useEffect(() => {
    if (limits) {
      if (limits.defaultTrafficSeconds != null) setTrafficSeconds(limits.defaultTrafficSeconds)
      if (limits.defaultMessagesPerSecond != null) setTrafficRate(limits.defaultMessagesPerSecond)
    }
  }, [limits])

  // Berkeley Round Form
  const [outlierThreshold, setOutlierThreshold] = useState(400)

  // Drift Form
  const [driftNodeId, setDriftNodeId] = useState(2)
  const [driftOffset, setDriftOffset] = useState(100)
  const [driftRate, setDriftRate] = useState(1.5)

  // Refs for focus management
  const resultRef = useRef(null)
  const errorRef = useRef(null)
  const nodeLabelRefs = useRef({})
  const formId = useId()

  const liveNodes = (overview?.nodes || []).filter(isNodeUp)
  const allNodes = overview?.nodes || []

  // Ensure selected sender is alive
  useEffect(() => {
    if (liveNodes.length > 0) {
      if (!liveNodes.some((n) => n.nodeId === localNodeId)) {
        setLocalNodeId(liveNodes[0].nodeId)
      }
      if (!liveNodes.some((n) => n.nodeId === msgFromId)) {
        setMsgFromId(liveNodes[0].nodeId)
      }
    }
  }, [liveNodes, localNodeId, msgFromId])

  // Move focus after an action completes or fails
  useEffect(() => {
    if (!focusTarget) return
    if (focusTarget === 'result') {
      resultRef.current?.focus()
    } else if (focusTarget === 'error') {
      errorRef.current?.focus()
    } else if (focusTarget.nodeId != null) {
      const el = nodeLabelRefs.current[focusTarget.nodeId]
      el?.focus()
    }
    setFocusTarget(null)
  }, [focusTarget])

  function handleApiError(err) {
    setActionError(err)
    if (err.errors) {
      setFieldErrors(err.errors)
    } else {
      setFieldErrors({})
    }
    setFocusTarget('error')
  }

  async function handleRecordLocalEvent(e) {
    e.preventDefault()
    setPendingAction('local')
    setActionError(null)
    setFieldErrors({})
    try {
      const res = await api.recordLocalEvent(localNodeId, { description: localDesc.trim() || undefined })
      setLastResult({ kind: 'local', data: res })
      setFocusTarget('result')
      onAnnounce(`Local event recorded on Node ${res.nodeId}, Lamport time is now ${res.lamportTime}.`)
      onActionDone()
    } catch (err) {
      handleApiError(err)
    } finally {
      setPendingAction(null)
    }
  }

  async function handleSendMessage(e) {
    e.preventDefault()
    setPendingAction('message')
    setActionError(null)
    setFieldErrors({})
    try {
      const res = await api.sendMessage({
        from: msgFromId,
        to: msgToId,
        payload: msgPayload.trim() || undefined,
      })
      setLastResult({ kind: 'message', data: res })
      setFocusTarget('result')
      onAnnounce(
        `Message sent from Node ${res.from} to Node ${res.to} with Lamport time ${res.lamportTime}. Delivery status is ${deliveryStatusText(res.deliveryStatus)}.`
      )
      onActionDone()
    } catch (err) {
      handleApiError(err)
    } finally {
      setPendingAction(null)
    }
  }

  async function handleStartTraffic(e) {
    e.preventDefault()
    setPendingAction('traffic')
    setActionError(null)
    setFieldErrors({})
    try {
      const res = await api.startTraffic({
        seconds: Number(trafficSeconds),
        messagesPerSecond: Number(trafficRate),
      })
      setLastResult({ kind: 'traffic', data: res })
      setFocusTarget('result')
      onAnnounce(`Random traffic session accepted: running for ${res.seconds} seconds at ${res.messagesPerSecond} messages per second.`)
      onActionDone()
    } catch (err) {
      handleApiError(err)
    } finally {
      setPendingAction(null)
    }
  }

  async function handleRunBerkeleyRound(e) {
    e.preventDefault()
    setPendingAction('berkeley')
    setActionError(null)
    setFieldErrors({})
    try {
      const res = await api.runBerkeleyRound({
        outlierThresholdMillis: Number(outlierThreshold),
      })
      setLastResult({ kind: 'berkeley', data: res })
      setFocusTarget('result')
      onAnnounce(`Berkeley synchronization round accepted with outlier threshold ${outlierThreshold} ms.`)
      onActionDone()
    } catch (err) {
      handleApiError(err)
    } finally {
      setPendingAction(null)
    }
  }

  async function handleUpdateDrift(e) {
    e.preventDefault()
    setPendingAction('drift')
    setActionError(null)
    setFieldErrors({})
    try {
      const res = await api.updateDrift(driftNodeId, {
        initialOffsetMillis: Number(driftOffset),
        driftRateMsPerSec: Number(driftRate),
      })
      setLastResult({ kind: 'drift', data: res })
      setFocusTarget('result')
      onAnnounce(`Simulated drift updated for Node ${res.nodeId}: offset ${formatOffset(res.offsetMillis)}, rate ${formatDriftRate(res.driftRateMsPerSec)}.`)
      onActionDone()
    } catch (err) {
      handleApiError(err)
    } finally {
      setPendingAction(null)
    }
  }

  return (
    <div data-testid="clocksync-controls" className="relative space-y-5 rounded-xl border bg-card p-5">
      {/* Tab navigation */}
      <div role="tablist" aria-label="Clock synchronization control modes" className="flex border-b border-border">
        <button
          type="button"
          role="tab"
          id={`${formId}-tab-lamport`}
          aria-selected={activeTab === 'lamport'}
          aria-controls={`${formId}-panel-lamport`}
          onClick={() => setActiveTab('lamport')}
          className={cn(
            'px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            activeTab === 'lamport'
              ? 'border-primary text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground',
          )}
        >
          Lamport logical clock
        </button>
        <button
          type="button"
          role="tab"
          id={`${formId}-tab-berkeley`}
          aria-selected={activeTab === 'berkeley'}
          aria-controls={`${formId}-panel-berkeley`}
          onClick={() => setActiveTab('berkeley')}
          className={cn(
            'px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            activeTab === 'berkeley'
              ? 'border-primary text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground',
          )}
        >
          Berkeley physical sync
        </button>
      </div>

      {/* Lamport Tab Panel */}
      {activeTab === 'lamport' && (
        <div
          role="tabpanel"
          id={`${formId}-panel-lamport`}
          aria-labelledby={`${formId}-tab-lamport`}
          className="space-y-6 pt-1"
        >
          {/* Action 1: Record Local Event */}
          <form onSubmit={handleRecordLocalEvent} className="space-y-3 rounded-lg border bg-background/50 p-4">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Rule 1: Record local event (ticks local clock)
            </h4>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="space-y-1">
                <label htmlFor={`${formId}-local-node`} className="text-xs font-medium">Node</label>
                <select
                  id={`${formId}-local-node`}
                  value={localNodeId}
                  onChange={(e) => setLocalNodeId(Number(e.target.value))}
                  disabled={isBusy || liveNodes.length === 0}
                  className="h-8 w-full rounded-md border bg-background px-2 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {allNodes.map((n) => (
                    <option key={n.nodeId} value={n.nodeId} disabled={isNodeCrashed(n)}>
                      Node {n.nodeId} {isNodeCrashed(n) ? '(down)' : ''}
                    </option>
                  ))}
                </select>
              </div>

              <div className="space-y-1 sm:col-span-2">
                <label htmlFor={`${formId}-local-desc`} className="text-xs font-medium">Description</label>
                <input
                  id={`${formId}-local-desc`}
                  type="text"
                  value={localDesc}
                  placeholder="Local computation event"
                  onChange={(e) => setLocalDesc(e.target.value)}
                  disabled={isBusy}
                  className="h-8 w-full rounded-md border bg-background px-3 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
              </div>
            </div>
            <Button type="submit" size="sm" disabled={isBusy || liveNodes.length === 0 || pendingAction !== null} className="text-xs">
              {pendingAction === 'local' ? 'Recording…' : 'Record local event'}
            </Button>
          </form>

          {/* Action 2: Send UDP Message */}
          <form onSubmit={handleSendMessage} className="space-y-3 rounded-lg border bg-background/50 p-4">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Rule 2: Send point-to-point message over UDP
            </h4>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="space-y-1">
                <label htmlFor={`${formId}-msg-from`} className="text-xs font-medium">Sender</label>
                <select
                  id={`${formId}-msg-from`}
                  value={msgFromId}
                  onChange={(e) => setMsgFromId(Number(e.target.value))}
                  disabled={isBusy || liveNodes.length === 0}
                  className="h-8 w-full rounded-md border bg-background px-2 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {allNodes.map((n) => (
                    <option key={n.nodeId} value={n.nodeId} disabled={isNodeCrashed(n)}>
                      Node {n.nodeId} {isNodeCrashed(n) ? '(down)' : ''}
                    </option>
                  ))}
                </select>
              </div>

              <div className="space-y-1">
                <label htmlFor={`${formId}-msg-to`} className="text-xs font-medium">Receiver</label>
                <select
                  id={`${formId}-msg-to`}
                  value={msgToId}
                  onChange={(e) => setMsgToId(Number(e.target.value))}
                  disabled={isBusy}
                  className="h-8 w-full rounded-md border bg-background px-2 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {allNodes.map((n) => (
                    <option key={n.nodeId} value={n.nodeId}>
                      Node {n.nodeId} {isNodeCrashed(n) ? '(down)' : ''}
                    </option>
                  ))}
                </select>
              </div>

              <div className="space-y-1">
                <label htmlFor={`${formId}-msg-payload`} className="text-xs font-medium">Payload</label>
                <input
                  id={`${formId}-msg-payload`}
                  type="text"
                  value={msgPayload}
                  placeholder="Ping message"
                  onChange={(e) => setMsgPayload(e.target.value)}
                  disabled={isBusy}
                  className="h-8 w-full rounded-md border bg-background px-3 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
              </div>
            </div>
            <Button type="submit" size="sm" disabled={isBusy || liveNodes.length === 0 || pendingAction !== null} className="text-xs">
              {pendingAction === 'message' ? 'Sending…' : 'Send message'}
            </Button>
          </form>

          {/* Action 3: Random Traffic Session */}
          <form onSubmit={handleStartTraffic} className="space-y-3 rounded-lg border bg-background/50 p-4">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Random traffic burst session
            </h4>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1">
                <label htmlFor={`${formId}-traffic-sec`} className="text-xs font-medium">Duration (seconds)</label>
                <input
                  id={`${formId}-traffic-sec`}
                  type="number"
                  min={1}
                  max={limits?.maxTrafficSeconds ?? 30}
                  value={trafficSeconds}
                  onChange={(e) => setTrafficSeconds(e.target.value)}
                  disabled={isBusy}
                  className="h-8 w-full rounded-md border bg-background px-3 text-xs tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                <span className="text-[10px] text-muted-foreground">
                  Limit: 1 to {limits?.maxTrafficSeconds ?? 30} seconds
                </span>
                {fieldErrors.seconds && (
                  <p className="text-[10px] text-destructive">{fieldErrors.seconds}</p>
                )}
              </div>

              <div className="space-y-1">
                <label htmlFor={`${formId}-traffic-rate`} className="text-xs font-medium">Messages per second</label>
                <input
                  id={`${formId}-traffic-rate`}
                  type="number"
                  min={1}
                  max={limits?.maxMessagesPerSecond ?? 20}
                  value={trafficRate}
                  onChange={(e) => setTrafficRate(e.target.value)}
                  disabled={isBusy}
                  className="h-8 w-full rounded-md border bg-background px-3 text-xs tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                <span className="text-[10px] text-muted-foreground">
                  Limit: 1 to {limits?.maxMessagesPerSecond ?? 20} msg/s
                </span>
                {fieldErrors.messagesPerSecond && (
                  <p className="text-[10px] text-destructive">{fieldErrors.messagesPerSecond}</p>
                )}
              </div>
            </div>
            <Button type="submit" size="sm" disabled={isBusy || liveNodes.length < 2 || pendingAction !== null} className="text-xs">
              {pendingAction === 'traffic' ? 'Starting…' : 'Start traffic burst'}
            </Button>
            {liveNodes.length < 2 && (
              <p className="text-[11px] text-muted-foreground">At least two live nodes are required to generate message traffic.</p>
            )}
          </form>
        </div>
      )}

      {/* Berkeley Tab Panel */}
      {activeTab === 'berkeley' && (
        <div
          role="tabpanel"
          id={`${formId}-panel-berkeley`}
          aria-labelledby={`${formId}-tab-berkeley`}
          className="space-y-6 pt-1"
        >
          {/* Action 4: Run Berkeley Round */}
          <form onSubmit={handleRunBerkeleyRound} className="space-y-3 rounded-lg border bg-background/50 p-4">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Run Berkeley synchronization round
            </h4>
            <div className="space-y-1 max-w-xs">
              <label htmlFor={`${formId}-round-threshold`} className="text-xs font-medium">
                Outlier threshold (ms)
              </label>
              <input
                id={`${formId}-round-threshold`}
                type="number"
                min={0}
                value={outlierThreshold}
                onChange={(e) => setOutlierThreshold(e.target.value)}
                disabled={isBusy}
                className="h-8 w-full rounded-md border bg-background px-3 text-xs tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
              <span className="text-[10px] text-muted-foreground">
                Nodes with offsets beyond this threshold are excluded from the average.
              </span>
              {fieldErrors.outlierThresholdMillis && (
                <p className="text-[10px] text-destructive">{fieldErrors.outlierThresholdMillis}</p>
              )}
            </div>
            <Button type="submit" size="sm" disabled={isBusy || liveNodes.length === 0 || pendingAction !== null} className="text-xs">
              {pendingAction === 'berkeley' ? 'Requesting…' : 'Run synchronization round'}
            </Button>
          </form>

          {/* Action 5: Adjust Simulated Drift */}
          <form onSubmit={handleUpdateDrift} className="space-y-3 rounded-lg border bg-background/50 p-4">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Adjust simulated physical clock parameters
            </h4>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="space-y-1">
                <label htmlFor={`${formId}-drift-node`} className="text-xs font-medium">Node</label>
                <select
                  id={`${formId}-drift-node`}
                  value={driftNodeId}
                  onChange={(e) => setDriftNodeId(Number(e.target.value))}
                  disabled={isBusy}
                  className="h-8 w-full rounded-md border bg-background px-2 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {allNodes.map((n) => (
                    <option key={n.nodeId} value={n.nodeId} disabled={isNodeCrashed(n)}>
                      Node {n.nodeId} {isNodeCrashed(n) ? '(down)' : ''}
                    </option>
                  ))}
                </select>
              </div>

              <div className="space-y-1">
                <label htmlFor={`${formId}-drift-offset`} className="text-xs font-medium">Offset (ms)</label>
                <input
                  id={`${formId}-drift-offset`}
                  type="number"
                  value={driftOffset}
                  onChange={(e) => setDriftOffset(e.target.value)}
                  disabled={isBusy}
                  aria-invalid={Boolean(fieldErrors.initialOffsetMillis || fieldErrors.offsetMillis || fieldErrors.offset)}
                  aria-describedby={
                    (fieldErrors.initialOffsetMillis || fieldErrors.offsetMillis || fieldErrors.offset)
                      ? `${formId}-drift-offset-error`
                      : undefined
                  }
                  className="h-8 w-full rounded-md border bg-background px-3 text-xs tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                {(fieldErrors.initialOffsetMillis || fieldErrors.offsetMillis || fieldErrors.offset) && (
                  <p id={`${formId}-drift-offset-error`} className="text-[10px] text-destructive">
                    {fieldErrors.initialOffsetMillis || fieldErrors.offsetMillis || fieldErrors.offset}
                  </p>
                )}
              </div>

              <div className="space-y-1">
                <label htmlFor={`${formId}-drift-rate`} className="text-xs font-medium">Drift rate (ms/s)</label>
                <input
                  id={`${formId}-drift-rate`}
                  type="number"
                  step="0.1"
                  value={driftRate}
                  onChange={(e) => setDriftRate(e.target.value)}
                  disabled={isBusy}
                  aria-invalid={Boolean(fieldErrors.driftRateMsPerSec || fieldErrors.driftRate)}
                  aria-describedby={
                    (fieldErrors.driftRateMsPerSec || fieldErrors.driftRate)
                      ? `${formId}-drift-rate-error`
                      : undefined
                  }
                  className="h-8 w-full rounded-md border bg-background px-3 text-xs tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                {(fieldErrors.driftRateMsPerSec || fieldErrors.driftRate) && (
                  <p id={`${formId}-drift-rate-error`} className="text-[10px] text-destructive">
                    {fieldErrors.driftRateMsPerSec || fieldErrors.driftRate}
                  </p>
                )}
              </div>
            </div>
            <Button type="submit" size="sm" disabled={isBusy || pendingAction !== null} className="text-xs">
              {pendingAction === 'drift' ? 'Updating…' : 'Update drift parameters'}
            </Button>
          </form>
        </div>
      )}

      {/* Result Panel (receives keyboard focus after action) */}
      {lastResult && (
        <div
          ref={resultRef}
          tabIndex={-1}
          data-testid="action-result"
          className="rounded-lg border border-primary/30 bg-primary/10 p-4 text-xs text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <div className="font-semibold text-primary">Action completed</div>
          {lastResult.kind === 'local' && (
            <p className="mt-1">
              Local event recorded on Node {lastResult.data.nodeId}: Lamport clock advanced to{' '}
              <strong className="font-mono">{lastResult.data.lamportTime}</strong> ({lastResult.data.description}).
            </p>
          )}
          {lastResult.kind === 'message' && (
            <div className="mt-1 space-y-1">
              <p>
                Sent message from Node {lastResult.data.from} to Node {lastResult.data.to} (Lamport time {lastResult.data.lamportTime}).
                Status: <strong className="font-semibold">{deliveryStatusText(lastResult.data.deliveryStatus)}</strong>.
              </p>
              {lastResult.data.deliveryNote && (
                <p className="text-muted-foreground">{lastResult.data.deliveryNote}</p>
              )}
            </div>
          )}
          {lastResult.kind === 'traffic' && (
            <p className="mt-1">
              Session started ({lastResult.data.seconds} s, {lastResult.data.messagesPerSecond} msg/s).
              Status: {lastResult.data.status}.
            </p>
          )}
          {lastResult.kind === 'berkeley' && (
            <p className="mt-1">
              Berkeley round {lastResult.data.roundId} accepted, coordinated by daemon Node {lastResult.data.daemonNodeId}.
            </p>
          )}
          {lastResult.kind === 'drift' && (
            <p className="mt-1">
              Node {lastResult.data.nodeId} drift set to {formatOffset(lastResult.data.offsetMillis)} ({formatDriftRate(lastResult.data.driftRateMsPerSec)}).
            </p>
          )}
        </div>
      )}

      {/* Action Error Alert (receives keyboard focus on error) */}
      {actionError && (
        <div
          ref={errorRef}
          tabIndex={-1}
          role="alert"
          data-testid="action-error"
          className="rounded-lg border border-destructive/40 bg-destructive/10 p-4 text-xs text-destructive focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <div className="font-semibold">Error: {actionError.title || 'Request failed'}</div>
          <p className="mt-1 text-foreground/90">{actionError.detail || actionError.message}</p>
          {actionError.nodeId && (
            <p className="mt-0.5 text-muted-foreground">Associated node: Node {actionError.nodeId}</p>
          )}
        </div>
      )}
    </div>
  )
}
