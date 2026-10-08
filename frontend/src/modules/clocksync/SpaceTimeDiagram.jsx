import { useId, useMemo, useState } from 'react'
import { isNodeCrashed } from '@/lib/clusterStatus'
import { cn } from '@/lib/utils'
import {
  MAX_DISPLAYED_EVENTS,
  buildDiagramModel,
  sortEventsTotalOrder,
} from './diagramModel'
import { eventTypeLabel } from './labels'

/**
 * Hand-built SVG space-time diagram for Lamport logical clocks (Experiment 3).
 *
 * Visualizes causal order across distributed nodes:
 * - One horizontal lane per cluster node.
 * - Local, send, and receive events as discrete dots labelled with Lamport values.
 * - Directed arrows connecting message transmission and reception over UDP.
 * - Violations marked by both non-circle shape (triangle) and explicit text, not colour alone (R17).
 * - Bounded rendering (latest 40 events) with transparent notes on retained and dropped counts.
 * - Self-contained horizontal scroll container (overflow-x-auto) to protect page responsiveness down to 375 px.
 * - Keyboard-accessible table equivalent sorted in (lamportTime, nodeId) total order.
 */
export function SpaceTimeDiagram({
  nodes = [],
  events = [],
  violations = [],
  retainedEventsCount,
  droppedEventsCount = 0,
}) {
  const [showTable, setShowTable] = useState(false)
  const [selectedEventId, setSelectedEventId] = useState(null)
  const tableId = useId()

  const model = useMemo(() => {
    return buildDiagramModel({ nodes, events, violations })
  }, [nodes, events, violations])

  const orderedTableEvents = useMemo(() => {
    const rawDisplayed = (events || []).slice(-MAX_DISPLAYED_EVENTS)
    return sortEventsTotalOrder(rawDisplayed)
  }, [events])

  const retainedTotal = retainedEventsCount != null ? retainedEventsCount : (events || []).length
  const displayedCount = model.events.length

  return (
    <div data-testid="space-time-diagram" className="relative space-y-4 rounded-xl border bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
        <div>
          <h3 className="font-semibold text-foreground">Space-time diagram</h3>
          <p className="text-xs text-muted-foreground" data-testid="retention-note">
            Showing the latest {displayedCount} of {retainedTotal} retained events.
            {droppedEventsCount > 0 && (
              <span className="ml-1 text-warning">
                ({droppedEventsCount} earlier events were dropped from log capacity).
              </span>
            )}
          </p>
        </div>
        <button
          type="button"
          aria-expanded={showTable}
          aria-controls={tableId}
          onClick={() => setShowTable((prev) => !prev)}
          className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {showTable ? 'Hide text equivalent' : 'Show text equivalent (table)'}
        </button>
      </div>

      {/* SVG Canvas inside self-contained scrollable container */}
      <div
        className="relative max-w-full overflow-x-auto rounded-lg border border-border/60 bg-background/50 p-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        tabIndex={0}
        role="region"
        aria-label="Scrollable space-time diagram canvas"
      >
        {model.lanes.length === 0 ? (
          <div className="flex h-48 items-center justify-center text-sm text-muted-foreground">
            No cluster nodes available.
          </div>
        ) : model.events.length === 0 ? (
          <div className="flex h-48 flex-col items-center justify-center gap-2 text-sm text-muted-foreground">
            <p>No logical clock events recorded yet.</p>
            <p className="text-xs">
              Record a local event, send a message, or start traffic using the controls below.
            </p>
          </div>
        ) : (
          <svg
            role="img"
            aria-label="Space-time diagram showing logical clock progression and message passing across nodes"
            width={model.width}
            height={model.height}
            className="select-none"
          >
            <defs>
              {/* Normal message arrow marker */}
              <marker
                id="st-arrow"
                viewBox="0 0 10 10"
                refX="7"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 1 L 9 5 L 0 9 z" fill="#02C39A" />
              </marker>

              {/* Violation message arrow marker */}
              <marker
                id="st-arrow-violation"
                viewBox="0 0 10 10"
                refX="7"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 1 L 9 5 L 0 9 z" fill="#B9463A" />
              </marker>
            </defs>

            {/* Node Lanes */}
            {model.lanes.map((lane) => {
              const crashed = isNodeCrashed(lane)
              return (
                <g key={lane.nodeId} data-testid={`lane-${lane.nodeId}`}>
                  {/* Lane Guide Line */}
                  <line
                    x1={80}
                    y1={lane.y}
                    x2={model.width - 20}
                    y2={lane.y}
                    stroke="currentColor"
                    strokeWidth="1"
                    className="text-border/80"
                    strokeDasharray={crashed ? '4 4' : undefined}
                  />

                  {/* Node Label at Left */}
                  <text
                    x={12}
                    y={lane.y + 4}
                    className="fill-foreground text-xs font-medium"
                    alignmentBaseline="middle"
                  >
                    Node {lane.nodeId}
                    {crashed && (
                      <tspan className="fill-destructive text-[10px]"> (down)</tspan>
                    )}
                  </text>
                </g>
              )
            })}

            {/* Message Arrows */}
            {model.arrows.map((arrow) => {
              const isViol = arrow.isViolation
              return (
                <g key={`arrow-${arrow.messageId}`} data-testid={`arrow-${arrow.messageId}`}>
                  <line
                    x1={arrow.x1}
                    y1={arrow.y1}
                    x2={arrow.x2}
                    y2={arrow.y2}
                    stroke={isViol ? '#B9463A' : '#02C39A'}
                    strokeWidth={isViol ? '2.5' : '1.75'}
                    strokeDasharray={isViol ? '4 3' : undefined}
                    markerEnd={isViol ? 'url(#st-arrow-violation)' : 'url(#st-arrow)'}
                    className="transition-opacity hover:opacity-100"
                    opacity={selectedEventId && selectedEventId !== arrow.messageId ? 0.35 : 0.9}
                  />
                  {/* Message Lamport transmission note */}
                  <text
                    x={(arrow.x1 + arrow.x2) / 2}
                    y={(arrow.y1 + arrow.y2) / 2 - 6}
                    textAnchor="middle"
                    className="fill-muted-foreground text-[10px] font-mono"
                  >
                    m#{arrow.messageId}
                  </text>
                </g>
              )
            })}

            {/* Event Dots */}
            {model.events.map((evt) => {
              const isSelected = selectedEventId === evt.messageId
              const isViol = evt.isViolation

              return (
                <g
                  key={`event-${evt.nodeId}-${evt.lamportTime}-${evt.index}`}
                  data-testid="event-marker"
                  tabIndex={0}
                  role="button"
                  aria-label={`${eventTypeLabel(evt.type)} on node ${evt.nodeId}, Lamport time ${evt.lamportTime}${isViol ? ', causal violation' : ''}`}
                  onClick={() => setSelectedEventId(evt.messageId ?? null)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      setSelectedEventId(evt.messageId ?? null)
                    }
                  }}
                  opacity={selectedEventId && !isSelected ? 0.4 : 1}
                  className="cursor-pointer focus:outline-none"
                >
                  {isViol ? (
                    // Causal Violation: Distinct TRIANGLE shape (not circle) and text label
                    <g>
                      <polygon
                        points={`${evt.x},${evt.y - 10} ${evt.x + 9},${evt.y + 6} ${evt.x - 9},${evt.y + 6}`}
                        fill="#B9463A"
                        stroke="#FFFFFF"
                        strokeWidth="1.5"
                      />
                      <text
                        x={evt.x}
                        y={evt.y - 14}
                        textAnchor="middle"
                        className="fill-destructive text-[10px] font-bold"
                      >
                        Violation
                      </text>
                    </g>
                  ) : evt.type === 'LOCAL' ? (
                    // Local event: Square dot
                    <rect
                      x={evt.x - 5}
                      y={evt.y - 5}
                      width={10}
                      height={10}
                      rx={2}
                      fill="#1C7293"
                      stroke="#FFFFFF"
                      strokeWidth="1"
                    />
                  ) : (
                    // Message Send or Receive: Circular dot
                    <circle
                      cx={evt.x}
                      cy={evt.y}
                      r={evt.type === 'SEND' ? 6 : 5}
                      fill={evt.type === 'SEND' ? '#02C39A' : '#38BDF8'}
                      stroke="#FFFFFF"
                      strokeWidth="1"
                    />
                  )}

                  {/* Lamport Clock Value Label */}
                  <text
                    x={evt.x}
                    y={evt.y + 16}
                    textAnchor="middle"
                    className={cn(
                      'font-mono text-[11px] font-medium tabular-nums',
                      isViol ? 'fill-destructive font-bold' : 'fill-foreground',
                    )}
                  >
                    L={evt.lamportTime}
                  </text>
                </g>
              )
            })}
          </svg>
        )}
      </div>

      {/* Legend & Guidance */}
      <div className="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <span className="size-2.5 rounded-full bg-[#02C39A]" /> Send message
        </span>
        <span className="flex items-center gap-1.5">
          <span className="size-2.5 rounded-full bg-[#38BDF8]" /> Receive message
        </span>
        <span className="flex items-center gap-1.5">
          <span className="size-2.5 rounded-[2px] bg-[#1C7293]" /> Local event
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block size-0 border-x-4 border-b-[8px] border-x-transparent border-b-[#B9463A]" />
          <span className="font-medium text-destructive">Causal violation (shape + text)</span>
        </span>
      </div>

      {/* Keyboard-Accessible Text Equivalent (Table) */}
      {showTable && (
        <div id={tableId} data-testid="diagram-table" className="space-y-2 pt-2">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Event log in total order (Lamport time, node id)
          </h4>
          <div className="max-h-72 overflow-y-auto rounded-md border bg-background/80">
            <table className="w-full text-left text-xs">
              <thead className="sticky top-0 border-b bg-muted/60 text-muted-foreground">
                <tr>
                  <th scope="col" className="px-3 py-2 font-medium">Lamport time</th>
                  <th scope="col" className="px-3 py-2 font-medium">Node</th>
                  <th scope="col" className="px-3 py-2 font-medium">Type</th>
                  <th scope="col" className="px-3 py-2 font-medium">Peer</th>
                  <th scope="col" className="px-3 py-2 font-medium">Message ID</th>
                  <th scope="col" className="px-3 py-2 font-medium">Description</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/60">
                {orderedTableEvents.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-3 py-4 text-center text-muted-foreground">
                      No events in timeline.
                    </td>
                  </tr>
                ) : (
                  orderedTableEvents.map((e, idx) => (
                    <tr
                      key={`${e.nodeId}-${e.lamportTime}-${idx}`}
                      className="transition-colors hover:bg-muted/40"
                    >
                      <td className="px-3 py-2 font-mono tabular-nums font-semibold text-foreground">
                        {e.lamportTime}
                      </td>
                      <td className="px-3 py-2">Node {e.nodeId}</td>
                      <td className="px-3 py-2">
                        <span className="inline-block rounded bg-secondary px-1.5 py-0.5 text-[10px]">
                          {eventTypeLabel(e.type)}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-muted-foreground">
                        {e.peerId ? `Node ${e.peerId}` : '—'}
                      </td>
                      <td className="px-3 py-2 font-mono text-muted-foreground">
                        {e.messageId ? `#${e.messageId}` : '—'}
                      </td>
                      <td className="px-3 py-2 text-foreground/90">
                        {e.description || '—'}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
