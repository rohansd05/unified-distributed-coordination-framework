import { useMemo, useState } from 'react'
import { IntegrationMap } from './IntegrationMap'
import { LinkList, LinkPanel } from './LinkPanel'
import { findLink, LINKS } from './integrationLinks'
import { leaderIdOf, linkEvidence, roleRows } from './evidenceModel'
import { useIntegrationEvidence } from './useIntegrationEvidence'

/**
 * Overview's "one system" section: the integration map with links L1-L6, and beside it either the
 * list of links or the selected link's panel. Live values come from the existing module overviews
 * (useIntegrationEvidence) plus the page's cluster and event stream.
 *
 * @param {object} props
 * @param {object|null} props.cluster GET /api/cluster (useCluster)
 * @param {Array} props.events the Overview event stream (for L4)
 */
export function IntegrationSection({ cluster, events = [] }) {
  const { overviews } = useIntegrationEvidence()
  const [selected, setSelected] = useState(null)

  const sources = useMemo(() => ({ cluster, overviews, events }), [cluster, overviews, events])
  const evidence = useMemo(
    () => Object.fromEntries(LINKS.map((link) => [link.id, linkEvidence(link.id, sources)])),
    [sources],
  )
  const rows = useMemo(() => roleRows(cluster, overviews), [cluster, overviews])
  const latestLamport = useMemo(() => {
    const latest = (events || []).reduce((best, e) => (!best || e.sequence > best.sequence ? e : best), null)
    return Number.isFinite(latest?.lamportTime) ? latest.lamportTime : null
  }, [events])
  const link = findLink(selected)

  return (
    <section aria-labelledby="integration-heading" className="rounded-lg border border-border bg-card p-4 sm:p-5" data-testid="integration-section">
      <div className="mb-3">
        <h2 id="integration-heading" className="text-lg font-semibold text-foreground">
          How the ten experiments work as one system
        </h2>
        <p className="text-sm text-muted-foreground">
          Every experiment runs on the same {cluster?.size ? `${cluster.size} nodes` : 'nodes'}. The arrows show where one experiment relies on another.
        </p>
      </div>
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="min-w-0">
          <IntegrationMap
            nodes={cluster?.nodes || []}
            roleRows={rows}
            leaderId={leaderIdOf(cluster)}
            latestLamport={latestLamport}
            selected={selected}
            onSelect={setSelected}
          />
          <p className="mt-2 text-xs text-muted-foreground">
            Role marks: a tick means the role sits on the leader, a triangle means it differs for now, a dash
            means it is not taken yet.
          </p>
        </div>
        <div className="min-w-0 rounded-md border border-border/70 bg-background/30 p-4">
          {link ? (
            <LinkPanel link={link} evidence={evidence[link.id]} onClose={() => setSelected(null)} />
          ) : (
            <LinkList evidence={evidence} onSelect={setSelected} />
          )}
        </div>
      </div>
    </section>
  )
}
