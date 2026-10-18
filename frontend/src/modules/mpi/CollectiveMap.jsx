import { useId } from 'react'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { failureKindLabel, operationLabel, outcomeLabel, transferStatusLabel } from './labels'
import { MAP_SIZE, fanModel } from './mpiModel'

const NODE_RADIUS = 24

const STATE_TEXT = { up: 'up', down: 'down', 'not-started': 'not started' }
const STATE_STYLE = {
  up: { fill: 'hsl(var(--success) / 0.15)', stroke: 'hsl(var(--success))' },
  down: { fill: 'hsl(var(--destructive) / 0.15)', stroke: 'hsl(var(--destructive))' },
  'not-started': { fill: 'hsl(var(--muted) / 0.4)', stroke: 'hsl(var(--muted-foreground))' },
}
const ARROW_STYLE = {
  DELIVERED: { stroke: 'hsl(var(--primary))', dash: undefined },
  FAILED: { stroke: 'hsl(var(--destructive))', dash: '6 4' },
  NOT_ATTEMPTED: { stroke: 'hsl(var(--muted-foreground))', dash: '2 4' },
}

/** The second signal beside colour: a tick for up, a cross for down, a dash for not started. */
function Glyph({ state, x, y }) {
  const stroke = STATE_STYLE[state].stroke
  if (state === 'up') return <path d={`M${x - 5} ${y} l3.5 3.5 l6.5 -7`} fill="none" stroke={stroke} strokeWidth="2" />
  if (state === 'down') {
    return <path d={`M${x - 4.5} ${y - 4.5} l9 9 M${x + 4.5} ${y - 4.5} l-9 9`} fill="none" stroke={stroke} strokeWidth="2" />
  }
  return <path d={`M${x - 5} ${y} h10`} fill="none" stroke={stroke} strokeWidth="2" />
}

/** A straight segment between two node rims, with its midpoint for a label. */
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

function sentence(collective, rootId, nodes) {
  if (!collective && rootId === null) {
    return nodes.some((node) => node.state !== 'down')
      ? 'No collective yet, and no leader yet: the first collective asks Experiment 4 to elect one, and it becomes the root.'
      : 'No collective yet, and no live node to be the root.'
  }
  if (!collective) return `No collective yet. The default root is node ${rootId}, the leader elected by Experiment 4.`
  return `${operationLabel(collective.operation)} ${collective.id} from root node ${collective.rootNodeId}: ${outcomeLabel(collective.outcome).toLowerCase()}.`
}

/**
 * Experiment 9's bold element: the root in the centre and every other node around it, each with
 * its state (colour plus a glyph plus a word), and one arrow per remote transfer of the shown
 * collective: outward for broadcast and scatter, inward for gather. Delivered is solid, failed is
 * dashed and labelled with its kind, not attempted is dotted and labelled. A skipped node is
 * marked and has no arrow. The arrows fade in, in rank order, when a new collective is shown
 * (not under reduced motion). Hand drawn SVG. The co-hosting fact carries the page's only
 * Simulated badge.
 *
 * @param {object} props
 * @param {object|null} props.overview the MpiOverviewDto
 * @param {object|null} props.collective the CollectiveDto to draw
 */
export function CollectiveMap({ overview, collective }) {
  const markerBase = `mpi-arrow-${useId().replace(/[^a-zA-Z0-9-]/g, '')}`
  const { nodes, arrows, rootId } = fanModel(overview, collective)
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const root = byId.get(rootId)
  const coHosting = overview?.coHosting
  const text = sentence(collective, rootId, nodes)
  const animationKey = collective ? `${collective.id}-${collective.startedAt}` : 'none'

  return (
    <div data-testid="collective-map" className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold">The root and its transfers</h3>
        {coHosting && <SimulatedBadge reason={coHosting.reason} />}
      </div>
      {nodes.length === 0 ? (
        <p className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">The nodes appear here once the module has loaded.</p>
      ) : (
        <div className="rounded-lg border bg-card p-2">
          <svg viewBox={`0 0 ${MAP_SIZE.width} ${MAP_SIZE.height}`} role="img" aria-label={text} className="mx-auto h-auto w-full max-w-md">
            <defs>
              {Object.entries(ARROW_STYLE).map(([status, style]) => (
                <marker key={status} id={`${markerBase}-${status}`} viewBox="0 0 10 10" refX="6" refY="5" markerWidth="7"
                  markerHeight="7" orient="auto-start-reverse">
                  <path d="M0 0 L10 5 L0 10 z" fill={style.stroke} />
                </marker>
              ))}
            </defs>
            <g key={animationKey}>
              {root && arrows.map((arrow) => {
                const peer = byId.get(arrow.peer)
                const { start, end, mid } = arrow.inward ? segment(peer, root) : segment(root, peer)
                const style = ARROW_STYLE[arrow.status] ?? ARROW_STYLE.NOT_ATTEMPTED
                return (
                  <g key={arrow.key} data-testid="fan-arrow" data-status={arrow.status} data-inward={arrow.inward ? 'true' : 'false'}
                    className="motion-safe:animate-in motion-safe:fade-in motion-safe:fill-mode-both motion-safe:duration-300"
                    style={{ animationDelay: `${arrow.order * 120}ms` }}>
                    <path d={`M${start.x} ${start.y} L${end.x} ${end.y}`} fill="none" stroke={style.stroke} strokeWidth="2.5"
                      strokeDasharray={style.dash} markerEnd={`url(#${markerBase}-${arrow.status in ARROW_STYLE ? arrow.status : 'NOT_ATTEMPTED'})`} />
                    {arrow.status !== 'DELIVERED' && (
                      <text x={mid.x} y={mid.y - 6} textAnchor="middle" data-testid="arrow-label"
                        className={`text-[10px] font-medium ${arrow.status === 'FAILED' ? 'fill-destructive' : 'fill-muted-foreground'}`}>
                        {arrow.status === 'FAILED' ? failureKindLabel(arrow.failureKind) : transferStatusLabel(arrow.status).toLowerCase()}
                      </text>
                    )}
                  </g>
                )
              })}
            </g>
            {nodes.map((node) => (
              <g key={node.id} data-testid={`map-node-${node.id}`} data-state={node.state} data-root={node.root ? 'true' : 'false'}>
                <circle cx={node.x} cy={node.y} r={NODE_RADIUS} fill={STATE_STYLE[node.state].fill}
                  stroke={STATE_STYLE[node.state].stroke} strokeWidth={node.root ? 3.5 : 2} />
                <text x={node.x} y={node.y - 5} textAnchor="middle" className="fill-foreground text-[12px] font-semibold">
                  Node {node.id}
                </text>
                <Glyph state={node.state} x={node.x} y={node.y + 9} />
                <text x={node.x} y={node.y + NODE_RADIUS + 12} textAnchor="middle" className="fill-muted-foreground text-[10px]">
                  {node.root ? (collective ? 'root' : 'default root') : node.skipped ? 'skipped' : STATE_TEXT[node.state]}
                  {node.leader ? ', leader' : ''}
                </text>
              </g>
            ))}
          </svg>
        </div>
      )}
      <p data-testid="map-sentence" className="text-sm">{text}</p>
      {nodes.length > 0 && (
        <ul className="sr-only">
          {nodes.map((node) => (
            <li key={node.id}>
              Node {node.id}: {STATE_TEXT[node.state]}{node.root ? ', root' : ''}{node.skipped ? ', skipped' : ''}
              {node.leader ? ', leader' : ''}.
            </li>
          ))}
          {arrows.map((arrow) => (
            <li key={`arrow-${arrow.key}`}>
              Rank {arrow.rank}, node {arrow.peer}: {transferStatusLabel(arrow.status).toLowerCase()}
              {arrow.status === 'FAILED' ? `, ${failureKindLabel(arrow.failureKind).toLowerCase()}` : ''}.
            </li>
          ))}
        </ul>
      )}
      {collective && arrows.length > 0 && (
        <p className="text-xs text-muted-foreground">
          {arrows[0].inward ? 'Arrows point to the root: each rank sends its block back.' : 'Arrows point away from the root: it sends to each rank.'}
          {' '}Solid is delivered, dashed failed, dotted not attempted.
        </p>
      )}
    </div>
  )
}
