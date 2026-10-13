import { CheckCircle2, XCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { NotAvailable } from './CallResult'
import { failureKindDescription, failureKindLabel, nodeStateLabel } from './labels'

/**
 * What each node's RMI registry holds (GET /registries, read over the network): the bound
 * names and their stub classes. A node that is down or unreachable is shown as unreachable
 * with the real failure, never as an empty registry.
 *
 * @param {object} props
 * @param {object[]|null} props.registries RegistryDto[]
 * @param {Error|null} [props.error] the last failed read
 * @param {object|null} [props.overview] for the failure descriptions
 * @param {() => void} [props.onRetry]
 */
export function RegistryList({ registries, error, overview, onRetry }) {
  if (!Array.isArray(registries)) {
    if (error) {
      return (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-4 text-sm">
          <p className="font-semibold text-destructive">Could not list the registries.</p>
          <p className="mt-1 text-muted-foreground">{error.detail || error.message}</p>
          {onRetry && <Button variant="outline" size="sm" className="mt-3" onClick={onRetry}>Try again</Button>}
        </div>
      )
    }
    return (
      <div className="h-24 animate-pulse rounded-lg bg-card/60 motion-reduce:animate-none" aria-busy="true">
        <span className="sr-only">Loading the registries...</span>
      </div>
    )
  }
  return (
    <ul data-testid="registry-list" className="divide-y rounded-lg border bg-card text-sm">
      {registries.map((registry) => (
        <li key={registry.nodeId} data-testid={`registry-${registry.nodeId}`} className="space-y-1 px-4 py-3">
          <p className="flex flex-wrap items-center gap-2">
            {registry.reachable
              ? <CheckCircle2 aria-hidden="true" className="size-4 text-success" />
              : <XCircle aria-hidden="true" className="size-4 text-destructive" />}
            <span className="font-medium">Node {registry.nodeId}</span>
            <span className="text-muted-foreground">registry on port <span className="font-mono">{registry.registryPort}</span>,</span>
            <span className="text-muted-foreground">endpoint {nodeStateLabel(registry.state).toLowerCase()}</span>
          </p>
          {registry.reachable ? (
            Array.isArray(registry.names) && registry.names.length > 0 ? (
              <ul className="space-y-0.5 pl-6">
                {registry.names.map((bound) => (
                  <li key={bound.name}>
                    <span className="font-mono">{bound.name}</span>
                    <span className="text-muted-foreground"> bound to stub </span>
                    <span className="break-all font-mono text-xs">{bound.stubClassName}</span>
                  </li>
                ))}
              </ul>
            ) : Array.isArray(registry.names) ? (
              <p className="pl-6 text-muted-foreground">Reachable, and no name is bound.</p>
            ) : (
              <p className="pl-6"><NotAvailable reason="The backend did not list the names" /></p>
            )
          ) : (
            <div className="pl-6" data-testid="registry-unreachable">
              <p className="text-destructive">Unreachable: {failureKindLabel(registry.failureKind).toLowerCase() || 'no answer'}.</p>
              {(failureKindDescription(overview, registry.failureKind) ?? registry.failureDescription) && (
                <p className="text-xs text-muted-foreground">
                  {failureKindDescription(overview, registry.failureKind) ?? registry.failureDescription}
                </p>
              )}
              {registry.failureClass && <p className="break-all font-mono text-xs text-muted-foreground">{registry.failureClass}</p>}
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}
