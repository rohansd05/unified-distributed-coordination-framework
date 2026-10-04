import { ComingSoon } from '@/components/ComingSoon'
import { PageHeader } from '@/components/PageHeader'

export function ClusterPage() {
  return (
    <>
      <PageHeader
        title="Cluster"
        description="Crash and recover each node, and see its capacity and running services."
      />
      <ComingSoon when="Arrives in Step 2.4" />
    </>
  )
}
