import { Link } from 'react-router-dom'
import { PageHeader } from '@/components/PageHeader'

export function NotFoundPage() {
  return (
    <>
      <PageHeader title="Page not found" description="There is no page at this address." />
      <Link
        to="/"
        className="inline-flex text-primary underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        Back to Overview
      </Link>
    </>
  )
}
