import { useEffect, useRef, useState } from 'react'
import { ArrowDown, CircleDashed, Copy, Crown, Info, PowerOff } from 'lucide-react'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { cn } from '@/lib/utils'
import { NOTE } from './faultToleranceModel'
import { NotMeasured } from './MeasurementsPanel'

const REJOIN_TEXT = {
  READY: 'Ready',
  REJOINING: 'Rejoining: asking who the primary is',
  WAITING_FOR_ANSWER: 'Waiting for an answer',
}

function roleOf(node) {
  if (node.nodeStatus !== 'UP') return { Icon: PowerOff, text: 'Down', className: 'text-destructive' }
  if (node.role === 'PRIMARY') return { Icon: Crown, text: 'Primary', className: 'text-success' }
  if (node.role === 'BACKUP') return { Icon: Copy, text: 'Backup', className: 'text-muted-foreground' }
  return { Icon: CircleDashed, text: 'No role yet', className: 'text-muted-foreground' }
}

function Row({ label, children }) {
  return (
    <div className="flex flex-wrap justify-between gap-x-2">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right">{children}</dd>
    </div>
  )
}

/**
 * One node. It is keyed by node and role, so a role change mounts a new tile; `entering` is
 * fixed at mount, so its one-shot animation plays once and never replays on a later poll.
 */
function NodeTile({ node, demotedFrom, entering }) {
  const [motion] = useState(entering)
  const down = node.nodeStatus !== 'UP'
  const primary = !down && node.role === 'PRIMARY'
  const role = roleOf(node)
  const epochReason = down ? 'Unknown while the node is down' : 'Not known yet: no replication service on this node'

  return (
    <li data-testid={`node-${node.nodeId}`} data-role={node.role ?? 'none'}
      className={cn('relative z-10 flex min-w-[9.5rem] flex-1 flex-col gap-3 rounded-lg border bg-card p-3 text-xs',
        primary && 'border-success/70 bg-success/10 ring-1 ring-success/40 sm:flex-[1.35]',
        down && 'border-dashed border-destructive/60 bg-destructive/5',
        motion === 'promote' && 'animate-in zoom-in-90 fade-in duration-500 motion-reduce:animate-none',
        motion === 'demote' && 'animate-in slide-in-from-top-3 fade-in duration-500 motion-reduce:animate-none')}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-semibold">Node {node.nodeId}</span>
        <span className={cn('inline-flex items-center gap-1 font-medium', role.className)}>
          <role.Icon aria-hidden="true" className="size-3.5" />
          {role.text}
        </span>
      </div>
      <div>
        <p className="text-[11px] text-muted-foreground">Epoch</p>
        <p data-testid="epoch" className={cn('font-semibold leading-none tabular-nums', primary ? 'text-5xl' : 'text-4xl')}>
          {node.epoch ?? <NotMeasured label="Unknown" reason={epochReason} />}
        </p>
      </div>
      <dl className="space-y-0.5">
        <Row label="Serving">{down ? 'No, down' : node.serving ? 'Yes' : 'No'}</Row>
        <Row label="Believes the primary is">
          {node.believedPrimaryId ? `Node ${node.believedPrimaryId}` : <NotMeasured label="Unknown" reason={down ? 'Unknown while the node is down' : 'No primary known'} />}
        </Row>
        <Row label="Rejoin">
          {REJOIN_TEXT[node.rejoin] ?? <NotMeasured label="Unknown" reason={down ? 'Unknown while the node is down' : 'Not reported'} />}
        </Row>
      </dl>
      {demotedFrom !== null && (
        <p data-testid="demoted" className="flex items-center gap-1 font-medium text-warning">
          <ArrowDown aria-hidden="true" className="size-3.5" />
          Demoted itself: primary at epoch {demotedFrom}, now backup at epoch {node.epoch}
        </p>
      )}
    </li>
  )
}

/**
 * The cluster as Experiment 8 sees it: every node on one rail with its role, its epoch (large,
 * on every node), whether it serves, which node it believes is primary, and its rejoin state.
 * Null role, epoch or rejoin (a node that is down, or not started) stays "Unknown", never 0.
 * The old primary that came back is marked with its own demotion, from the latest run.
 *
 * Motion only on role changes (a promotion, a demotion), never on the first load and never under
 * prefers-reduced-motion (motion-reduce:animate-none).
 *
 * @param {object} props
 * @param {object} props.status
 * @param {object|null} props.run
 * @param {object} [props.settings]
 * @param {string[]} [props.notes]
 */
export function ReplicaRail({ status, run, settings, notes = [] }) {
  const nodes = status?.nodes ?? []
  const previousRolesRef = useRef(null)
  const previous = previousRolesRef.current
  useEffect(() => {
    previousRolesRef.current = Object.fromEntries(nodes.map((node) => [node.nodeId, node.role]))
  })

  const demoted = run?.milestones?.some((m) => m.kind === 'DEMOTED') ? run.oldPrimaryId : null
  const entering = (node) => {
    if (!previous || !(node.nodeId in previous) || previous[node.nodeId] === node.role) return null
    if (node.role === 'PRIMARY') return 'promote'
    // A crash clears the role, so the returning old primary comes back from "none" to backup.
    if (node.role === 'BACKUP' && (previous[node.nodeId] === 'PRIMARY' || node.nodeId === run?.oldPrimaryId)) return 'demote'
    return null
  }

  return (
    <div data-testid="replica-rail" className="space-y-3">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
        <p>
          {status?.currentPrimaryId
            ? <>Node {status.currentPrimaryId} is primary at epoch <span className="font-semibold tabular-nums">{status.primaryEpoch}</span>.</>
            : 'No primary serves right now.'}
        </p>
        <p className="text-muted-foreground">
          Highest epoch known: <span className="tabular-nums text-foreground">{status?.highestEpoch ?? 'unknown'}</span>
        </p>
        {settings?.coHostingSimulated && (
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            One process, real sockets <SimulatedBadge reason={settings.coHostingReason ?? undefined} />
          </span>
        )}
      </div>
      <div className="relative">
        <div aria-hidden="true" className="absolute inset-x-4 top-1/2 hidden h-px bg-border sm:block" />
        <ol aria-label="Nodes" className="relative flex flex-wrap gap-3">
          {nodes.map((node) => (
            <NodeTile key={`${node.nodeId}-${node.role ?? 'none'}-${node.nodeStatus}`} node={node} entering={entering(node)}
              demotedFrom={demoted === node.nodeId && node.role === 'BACKUP' && node.nodeStatus === 'UP' ? run.oldEpoch : null} />
          ))}
        </ol>
      </div>
      {notes[NOTE.exp5Interference] && (
        <p data-testid="exp5-note" className="flex items-start gap-2 text-xs text-muted-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          {notes[NOTE.exp5Interference]}
        </p>
      )}
    </div>
  )
}
