import { useId } from 'react'
import { cn } from '@/lib/utils'
import { messageLabel } from './labels'
import { ringLayout } from './electionModel'

const CENTER = 180
const RADIUS = 130
const NODE_RADIUS = 26

/** A curved arrow from one node to another, bent towards the ring's centre; its label sits on the curve. */
function arc(from, to) {
  const cx = (from.x + to.x) / 2 + (CENTER - (from.x + to.x) / 2) * 0.45
  const cy = (from.y + to.y) / 2 + (CENTER - (from.y + to.y) / 2) * 0.45
  const shorten = (px, py, qx, qy) => {
    const length = Math.hypot(qx - px, qy - py) || 1
    return [px + ((qx - px) / length) * NODE_RADIUS, py + ((qy - py) / length) * NODE_RADIUS]
  }
  const [sx, sy] = shorten(from.x, from.y, cx, cy)
  const [ex, ey] = shorten(to.x, to.y, cx, cy)
  return {
    d: `M ${sx.toFixed(1)} ${sy.toFixed(1)} Q ${cx.toFixed(1)} ${cy.toFixed(1)} ${ex.toFixed(1)} ${ey.toFixed(1)}`,
    labelX: 0.25 * sx + 0.5 * cx + 0.25 * ex,
    labelY: 0.25 * sy + 0.5 * cy + 0.25 * ey,
  }
}

/** One sentence per node for the text list that mirrors the drawing. */
function nodeSentence(node, leaderId, suspectedBy) {
  const parts = [`Node ${node.nodeId}: ${node.status === 'UP' ? 'up' : 'crashed'}${node.nodeId === leaderId ? ', the leader' : ''}`]
  if (!node.serviceRunning) {
    parts.push(node.status === 'UP' ? 'election service not started' : 'not taking part')
  } else {
    parts.push(node.coordinatorId != null ? `follows node ${node.coordinatorId}` : 'knows no leader')
    if (node.suspectedPeers?.length > 0) {
      parts.push(`suspects node ${node.suspectedPeers.join(' and node ')}`)
    }
  }
  if (suspectedBy > 0) {
    parts.push(`suspected by ${suspectedBy} ${suspectedBy === 1 ? 'node' : 'nodes'}`)
  }
  return `${parts.join('; ')}.`
}

/**
 * The page's bold element (R17): the nodes on a ring, the leader marked with the word
 * "Leader" (taken from cluster roles, never from page state), each node's status and the
 * leader it follows in words, nodes that others suspect, and the newest election messages of
 * the current or last round as arrows labelled with their type and real Lamport value. Arrows
 * fade in only when they arrive, and not at all under prefers-reduced-motion. A text list says
 * the same for screen readers and narrow screens. Colour is never the only signal.
 *
 * @param {object} props
 * @param {Array<object>|null} props.nodes the overview's nodes
 * @param {number|null} props.leaderId from cluster roles
 * @param {Array<object>} props.messages see roundMessages
 */
export function ElectionRing({ nodes, leaderId, messages = [] }) {
  const titleId = useId()
  const markerId = useId()

  if (!Array.isArray(nodes) || nodes.length === 0) {
    return (
      <div data-testid="election-ring" className="rounded-lg border border-dashed bg-card/40 p-6 text-sm text-muted-foreground">
        The ring of nodes appears as soon as the election module answers.
      </div>
    )
  }

  const placed = ringLayout(nodes, { center: CENTER, radius: RADIUS })
  const byId = new Map(placed.map((node) => [node.nodeId, node]))
  const suspicionCount = new Map()
  for (const node of nodes) {
    for (const peer of node.suspectedPeers ?? []) {
      suspicionCount.set(peer, (suspicionCount.get(peer) ?? 0) + 1)
    }
  }
  const arrows = messages.filter((message) => byId.has(message.from) && byId.has(message.to) && message.from !== message.to)
  const summary = leaderId != null
    ? `Ring of ${nodes.length} nodes; node ${leaderId} is the leader`
    : `Ring of ${nodes.length} nodes; no leader`

  return (
    <figure data-testid="election-ring" className="relative space-y-3">
      <svg viewBox="0 0 360 360" role="img" aria-labelledby={titleId} className="mx-auto block h-auto w-full max-w-sm">
        <title id={titleId}>{summary}</title>
        <defs>
          <marker id={markerId} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" className="fill-primary" />
          </marker>
        </defs>
        <circle cx={CENTER} cy={CENTER} r={RADIUS} className="fill-none stroke-border" strokeWidth="1.5" strokeDasharray="3 7" />

        {arrows.map((message) => {
          const path = arc(byId.get(message.from), byId.get(message.to))
          return (
            <g key={message.sequence} data-testid="message-arrow"
              className="motion-safe:animate-in motion-safe:fade-in motion-safe:duration-500">
              <path d={path.d} className="fill-none stroke-primary" strokeWidth="1.5" markerEnd={`url(#${markerId})`} />
              <text x={path.labelX} y={path.labelY} textAnchor="middle" className="fill-foreground text-[9px]">
                {messageLabel(message.type)}
                <tspan className="fill-muted-foreground font-mono"> L:{message.lamportTime}</tspan>
              </text>
            </g>
          )
        })}

        {placed.map((node) => {
          const up = node.status === 'UP'
          const leader = node.nodeId === leaderId
          const suspected = (suspicionCount.get(node.nodeId) ?? 0) > 0
          return (
            <g key={node.nodeId} data-testid={`ring-node-${node.nodeId}`}>
              <circle cx={node.x} cy={node.y} r={NODE_RADIUS}
                className={cn('fill-card', leader ? 'stroke-success' : up ? 'stroke-primary' : 'stroke-destructive')}
                strokeWidth={leader ? 3 : 1.5} strokeDasharray={up ? undefined : '4 4'} />
              <text x={node.x} y={node.y + 6} textAnchor="middle" className="fill-foreground text-[18px] font-semibold">
                {node.nodeId}
              </text>
              {leader && (
                <g>
                  <rect x={node.x - 23} y={node.y - NODE_RADIUS - 18} width="46" height="14" rx="3" className="fill-success" />
                  <text x={node.x} y={node.y - NODE_RADIUS - 8} textAnchor="middle" className="fill-background text-[9px] font-semibold">
                    Leader
                  </text>
                </g>
              )}
              <text x={node.x} y={node.y + NODE_RADIUS + 12} textAnchor="middle"
                className={cn('text-[9px]', up ? 'fill-muted-foreground' : 'fill-destructive')}>
                {up ? (node.serviceRunning && node.coordinatorId != null ? `Follows ${node.coordinatorId}` : 'Up') : 'Crashed'}
              </text>
              {suspected && (
                <text x={node.x} y={node.y + NODE_RADIUS + 23} textAnchor="middle" className="fill-warning text-[9px]">
                  Suspected
                </text>
              )}
            </g>
          )
        })}
      </svg>
      <figcaption>
        <ul className="space-y-1 text-xs text-muted-foreground" data-testid="ring-text">
          {placed.map((node) => (
            <li key={node.nodeId}>{nodeSentence(node, leaderId, suspicionCount.get(node.nodeId) ?? 0)}</li>
          ))}
        </ul>
      </figcaption>
    </figure>
  )
}
