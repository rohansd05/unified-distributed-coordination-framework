import { ExternalLink } from 'lucide-react'
import { PageHeader } from '@/components/PageHeader'
import { grafanaUrl } from '@/lib/env'
import { NotFoundPage } from '@/pages/NotFoundPage'

/** Exists only when VITE_GRAFANA_URL is set (local mode); otherwise a 404. */
export function MonitoringPage() {
  const url = grafanaUrl()
  if (!url) {
    return <NotFoundPage />
  }

  return (
    <>
      <PageHeader
        title="Monitoring"
        description="Grafana dashboards for the local system."
      />
      <div className="space-y-3 rounded-lg border bg-card p-6">
        <p className="text-muted-foreground">
          Prometheus scrapes the backend&apos;s metrics, each tagged with the node it belongs to, and Grafana charts them.
        </p>
        {/* A plain anchor to the configured Grafana origin: an external page, not a router target. */}
        <a href={url} target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-2 font-medium text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
          Open Grafana in a new tab <ExternalLink className="size-4" aria-hidden="true" />
        </a>
      </div>
    </>
  )
}
