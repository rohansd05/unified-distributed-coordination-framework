import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useCluster } from '@/hooks/useCluster'
import { isNodeCrashed } from '@/lib/clusterStatus'

/** Sizing configuration per capacity profile (scaled up for projector legibility) */
const CAPACITY_CONFIG = {
  FAST: { radius: 44, threads: 4, pips: [[-7, -7], [7, -7], [-7, 7], [7, 7]] },
  MEDIUM: { radius: 38, threads: 2, pips: [[-8, 0], [8, 0]] },
  SLOW: { radius: 32, threads: 1, pips: [[0, 0]] },
}

/**
 * SVG Cluster Topology Graph
 *
 * Renders nodes on a ring with peer links, truthful capacity encoding (radius + pips),
 * explicit crashed status and dashed links, leader slot, keyboard accessibility with
 * explicit SVG focus rings, and single-shot transition pulses respecting prefers-reduced-motion.
 *
 * @param {object} props
 * @param {Array} [props.nodes] Cluster nodes (falls back to useCluster().cluster.nodes)
 * @param {number|null} [props.selectedNodeId] ID of the currently selected node
 * @param {Function} [props.onSelectNode] Callback when a node is clicked or activated
 * @param {string} [props.className] Additional CSS class names
 */
export function ClusterGraph({
  nodes: propNodes,
  selectedNodeId = null,
  onSelectNode = () => {},
  className = '',
}) {
  const { cluster } = useCluster()
  const nodes = useMemo(() => propNodes || cluster?.nodes || [], [propNodes, cluster?.nodes])
  const graphId = useId()

  const [pulsingNodes, setPulsingNodes] = useState({})
  const [focusedNodeId, setFocusedNodeId] = useState(null)
  const prevStatusRef = useRef({})

  // Detect state transitions to trigger one-shot pulse
  useEffect(() => {
    const changes = {}
    nodes.forEach((node) => {
      const prev = prevStatusRef.current[node.id]
      if (prev && prev !== node.status) {
        changes[node.id] = isNodeCrashed(node) ? 'crashed' : 'recovered'
      }
      prevStatusRef.current[node.id] = node.status
    })

    if (Object.keys(changes).length > 0) {
      setPulsingNodes((curr) => ({ ...curr, ...changes }))
      const timer = setTimeout(() => {
        setPulsingNodes((curr) => {
          const next = { ...curr }
          Object.keys(changes).forEach((id) => delete next[id])
          return next
        })
      }, 700)
      return () => clearTimeout(timer)
    }
  }, [nodes])

  if (!nodes || nodes.length === 0) {
    return (
      <div className={`flex aspect-square items-center justify-center rounded-lg border border-border bg-card p-8 text-sm text-muted-foreground ${className}`}>
        No cluster nodes available
      </div>
    )
  }

  const cx = 250
  const cy = 250
  const ringRadius = 165
  const count = nodes.length

  // Calculate coordinates for each node on the ring (top-center start)
  const nodePositions = nodes.map((node, index) => {
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / count
    const x = cx + ringRadius * Math.cos(angle)
    const y = cy + ringRadius * Math.sin(angle)
    const cap = CAPACITY_CONFIG[node.capacity?.name] || CAPACITY_CONFIG.MEDIUM
    const crashed = isNodeCrashed(node)
    const isLeader = Array.isArray(node.roles) && node.roles.includes('leader')
    const isSelected = selectedNodeId === node.id

    return {
      node,
      x,
      y,
      radius: cap.radius,
      pips: cap.pips,
      threads: node.capacity?.threads ?? cap.threads,
      capacityName: node.capacity?.name ?? 'MEDIUM',
      isCrashed: crashed,
      isLeader,
      isSelected,
    }
  })

  // Build peer ring links between adjacent nodes
  const links = nodePositions.map((curr, idx) => {
    const next = nodePositions[(idx + 1) % count]
    const eitherCrashed = curr.isCrashed || next.isCrashed
    return {
      key: `link-${curr.node.id}-${next.node.id}`,
      x1: curr.x,
      y1: curr.y,
      x2: next.x,
      y2: next.y,
      isBroken: eitherCrashed,
    }
  })

  return (
    <div className={`relative flex items-center justify-center ${className}`}>
      <svg
        viewBox="0 0 500 500"
        className="h-auto w-full max-w-[500px] select-none overflow-visible"
        role="region"
        aria-label="Cluster topology graph"
      >
        <title id={`${graphId}-title`}>Cluster Topology Graph</title>
        <desc id={`${graphId}-desc`}>
          A ring of {count} nodes showing current health, peer communication links, and worker capacities.
        </desc>

        {/* Peer links */}
        <g aria-hidden="true">
          {links.map((link) => (
            <line
              key={link.key}
              x1={link.x1}
              y1={link.y1}
              x2={link.x2}
              y2={link.y2}
              stroke={link.isBroken ? 'hsl(var(--destructive) / 0.35)' : 'hsl(var(--border))'}
              strokeWidth={link.isBroken ? '1.5' : '2'}
              strokeDasharray={link.isBroken ? '4 4' : undefined}
            />
          ))}
        </g>

        {/* Nodes */}
        {nodePositions.map((pos) => {
          const pulse = pulsingNodes[pos.node.id]
          const accessibleName = `Node ${pos.node.id}, ${pos.isCrashed ? 'crashed' : 'up'}, ${pos.capacityName.toLowerCase()} capacity, ${pos.threads} ${pos.threads === 1 ? 'thread' : 'threads'}`
          const isFocused = focusedNodeId === pos.node.id

          return (
            <g
              key={`node-${pos.node.id}`}
              tabIndex={0}
              role="button"
              aria-label={accessibleName}
              aria-pressed={pos.isSelected}
              data-node-id={pos.node.id}
              onClick={() => onSelectNode(pos.node.id)}
              onFocus={() => setFocusedNodeId(pos.node.id)}
              onBlur={() => setFocusedNodeId(null)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  onSelectNode(pos.node.id)
                }
              }}
              className="cursor-pointer transition-transform duration-100 hover:scale-[1.03] focus:outline-none"
            >
              {/* State transition ripple (prefers-reduced-motion: hidden) */}
              {pulse && (
                <circle
                  cx={pos.x}
                  cy={pos.y}
                  r={pos.radius + 12}
                  fill="none"
                  stroke={pulse === 'crashed' ? 'hsl(var(--destructive))' : 'hsl(var(--success))'}
                  strokeWidth="2.5"
                  className="animate-ping motion-reduce:hidden"
                  opacity="0.8"
                />
              )}

              {/* Keyboard focus ring (D2: explicit SVG circle, clearly distinct from solid selection halo) */}
              {isFocused && (
                <circle
                  data-testid="focus-ring"
                  cx={pos.x}
                  cy={pos.y}
                  r={pos.radius + 6}
                  fill="none"
                  stroke="hsl(var(--ring))"
                  strokeWidth="2.5"
                  strokeDasharray="4 3"
                  pointerEvents="none"
                />
              )}

              {/* Selection halo */}
              {pos.isSelected && (
                <circle
                  cx={pos.x}
                  cy={pos.y}
                  r={pos.radius + 6}
                  fill="none"
                  stroke="hsl(var(--primary))"
                  strokeWidth="2"
                  opacity="0.9"
                />
              )}

              {/* Base node circle */}
              <circle
                className="node-base"
                cx={pos.x}
                cy={pos.y}
                r={pos.radius}
                fill={pos.isCrashed ? 'hsl(var(--destructive) / 0.12)' : 'hsl(var(--card))'}
                stroke={
                  pos.isCrashed
                    ? 'hsl(var(--destructive))'
                    : pos.isSelected
                      ? 'hsl(var(--primary))'
                      : 'hsl(var(--border))'
                }
                strokeWidth={pos.isCrashed ? '2.5' : pos.isSelected ? '2.5' : '1.5'}
                strokeDasharray={pos.isCrashed ? '6 4' : undefined}
              />

              {/* Leader marker slot (renders only when node is elected leader in Phase 5) */}
              {pos.isLeader && (
                <g aria-label="Elected cluster leader" transform={`translate(${pos.x - 7}, ${pos.y - pos.radius - 14})`}>
                  <path
                    d="M 2 10 L 4 2 L 7 6 L 10 2 L 12 10 Z"
                    fill="hsl(var(--success))"
                    stroke="hsl(var(--success-foreground))"
                    strokeWidth="0.5"
                  />
                </g>
              )}

              {/* Node ID label (scaled up to text-sm >= 12px on desktop) */}
              <text
                x={pos.x}
                y={pos.isCrashed ? pos.y - 5 : pos.y - 7}
                textAnchor="middle"
                className="fill-foreground font-sans text-sm font-semibold"
              >
                Node {pos.node.id}
              </text>

              {/* Status / Crashed text */}
              {pos.isCrashed ? (
                <text
                  x={pos.x}
                  y={pos.y + 12}
                  textAnchor="middle"
                  className="fill-destructive font-sans text-xs font-medium"
                >
                  Crashed
                </text>
              ) : (
                /* Thread pips for live node capacity */
                <g aria-hidden="true">
                  {pos.pips.map(([px, py], pipIdx) => (
                    <circle
                      key={`pip-${pipIdx}`}
                      cx={pos.x + px}
                      cy={pos.y + 11 + py}
                      r="2.5"
                      fill="hsl(var(--muted-foreground))"
                      opacity="0.8"
                    />
                  ))}
                </g>
              )}
            </g>
          )
        })}
      </svg>
    </div>
  )
}
