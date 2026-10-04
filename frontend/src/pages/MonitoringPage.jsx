import { ComingSoon } from '@/components/ComingSoon'
import { PageHeader } from '@/components/PageHeader'
import { grafanaUrl } from '@/lib/env'
import { NotFoundPage } from '@/pages/NotFoundPage'

/** Exists only when VITE_GRAFANA_URL is set (local mode); otherwise a 404. */
export function MonitoringPage() {
  if (!grafanaUrl()) {
    return <NotFoundPage />
  }

  return (
    <>
      <PageHeader
        title="Monitoring"
        description="Grafana dashboards for the local system."
      />
      <ComingSoon when="Arrives in Phase 15" />
    </>
  )
}
