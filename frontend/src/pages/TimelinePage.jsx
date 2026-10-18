import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Eraser, Pause, Play, Search, WifiOff, X } from 'lucide-react'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { useCluster } from '@/hooks/useCluster'
import { isNodeCrashed } from '@/lib/clusterStatus'
import { cn } from '@/lib/utils'
import { api as defaultApi } from '@/services/api'
import { TimelineLanes } from '@/components/timeline/TimelineLanes'
import { useTimelineEvents } from '@/components/timeline/useTimelineEvents'
import {
  ORDER_ARRIVAL, ORDER_CAUSAL, ROLE_NAMES, applyFilters, describeEvent, filtersToParams, leaderChain,
  moduleOptions, nodeLabel, orderEvents, parseFilters, scenarioBands, typeLabel,
} from '@/components/timeline/timelineModel'

function Chip({ pressed, onClick, children }) {
  return (
    <button type="button" aria-pressed={pressed} onClick={onClick}
      className={cn('rounded-full border px-2.5 py-1 text-xs transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        pressed ? 'border-primary bg-primary/15 text-foreground' : 'border-border text-muted-foreground hover:text-foreground')}>
      {children}
    </button>
  )
}

function toggle(values, value) {
  return values.includes(value) ? values.filter((v) => v !== value) : [...values, value]
}

function EventDetail({ event, onClose }) {
  const rows = [
    ['Sequence', event.sequence, true],
    ['Lamport time', event.lamportTime, true],
    ['Node', nodeLabel(event.nodeId)],
    ['Peer', event.peerId == null ? '—' : nodeLabel(event.peerId)],
    ['Module', event.module],
    ['Type', event.type, true],
    ['Wall time', event.wallTime ?? '—', true],
  ]
  return (
    <section aria-label="Event details" data-testid="event-detail" className="space-y-3 rounded-lg border bg-card p-4">
      <div className="flex items-start justify-between gap-3">
        <h2 className="text-sm font-medium">{describeEvent(event)}</h2>
        <Button variant="ghost" size="icon" className="size-7 shrink-0" onClick={onClose} aria-label="Close details">
          <X className="size-4" aria-hidden="true" />
        </Button>
      </div>
      {event.message && <p className="text-sm text-muted-foreground">{event.message}</p>}
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
        {rows.map(([label, value, mono]) => (
          <div key={label} className="contents">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className={cn('break-all', mono && 'font-mono tabular-nums')}>{value}</dd>
          </div>
        ))}
      </dl>
      <div>
        <h3 className="mb-1 text-xs text-muted-foreground">Data</h3>
        <pre className="max-h-64 overflow-auto rounded-md bg-background/80 p-3 font-mono text-[11px] leading-relaxed">
          {JSON.stringify(event.data ?? {}, null, 2)}
        </pre>
      </div>
    </section>
  )
}

/**
 * /timeline: every module's events in one order, with one lane per node and the cluster lane,
 * the leader chain highlighted, filters in the URL query (see timelineModel PARAM), live from
 * /topic/events. Pause and clear act on this view only.
 */
export function TimelinePage({ api = defaultApi }) {
  const { events, loading, error, status } = useTimelineEvents({ api })
  const { cluster } = useCluster()
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = useMemo(() => parseFilters(searchParams), [searchParams])
  const [pausedAt, setPausedAt] = useState(null)
  const [clearedAt, setClearedAt] = useState(0)
  const [selected, setSelected] = useState(null)

  const setFilters = (patch) => setSearchParams(filtersToParams({ ...filters, ...patch }), { replace: true })
  const lastSequence = events.at(-1)?.sequence ?? 0

  const visible = useMemo(
    () => events.filter((e) => e.sequence > clearedAt && (pausedAt == null || e.sequence <= pausedAt)),
    [events, clearedAt, pausedAt],
  )
  const waiting = pausedAt == null ? 0 : events.filter((e) => e.sequence > pausedAt).length
  const shown = useMemo(
    () => orderEvents(applyFilters(visible, filters), filters.order),
    [visible, filters],
  )
  const selectedEvent = visible.find((e) => e.sequence === selected) ?? null
  const chain = useMemo(() => leaderChain(visible, selected), [visible, selected])
  const bands = useMemo(() => scenarioBands(visible), [visible])

  const nodeIds = useMemo(() => {
    const ids = new Set([0, ...(cluster?.nodes ?? []).map((n) => n.id), ...events.map((e) => e.nodeId)])
    return [...ids].sort((a, b) => a - b)
  }, [cluster?.nodes, events])
  const lanes = filters.nodes.length ? nodeIds.filter((id) => filters.nodes.includes(id)) : nodeIds
  const down = new Set((cluster?.nodes ?? []).filter(isNodeCrashed).map((n) => n.id))
  const filtering = filters.modules.length > 0 || filters.nodes.length > 0 || filters.q !== ''

  return (
    <div className="space-y-6">
      <PageHeader title="Timeline"
        description="Every module's events in one order, live: crash the leader and watch each role follow the new one." />

      {(status === 'reconnecting' || status === 'error') && (
        <div role="status" data-testid="disconnected-banner"
          className="flex items-start gap-3 rounded-lg border border-warning/40 bg-warning/10 px-4 py-3 text-sm">
          <WifiOff className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden="true" />
          <p>Live updates disconnected, retrying. The events below stay; anything missed loads when the connection returns.</p>
        </div>
      )}

      <section aria-label="Filters" className="space-y-3 rounded-lg border bg-card p-4">
        <div className="flex flex-wrap items-center gap-2">
          <div role="group" aria-label="Order" className="flex rounded-md border p-0.5">
            {[[ORDER_CAUSAL, 'Lamport order'], [ORDER_ARRIVAL, 'Arrival order (bus sequence)']].map(([value, label]) => (
              <button key={value} type="button" aria-pressed={filters.order === value} onClick={() => setFilters({ order: value })}
                className={cn('rounded px-2.5 py-1 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                  filters.order === value ? 'bg-primary/15 text-foreground' : 'text-muted-foreground hover:text-foreground')}>
                {label}
              </button>
            ))}
          </div>
          <Button variant="outline" size="sm" onClick={() => setPausedAt(pausedAt == null ? lastSequence : null)}>
            {pausedAt == null
              ? <><Pause className="size-4" aria-hidden="true" /> Pause live</>
              : <><Play className="size-4" aria-hidden="true" /> Resume live{waiting > 0 ? ` (${waiting} waiting)` : ''}</>}
          </Button>
          <Button variant="outline" size="sm" onClick={() => { setClearedAt(lastSequence); setSelected(null) }} disabled={!visible.length}>
            <Eraser className="size-4" aria-hidden="true" /> Clear view
          </Button>
          {clearedAt > 0 && (
            <Button variant="ghost" size="sm" onClick={() => setClearedAt(0)}>Show cleared events</Button>
          )}
          <label className="relative ml-auto flex min-w-[12rem] flex-1 items-center sm:max-w-xs">
            <span className="sr-only">Search event types and messages</span>
            <Search className="pointer-events-none absolute left-2.5 size-4 text-muted-foreground" aria-hidden="true" />
            <input type="search" value={filters.q} onChange={(e) => setFilters({ q: e.target.value })}
              placeholder="Search types and messages"
              className="h-8 w-full rounded-md border border-input bg-background pl-8 pr-2 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
          </label>
        </div>
        <div role="group" aria-label="Modules" className="flex flex-wrap gap-1.5">
          {moduleOptions(events).map((m) => (
            <Chip key={m.id} pressed={filters.modules.includes(m.id)} onClick={() => setFilters({ modules: toggle(filters.modules, m.id) })}>
              {m.label}
            </Chip>
          ))}
        </div>
        <div role="group" aria-label="Nodes" className="flex flex-wrap gap-1.5">
          {nodeIds.map((id) => (
            <Chip key={id} pressed={filters.nodes.includes(id)} onClick={() => setFilters({ nodes: toggle(filters.nodes, id) })}>
              {nodeLabel(id)}
            </Chip>
          ))}
          {filtering && <Button variant="ghost" size="sm" className="h-7" onClick={() => setSearchParams(filtersToParams({ order: filters.order }), { replace: true })}>Clear filters</Button>}
        </div>
        <p className="text-xs text-muted-foreground">
          Role changes carry the new holder&apos;s Lamport clock and LEADER_CHANGED the cluster clock (node 0), so in Lamport order a
          role change can appear before the election that caused it. The bus sequence is what guarantees it came after LEADER_CHANGED.
        </p>
      </section>

      {chain && (
        <p data-testid="leader-chain" className="rounded-lg border border-warning/40 bg-warning/5 px-4 py-3 text-sm">
          <span className="font-medium">{describeEvent(chain.anchor)}.</span>{' '}
          {chain.members.length
            ? `Then ${chain.members.length === 1 ? 'one role' : `${chain.members.length} roles`} followed it to node ${chain.leaderId}: ${[...new Set(chain.members.map((e) => ROLE_NAMES[e.type]))].join(', ')}.`
            : `No role has followed it to node ${chain.leaderId} yet; each moves when its module next acts.`}
        </p>
      )}

      {loading && !events.length ? (
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">Loading event history…</p>
      ) : !shown.length ? (
        <div data-testid="timeline-empty" className="space-y-2 rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          {error && !events.length && <p>Could not load the event history ({error.detail || error.message}). Live events still appear here.</p>}
          <p>
            {filtering && visible.length
              ? 'No events match these filters.'
              : clearedAt > 0
                ? 'View cleared. New events appear here as they arrive.'
                : 'No events yet. Crash a node on the Cluster page or run any experiment, and its events appear here.'}
          </p>
        </div>
      ) : (
        <>
          <section aria-label="Lanes" className="rounded-lg border bg-card p-4">
            <TimelineLanes events={shown} lanes={lanes} down={down} chain={chain} bands={bands}
              selected={selected} onSelect={(e) => setSelected(e.sequence)} />
          </section>
          <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
            <ol aria-label="Events" className="max-h-[32rem] divide-y divide-border/60 overflow-y-auto rounded-lg border bg-card">
              {[...shown].reverse().map((e) => {
                const inChain = chain?.sequences.has(e.sequence)
                return (
                  <li key={e.sequence}>
                    <button type="button" data-testid="timeline-row" aria-pressed={selected === e.sequence}
                      onClick={() => setSelected(e.sequence)}
                      className={cn('flex w-full items-center gap-3 px-4 py-2 text-left text-xs hover:bg-accent/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring',
                        selected === e.sequence && 'bg-accent/50', inChain && 'border-l-2 border-warning')}>
                      <span className="w-14 shrink-0 font-mono tabular-nums text-primary">L:{e.lamportTime}</span>
                      <span className="w-14 shrink-0 font-medium">{nodeLabel(e.nodeId)}</span>
                      <span className="min-w-0 flex-1 truncate">{describeEvent(e)}</span>
                      {inChain && <span className="shrink-0 text-warning">Leader chain</span>}
                      <span className="hidden shrink-0 text-muted-foreground md:inline">{typeLabel(e.type)}</span>
                      <span className="w-12 shrink-0 text-right font-mono tabular-nums text-muted-foreground">#{e.sequence}</span>
                    </button>
                  </li>
                )
              })}
            </ol>
            {selectedEvent
              ? <EventDetail event={selectedEvent} onClose={() => setSelected(null)} />
              : <p className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">Select an event in the lanes or the list to see all its data.</p>}
          </div>
        </>
      )}
    </div>
  )
}
