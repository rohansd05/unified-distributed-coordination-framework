import { useId } from 'react'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { failureKindLabel, methodLabel, nodeStateLabel } from './labels'
import { MAP_SIZE, callSentence, mapModel } from './rmiModel'

const NODE_RADIUS = 26

const STATUS_TEXT = { up: 'up', down: 'down', 'not-started': 'not started' }
const STATUS_STYLE = {
  up: { fill: 'hsl(var(--success) / 0.15)', stroke: 'hsl(var(--success))' },
  down: { fill: 'hsl(var(--destructive) / 0.15)', stroke: 'hsl(var(--destructive))' },
  'not-started': { fill: 'hsl(var(--muted) / 0.4)', stroke: 'hsl(var(--muted-foreground))' },
}

/** The second signal beside colour: a tick for up, a cross for down, a dash for not started. */
function Glyph({ status, x, y }) {
  const stroke = STATUS_STYLE[status].stroke
  if (status === 'up') {
    return <path d={`M${x - 5} ${y} l3.5 3.5 l6.5 -7`} fill="none" stroke={stroke} strokeWidth="2" />
  }
  if (status === 'down') {
    return <path d={`M${x - 4.5} ${y - 4.5} l9 9 M${x + 4.5} ${y - 4.5} l-9 9`} fill="none" stroke={stroke} strokeWidth="2" />
  }
  return <path d={`M${x - 5} ${y} h10`} fill="none" stroke={stroke} strokeWidth="2" />
}

/** A straight arrow between two node rims, with its midpoint for the failure label. */
function segment(from, to) {
  const dx = to.x - from.x
  const dy = to.y - from.y
  const length = Math.hypot(dx, dy) || 1
  const ux = dx / length
  const uy = dy / length
  const start = { x: from.x + ux * (NODE_RADIUS + 2), y: from.y + uy * (NODE_RADIUS + 2) }
  const end = { x: to.x - ux * (NODE_RADIUS + 6), y: to.y - uy * (NODE_RADIUS + 6) }
  return { start, end, mid: { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 } }
}

/**
 * Experiment 1's bold element: every node on a circle with its state (colour plus a glyph plus
 * a word), and the last call as an arrow from caller to target: solid for a success, dashed and
 * labelled with the failure kind for a remote failure, a loop for a node calling itself. Hand
 * drawn SVG, no chart library. The co-hosting fact carries the page's only Simulated badge.
 *
 * @param {object} props
 * @param {object|null} props.overview the RmiOverviewDto
 * @param {object|null} props.call the call to draw (see latestCall)
 */
export function NodeMap({ overview, call }) {
  const markerId = `rmi-arrow-${useId().replace(/[^a-zA-Z0-9-]/g, '')}`
  const { nodes, arrow } = mapModel(overview, call)
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const sentence = callSentence(call)
  const coHosting = overview?.coHosting

  const color = arrow?.ok ? 'hsl(var(--primary))' : 'hsl(var(--destructive))'
  let arrowShape = null
  if (arrow) {
    const dash = arrow.ok ? undefined : '6 4'
    const from = byId.get(arrow.from)
    let label = null
    let path
    if (arrow.self) {
      const top = from.y - NODE_RADIUS
      path = `M${from.x - 10} ${top} C${from.x - 34} ${top - 46}, ${from.x + 34} ${top - 46}, ${from.x + 10} ${top - 4}`
      label = { x: from.x, y: top - 40 }
    } else {
      const { start, end, mid } = segment(from, byId.get(arrow.to))
      path = `M${start.x} ${start.y} L${end.x} ${end.y}`
      label = mid
    }
    arrowShape = (
      <g data-testid="call-arrow" data-outcome={arrow.ok ? 'success' : 'failure'}>
        <path d={path} fill="none" stroke={color} strokeWidth="2.5" strokeDasharray={dash} markerEnd={`url(#${markerId})`} />
        {!arrow.ok && (
          <text x={label.x} y={label.y - 6} textAnchor="middle" className="fill-destructive text-[11px] font-medium"
            data-testid="arrow-failure-kind">
            {failureKindLabel(arrow.kind)}
          </text>
        )}
      </g>
    )
  }

  return (
    <div data-testid="node-map" className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold">Nodes and the last call</h3>
        {coHosting?.simulated && <SimulatedBadge reason={coHosting.reason} />}
      </div>
      {nodes.length === 0 ? (
        <p className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">The nodes appear here once the module has loaded.</p>
      ) : (
        <div className="rounded-lg border bg-card p-2">
          <svg viewBox={`0 0 ${MAP_SIZE.width} ${MAP_SIZE.height}`} role="img" aria-label={sentence}
            className="mx-auto h-auto w-full max-w-md">
            <defs>
              <marker id={markerId} viewBox="0 0 10 10" refX="6" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M0 0 L10 5 L0 10 z" fill={color} />
              </marker>
            </defs>
            {arrowShape}
            {nodes.map((node) => (
              <g key={node.id} data-testid={`map-node-${node.id}`} data-status={node.status}>
                <circle cx={node.x} cy={node.y} r={NODE_RADIUS} fill={STATUS_STYLE[node.status].fill}
                  stroke={STATUS_STYLE[node.status].stroke} strokeWidth="2" />
                <text x={node.x} y={node.y - 6} textAnchor="middle" className="fill-foreground text-[12px] font-semibold">
                  Node {node.id}
                </text>
                <Glyph status={node.status} x={node.x} y={node.y + 9} />
                <text x={node.x} y={node.y + NODE_RADIUS + 13} textAnchor="middle" className="fill-muted-foreground text-[11px]">
                  {STATUS_TEXT[node.status]}
                </text>
              </g>
            ))}
          </svg>
        </div>
      )}
      <p data-testid="map-sentence" className="text-sm">{sentence}</p>
      {nodes.length > 0 && (
        <ul className="sr-only">
          {nodes.map((node) => (
            <li key={node.id}>Node {node.id}: {STATUS_TEXT[node.status]}, endpoint {nodeStateLabel(node.state).toLowerCase()}.</li>
          ))}
        </ul>
      )}
      {arrow && !arrow.ok && (
        <p className="text-xs text-muted-foreground">
          The dashed arrow is a {methodLabel(arrow.method)} that did not come back.
        </p>
      )}
    </div>
  )
}
