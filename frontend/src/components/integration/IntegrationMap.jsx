import { useId } from 'react'
import { isLeader, isNodeCrashed } from '@/lib/clusterStatus'
import { BUS, LINKS, SHORT_NAMES } from './integrationLinks'

/** Experiment boxes in viewBox units (760 x 540). The six role holders sit on an arc around Exp 4. */
const W = 120
const H = 60
const BOXES = {
  4: { x: 380, y: 230, w: 140, h: 64 },
  3: { x: 94, y: 221, w: W, h: H },
  5: { x: 164, y: 140, w: W, h: H },
  8: { x: 300, y: 91, w: W, h: H },
  7: { x: 460, y: 91, w: W, h: H },
  10: { x: 596, y: 140, w: W, h: H },
  9: { x: 666, y: 221, w: W, h: H },
  6: { x: 110, y: 350, w: W, h: H },
  2: { x: 280, y: 350, w: W, h: H },
  1: { x: 650, y: 350, w: W, h: H },
}
const GROUP = { x: 8, y: 8, w: 744, h: 392 }
const BUS_BOX = { x: 8, y: 448, w: 744, h: 80 }
const L5_X = 470
const L4_X = 240

/** Where the line from box `a` towards point `b` leaves the box. */
function border(a, b) {
  const dx = b.x - a.x
  const dy = b.y - a.y
  if (dx === 0 && dy === 0) return { x: a.x, y: a.y }
  const s = Math.min(dx ? (a.w / 2) / Math.abs(dx) : Infinity, dy ? (a.h / 2) / Math.abs(dy) : Infinity)
  return { x: a.x + dx * s, y: a.y + dy * s }
}

function pullBack(from, to, by) {
  const len = Math.hypot(to.x - from.x, to.y - from.y) || 1
  return { x: to.x - ((to.x - from.x) / len) * by, y: to.y - ((to.y - from.y) / len) * by }
}

/** SVG path and label position of each link's edges. */
function linkGeometry(link) {
  if (link.id === 'L2') {
    // Bows away from the L1 edge 4 -> 8 so the two stay apart.
    return { paths: ['M 372 198 Q 380 140 352 124'], chip: { x: 371, y: 152 } }
  }
  if (link.id === 'L4') {
    return {
      paths: [`M ${L4_X} ${GROUP.y + GROUP.h + 3} L ${L4_X} ${BUS_BOX.y - 3}`],
      chip: { x: L4_X, y: (GROUP.y + GROUP.h + BUS_BOX.y) / 2 },
      both: true,
    }
  }
  const paths = link.edges.map(([from, to]) => {
    const target = BOXES[to]
    const start = from === BUS ? { x: L5_X, y: BUS_BOX.y } : border(BOXES[from], target)
    const end = border(target, from === BUS ? { x: L5_X, y: BUS_BOX.y } : BOXES[from])
    const tip = pullBack(start, end, 3)
    return `M ${start.x.toFixed(1)} ${start.y.toFixed(1)} L ${tip.x.toFixed(1)} ${tip.y.toFixed(1)}`
  })
  const chips = {
    L1: { x: 237, y: 226 },
    L3: { x: 195, y: 334 },
    L5: { x: L5_X, y: 300 },
    L6: { x: 658, y: 286 },
  }
  return { paths, chip: chips[link.id] }
}

const ROLE_MARK = {
  match: { word: 'matches', tone: 'success' },
  mismatch: { word: 'differs', tone: 'warning' },
  idle: { word: 'not taken yet', tone: 'muted-foreground' },
  'no-leader': { word: 'no leader', tone: 'muted-foreground' },
  unavailable: { word: 'unreadable', tone: 'warning' },
  loading: { word: 'reading…', tone: 'muted-foreground' },
}

/** The role line of a holder box: a shape plus words, so colour is never the only signal. */
function roleText(row) {
  const mark = ROLE_MARK[row.state] || ROLE_MARK.loading
  if (row.state === 'match' || row.state === 'mismatch') return { ...mark, text: `node ${row.holder} ${mark.word}` }
  return { ...mark, text: mark.word }
}

function RoleGlyph({ state, x, y }) {
  const stroke = `hsl(var(--${ROLE_MARK[state]?.tone || 'muted-foreground'}))`
  if (state === 'match') {
    return <path d={`M ${x - 4} ${y} l 3 3 l 5 -6`} fill="none" stroke={stroke} strokeWidth="2" strokeLinecap="round" />
  }
  if (state === 'mismatch' || state === 'unavailable') {
    return (
      <g>
        <path d={`M ${x} ${y - 5} L ${x + 5} ${y + 4} L ${x - 5} ${y + 4} Z`} fill="none" stroke={stroke} strokeWidth="1.5" />
        <line x1={x} y1={y - 1.5} x2={x} y2={y + 1} stroke={stroke} strokeWidth="1.5" />
      </g>
    )
  }
  return <line x1={x - 4} y1={y} x2={x + 4} y2={y} stroke={stroke} strokeWidth="2" strokeLinecap="round" />
}

function ExperimentBox({ lab, row, leaderId, dimmed, involved }) {
  const box = BOXES[lab]
  const isHub = lab === 4
  const third = isHub
    ? { text: leaderId === null ? 'no leader yet' : `leader: node ${leaderId}`, tone: leaderId === null ? 'muted-foreground' : 'success' }
    : row ? roleText(row) : null
  const top = box.y - box.h / 2
  const label = [
    `Experiment ${lab}, ${SHORT_NAMES[lab]}`,
    isHub ? third.text : null,
    row ? `${row.role}: ${row.note}` : null,
  ].filter(Boolean).join('. ')
  return (
    <g
      role="img"
      aria-label={label}
      data-lab={lab}
      className="transition-opacity duration-200 motion-reduce:transition-none"
      opacity={dimmed ? 0.4 : 1}
    >
      <rect
        x={box.x - box.w / 2}
        y={top}
        width={box.w}
        height={box.h}
        rx="10"
        fill="hsl(var(--card))"
        stroke={involved ? 'hsl(var(--primary))' : 'hsl(var(--border))'}
        strokeWidth={involved ? 2.5 : 1.5}
      />
      <text x={box.x} y={top + (third ? 16 : 22)} textAnchor="middle" className="fill-muted-foreground text-[11px]">
        Exp {lab}
      </text>
      <text x={box.x} y={top + (third ? 34 : 42)} textAnchor="middle" className="fill-foreground text-[13px] font-semibold">
        {SHORT_NAMES[lab]}
      </text>
      {third && (
        <g aria-hidden="true">
          {!isHub && <RoleGlyph state={row.state} x={box.x - 44} y={top + 48} />}
          <text
            x={isHub ? box.x : box.x + 6}
            y={top + 52}
            textAnchor="middle"
            className="text-[11.5px]"
            fill={`hsl(var(--${third.tone}))`}
          >
            {third.text}
          </text>
        </g>
      )}
    </g>
  )
}

function ClusterBus({ nodes, latestLamport, dimmed, involved }) {
  const dots = nodes.slice(0, 7)
  const firstX = 740 - dots.length * 56
  return (
    <g
      role="img"
      aria-label={`The shared cluster: ${nodes.length} nodes and one event bus.${latestLamport != null ? ` Latest event at Lamport ${latestLamport}.` : ''}`}
      opacity={dimmed ? 0.4 : 1}
      className="transition-opacity duration-200 motion-reduce:transition-none"
    >
      <rect
        x={BUS_BOX.x}
        y={BUS_BOX.y}
        width={BUS_BOX.w}
        height={BUS_BOX.h}
        rx="14"
        fill="hsl(var(--navy) / 0.45)"
        stroke={involved ? 'hsl(var(--primary))' : 'hsl(var(--border))'}
        strokeWidth={involved ? 2.5 : 1.5}
      />
      <text x={28} y={BUS_BOX.y + 26} className="fill-foreground text-[14px] font-semibold">Shared cluster</text>
      <text x={28} y={BUS_BOX.y + 46} className="fill-muted-foreground text-[11.5px]">
        {nodes.length} nodes on real sockets, one event bus
      </text>
      <text x={28} y={BUS_BOX.y + 66} className="fill-muted-foreground text-[11.5px]">
        {latestLamport != null ? 'Latest event at Lamport ' : 'No event yet'}
        {latestLamport != null && (
          <tspan key={latestLamport} className="fill-foreground font-mono">{latestLamport}</tspan>
        )}
      </text>
      {dots.map((node, i) => {
        const cx = firstX + i * 56 + 28
        const cy = BUS_BOX.y + 46
        const down = isNodeCrashed(node)
        const leader = isLeader(node)
        return (
          <g key={node.id} aria-hidden="true">
            <circle
              cx={cx}
              cy={cy}
              r="15"
              fill={down ? 'hsl(var(--destructive) / 0.12)' : 'hsl(var(--card))'}
              stroke={down ? 'hsl(var(--destructive))' : leader ? 'hsl(var(--success))' : 'hsl(var(--border))'}
              strokeWidth={leader ? 2.5 : 1.5}
              strokeDasharray={down ? '4 3' : undefined}
            />
            <text x={cx} y={cy + 4} textAnchor="middle" className="fill-foreground text-[12px] font-semibold">
              {node.id}
            </text>
            {leader && (
              <path
                transform={`translate(${cx - 7}, ${cy - 30})`}
                d="M 2 10 L 4 2 L 7 6 L 10 2 L 12 10 Z"
                fill="hsl(var(--success))"
              />
            )}
            {down && (
              <text x={cx} y={cy + 28} textAnchor="middle" className="fill-destructive text-[10px]">down</text>
            )}
          </g>
        )
      })}
    </g>
  )
}

/**
 * The integration map (9A.8): the ten experiments and the shared cluster as one node graph, with
 * links L1-L6 as labelled edges. Selecting an edge or its label (click, Enter or Space) calls
 * onSelect with the link id. Hand-drawn SVG; no chart library.
 *
 * @param {object} props
 * @param {Array} props.nodes cluster nodes (GET /api/cluster)
 * @param {Array} props.roleRows from evidenceModel.roleRows
 * @param {number|null} props.leaderId
 * @param {number|null} props.latestLamport Lamport time of the latest event seen
 * @param {string|null} props.selected the selected link id
 * @param {(id: string|null) => void} props.onSelect
 */
export function IntegrationMap({ nodes = [], roleRows = [], leaderId = null, latestLamport = null, selected = null, onSelect }) {
  const id = useId()
  const markerId = (tone) => `${id}-arrow-${tone}`.replaceAll(':', '')
  const selectedLink = LINKS.find((l) => l.id === selected)
  const involved = new Set(selectedLink ? selectedLink.labs : [])
  const busInvolved = selected === 'L4' || selected === 'L5'
  const rowByLab = Object.fromEntries(roleRows.map((r) => [r.lab, r]))

  return (
    <svg
      viewBox="0 0 760 540"
      className="h-auto w-full select-none"
      role="group"
      aria-label="Integration map: ten experiments and the shared cluster joined by links L1 to L6"
    >
      <defs>
        {['idle', 'active'].map((tone) => (
          <marker
            key={tone}
            id={markerId(tone)}
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="7"
            markerHeight="7"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill={tone === 'active' ? 'hsl(var(--primary))' : 'hsl(var(--muted-foreground))'} />
          </marker>
        ))}
      </defs>

      <rect
        x={GROUP.x}
        y={GROUP.y}
        width={GROUP.w}
        height={GROUP.h}
        rx="18"
        fill="none"
        stroke="hsl(var(--border))"
        strokeDasharray="6 5"
        aria-hidden="true"
      />
      <text x={24} y={32} className="fill-muted-foreground text-[12px]" aria-hidden="true">Ten experiments</text>

      {LINKS.map((link) => {
        const geo = linkGeometry(link)
        const active = selected === link.id
        const dim = selected !== null && !active
        const stroke = active ? 'hsl(var(--primary))' : 'hsl(var(--muted-foreground) / 0.75)'
        const marker = `url(#${markerId(active ? 'active' : 'idle')})`
        const select = () => onSelect?.(active ? null : link.id)
        return (
          <g
            key={link.id}
            role="button"
            tabIndex={0}
            aria-pressed={active}
            aria-label={`${link.id}: ${link.meaning}`}
            data-link={link.id}
            onClick={select}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                select()
              }
            }}
            className="group cursor-pointer focus:outline-none"
            opacity={dim ? 0.3 : 1}
          >
            {geo.paths.map((d) => (
              <g key={d}>
                <path d={d} fill="none" stroke="transparent" strokeWidth="14" />
                <path
                  d={d}
                  fill="none"
                  stroke={stroke}
                  strokeWidth={active ? 2.75 : 1.6}
                  strokeDasharray={link.id === 'L2' ? '5 4' : undefined}
                  markerEnd={marker}
                  markerStart={geo.both ? marker : undefined}
                  className="transition-[stroke-width] duration-150 group-hover:[stroke-width:2.75] motion-reduce:transition-none"
                />
              </g>
            ))}
            <g transform={`translate(${geo.chip.x}, ${geo.chip.y})`}>
              <rect
                x="-17"
                y="-11"
                width="34"
                height="22"
                rx="11"
                fill={active ? 'hsl(var(--primary))' : 'hsl(var(--secondary))'}
                stroke="hsl(var(--primary))"
                strokeWidth="1.5"
              />
              <rect
                x="-21"
                y="-15"
                width="42"
                height="30"
                rx="15"
                fill="none"
                stroke="hsl(var(--ring))"
                strokeWidth="2"
                strokeDasharray="4 3"
                className="opacity-0 group-focus-visible:opacity-100"
              />
              <text y="4.5" textAnchor="middle" className="fill-foreground text-[12px] font-semibold">
                {link.id}
              </text>
            </g>
          </g>
        )
      })}

      {Object.keys(BOXES).map(Number).map((lab) => (
        <ExperimentBox
          key={lab}
          lab={lab}
          row={rowByLab[lab]}
          leaderId={leaderId}
          involved={involved.has(lab)}
          dimmed={selected !== null && !involved.has(lab)}
        />
      ))}

      <ClusterBus nodes={nodes} latestLamport={latestLamport} involved={busInvolved} dimmed={selected !== null && !busInvolved} />
    </svg>
  )
}
