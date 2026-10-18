import { cn } from '@/lib/utils'

const R = 15
const GAP = 58
const PAD = 26

function roleWords(node, endState) {
  const words = []
  if (endState.leaderId === node.nodeId) words.push('leader')
  if (endState.primaryId === node.nodeId) words.push('primary')
  return words
}

/**
 * The cluster a run leaves behind, as the engine read it when the run ended: a row of nodes (up
 * solid, down dashed with a cross), the leader ringed, and the engine's own description beneath.
 *
 * @param {object} props
 * @param {{description: string, leaderId: number|null, primaryId: number|null,
 *   nodes: Array<{nodeId: number, status: string, roles: string[]}>}} props.endState
 */
export function EndStateView({ endState }) {
  const nodes = endState.nodes ?? []
  const width = PAD * 2 + Math.max(0, nodes.length - 1) * GAP
  const summary = nodes.map((n) => `node ${n.nodeId} ${n.status === 'UP' ? 'up' : 'down'}${roleWords(n, endState).map((w) => `, ${w}`).join('')}`).join('; ')
  return (
    <section aria-labelledby="end-state-heading" data-testid="end-state" className="space-y-3 rounded-lg border bg-card p-4">
      <h3 id="end-state-heading" className="text-sm font-medium">The cluster it leaves behind</h3>
      {nodes.length > 0 && (
        <svg viewBox={`0 0 ${width} 76`} role="img" aria-label={summary} className="h-20 w-full max-w-md">
          <line x1={PAD} y1={28} x2={width - PAD} y2={28} className="stroke-border" strokeWidth="1.5" />
          {nodes.map((node, i) => {
            const x = PAD + i * GAP
            const up = node.status === 'UP'
            const leader = endState.leaderId === node.nodeId
            const words = roleWords(node, endState)
            return (
              <g key={node.nodeId}>
                {leader && <circle cx={x} cy={28} r={R + 5} className="fill-none stroke-warning" strokeWidth="2" />}
                <circle cx={x} cy={28} r={R}
                  className={cn(up ? 'fill-secondary stroke-primary' : 'fill-background stroke-muted-foreground')}
                  strokeWidth="2" strokeDasharray={up ? undefined : '4 3'} />
                {up ? (
                  <text x={x} y={32} textAnchor="middle" className="fill-foreground font-mono text-[11px]">{node.nodeId}</text>
                ) : (
                  <g className="stroke-destructive" strokeWidth="2" strokeLinecap="round">
                    <line x1={x - 5} y1={23} x2={x + 5} y2={33} />
                    <line x1={x - 5} y1={33} x2={x + 5} y2={23} />
                  </g>
                )}
                <text x={x} y={62} textAnchor="middle" className="fill-muted-foreground text-[10px]">
                  {up ? words.join(', ') || 'up' : `node ${node.nodeId} down`}
                </text>
              </g>
            )
          })}
        </svg>
      )}
      <p className="text-sm text-muted-foreground">{endState.description}</p>
    </section>
  )
}
