import { useEffect, useRef } from 'react'
import { cn } from '@/lib/utils'
import { describeEvent, eventKind, nodeLabel } from './timelineModel'

/** The lanes show the most recent events of the chosen order; the list below shows all. */
export const MAX_LANE_EVENTS = 120

const LABEL_W = 92
const STEP = 30
const PAD = 22
const TOP = 26
const LANE_H = 44

function Marker({ kind, x, y }) {
  switch (kind) {
    case 'leader':
      return <polygon points={`${x},${y - 10} ${x + 10},${y} ${x},${y + 10} ${x - 10},${y}`} className="fill-warning stroke-background" strokeWidth="1.5" />
    case 'role':
      return <polygon points={`${x},${y - 7} ${x + 7},${y} ${x},${y + 7} ${x - 7},${y}`} className="fill-success stroke-background" strokeWidth="1.5" />
    case 'crash':
      return (
        <g className="stroke-destructive" strokeWidth="2.5" strokeLinecap="round">
          <line x1={x - 6} y1={y - 6} x2={x + 6} y2={y + 6} />
          <line x1={x - 6} y1={y + 6} x2={x + 6} y2={y - 6} />
        </g>
      )
    case 'recover':
      return <circle cx={x} cy={y} r={6} className="fill-background stroke-success" strokeWidth="2.5" />
    case 'warning':
      return <polygon points={`${x},${y - 8} ${x + 8},${y + 6} ${x - 8},${y + 6}`} className="fill-warning stroke-background" strokeWidth="1.5" />
    case 'scenario':
      return <rect x={x - 5} y={y - 5} width={10} height={10} rx={2} className="fill-primary stroke-background" strokeWidth="1" />
    default:
      return <circle cx={x} cy={y} r={4} className="fill-muted-foreground" />
  }
}

const LEGEND = [
  ['leader', 'Leader changed'],
  ['role', 'Role followed the leader'],
  ['crash', 'Node crashed'],
  ['recover', 'Node recovered or cluster reset'],
  ['warning', 'Aborted or failed'],
  ['scenario', 'Scenario step'],
  ['other', 'Other event'],
]

/**
 * One lane per node plus the cluster lane (node 0), in hand-drawn SVG. Positions are ranks in
 * the chosen order, not a time scale: Lamport times of different nodes are not comparable.
 *
 * @param {object} props
 * @param {Array<object>} props.events filtered events in display order
 * @param {Array<number>} props.lanes node ids, 0 first
 * @param {Set<number>} [props.down] node ids that are down now
 * @param {{ anchor: object, sequences: Set<number> } | null} [props.chain] the highlighted leader chain
 * @param {Array<object>} [props.bands] scenario runs (scenarioBands)
 * @param {number | null} [props.selected] selected event sequence
 * @param {(event: object) => void} props.onSelect
 */
export function TimelineLanes({ events, lanes, down = new Set(), chain = null, bands = [], selected = null, onSelect }) {
  const scroller = useRef(null)
  const shown = events.slice(-MAX_LANE_EVENTS)
  const lastSequence = shown.at(-1)?.sequence

  // Follow the newest event, as a log does.
  useEffect(() => {
    if (scroller.current) scroller.current.scrollLeft = scroller.current.scrollWidth
  }, [lastSequence])

  const laneIndex = new Map(lanes.map((id, i) => [id, i]))
  const xOf = (i) => LABEL_W + PAD + i * STEP
  const yOf = (nodeId) => TOP + (laneIndex.get(nodeId) ?? 0) * LANE_H + LANE_H / 2
  const width = Math.max(xOf(shown.length) + PAD, 560)
  const height = TOP + lanes.length * LANE_H + 8
  const points = shown.map((e, i) => ({ e, x: xOf(i), y: yOf(e.nodeId) }))
  const anchorPoint = chain && points.find((p) => p.e.sequence === chain.anchor.sequence)

  const bandRects = bands.flatMap((band) => {
    const inside = points.filter((p) => p.e.sequence >= band.startSequence
      && (band.endSequence == null || p.e.sequence <= band.endSequence))
    if (!inside.length) return []
    const x1 = Math.min(...inside.map((p) => p.x)) - STEP / 2
    const x2 = Math.max(...inside.map((p) => p.x)) + STEP / 2
    return [{ band, x1, x2 }]
  })

  return (
    <div className="space-y-3">
      {events.length > shown.length && (
        <p className="text-xs text-muted-foreground">
          The lanes show the latest {shown.length} of {events.length} events; the list below has all of them.
        </p>
      )}
      <div ref={scroller} className="overflow-x-auto rounded-md border bg-background/60" data-testid="timeline-lanes">
        <svg width={width} height={height} className="block select-none"
          aria-label={`Event lanes: ${shown.length} events across ${lanes.length} lanes. The list below gives every event in text.`}>
          {bandRects.map(({ band, x1, x2 }) => (
            <g key={band.key} data-testid="scenario-band">
              <rect x={x1} y={TOP - 4} width={x2 - x1} height={height - TOP} rx={6}
                className="fill-primary/10 stroke-primary/40" strokeDasharray="4 3" />
              <text x={x1 + 6} y={TOP - 9} className="fill-primary text-[10px] font-medium">
                {band.title}{band.endSequence == null ? ' (running)' : ''}
              </text>
            </g>
          ))}

          {lanes.map((id) => (
            <g key={`lane-${id}`}>
              <line x1={LABEL_W} x2={width - 8} y1={yOf(id)} y2={yOf(id)}
                className={down.has(id) ? 'stroke-destructive/50' : 'stroke-border'}
                strokeDasharray={down.has(id) ? '5 4' : undefined} strokeWidth="1.5" />
              <text x={10} y={yOf(id) + 4} className="fill-foreground text-xs font-medium">
                {nodeLabel(id)}
                {down.has(id) && <tspan className="fill-destructive text-[10px]"> (down)</tspan>}
              </text>
            </g>
          ))}

          {anchorPoint && points.filter((p) => p !== anchorPoint && chain.sequences.has(p.e.sequence)).map((p) => (
            <line key={`chain-${p.e.sequence}`} x1={anchorPoint.x} y1={anchorPoint.y} x2={p.x} y2={p.y}
              className="stroke-warning" strokeWidth="1.5" strokeDasharray="3 3" opacity="0.8" />
          ))}

          {points.map(({ e, x, y }) => {
            const inChain = chain?.sequences.has(e.sequence)
            const isSelected = selected === e.sequence
            return (
              <g key={e.sequence} role="button" tabIndex={0} data-testid="lane-marker"
                aria-label={`${describeEvent(e)}. ${nodeLabel(e.nodeId)}, Lamport time ${e.lamportTime}, sequence ${e.sequence}${inChain ? ', part of the leader chain' : ''}`}
                aria-pressed={isSelected}
                onClick={() => onSelect(e)}
                onKeyDown={(k) => { if (k.key === 'Enter' || k.key === ' ') { k.preventDefault(); onSelect(e) } }}
                className="group cursor-pointer focus:outline-none">
                <title>{`${describeEvent(e)} (L=${e.lamportTime}, #${e.sequence})`}</title>
                <circle cx={x} cy={y} r={13} className="fill-transparent stroke-ring opacity-0 group-focus-visible:opacity-100" strokeWidth="2" />
                {(inChain || isSelected) && (
                  <circle cx={x} cy={y} r={11} className={cn('fill-none', isSelected ? 'stroke-foreground' : 'stroke-warning')} strokeWidth="1.5" />
                )}
                <Marker kind={eventKind(e)} x={x} y={y} />
                {isSelected && (
                  <text x={x} y={y - 15} textAnchor="middle" className="fill-foreground font-mono text-[10px] tabular-nums">
                    L={e.lamportTime}
                  </text>
                )}
              </g>
            )
          })}
        </svg>
      </div>
      <ul className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground" aria-label="Legend">
        {LEGEND.map(([kind, label]) => (
          <li key={kind} className="flex items-center gap-1.5">
            <svg width="16" height="16" aria-hidden="true"><Marker kind={kind} x={8} y={8} /></svg>
            {label}
          </li>
        ))}
        <li className="flex items-center gap-1.5">
          <svg width="16" height="16" aria-hidden="true"><circle cx="8" cy="8" r="6" className="fill-none stroke-warning" strokeWidth="1.5" /></svg>
          Ringed: the leader chain
        </li>
      </ul>
    </div>
  )
}
