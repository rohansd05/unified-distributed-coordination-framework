import { ComingSoon } from '@/components/ComingSoon'
import { PageHeader } from '@/components/PageHeader'

export function OverviewPage() {
  return (
    <>
      <PageHeader
        title="Overview"
        description="Cluster health, nodes, the live event stream and quick actions."
      />
      <ComingSoon when="Arrives in Step 2.4" />
    </>
  )
}
