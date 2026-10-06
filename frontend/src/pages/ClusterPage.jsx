import { useState } from 'react'
import { useCluster } from '@/hooks/useCluster'
import { useToast } from '@/hooks/use-toast'
import { api as defaultApi } from '@/services/api'
import { getConfig } from '@/lib/config'
import { isNodeCrashed } from '@/lib/clusterStatus'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'

export function ClusterPage({ api = defaultApi }) {
  const { cluster, loading, error, refresh } = useCluster()
  const { toast } = useToast()

  const [pendingAction, setPendingAction] = useState(null)
  const [resetDialogOpen, setResetDialogOpen] = useState(false)

  const nodes = cluster?.nodes || []
  const upCount = cluster?.upCount ?? 0
  const size = cluster?.size ?? nodes.length

  const handleCrash = async (nodeId) => {
    setPendingAction(`crash-${nodeId}`)
    const targetNode = nodes.find((n) => n.id === nodeId)
    const running = targetNode?.runningServices || []
    try {
      await api.crashNode(nodeId)
      const description = running.length > 0
        ? `Stopped services: ${running.join(', ')}.`
        : 'No services were running on it yet.'
      toast({
        title: `Node ${nodeId} crashed`,
        description,
      })
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Crash failed',
        description: err.detail || err.message || `Failed to crash node ${nodeId}`,
      })
    } finally {
      setPendingAction(null)
    }
  }

  const handleRecover = async (nodeId) => {
    setPendingAction(`recover-${nodeId}`)
    try {
      await api.recoverNode(nodeId)
      toast({
        title: `Node ${nodeId} recovered`,
        description: `All sockets re-bound on node ${nodeId}.`,
      })
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Recovery failed',
        description: err.detail || err.message || `Failed to recover node ${nodeId}`,
      })
    } finally {
      setPendingAction(null)
    }
  }

  const handleReset = async () => {
    setPendingAction('reset')
    try {
      await api.resetCluster()
      toast({
        title: 'Cluster reset',
        description: 'All nodes recovered, Lamport clocks restarted, event history cleared.',
      })
      setResetDialogOpen(false)
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Reset failed',
        description: err.detail || err.message || 'Failed to reset cluster',
      })
    } finally {
      setPendingAction(null)
    }
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between border-b border-border/60 pb-5">
        <div>
          <h1 className="text-2xl font-semibold text-foreground tracking-tight">Cluster</h1>
          <p className="text-sm text-muted-foreground">
            Crash and recover each node, and inspect its capacity and running services
          </p>
        </div>

        <div className="flex items-center gap-3">
          <span
            data-testid="cluster-health-statement"
            className="inline-flex items-center gap-2 rounded-full border border-border bg-card px-3 py-1 text-xs font-medium text-foreground"
          >
            <span
              className={`h-2 w-2 rounded-full ${upCount === size && size > 0 ? 'bg-success' : 'bg-destructive'}`}
              aria-hidden="true"
            />
            {loading ? '— of — nodes up' : `${upCount} of ${size} nodes up`}
          </span>

          <AlertDialog open={resetDialogOpen} onOpenChange={setResetDialogOpen}>
            <AlertDialogTrigger asChild>
              <Button variant="outline" size="sm" disabled={pendingAction === 'reset' || loading}>
                {pendingAction === 'reset' ? 'Resetting…' : 'Reset cluster'}
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Reset cluster to clean slate?</AlertDialogTitle>
                <AlertDialogDescription>
                  This recovers every node, restarts all Lamport clocks to 0 (the reset event is stamped 1), and clears the event history. All experiment modules return to an idle state.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel disabled={pendingAction === 'reset'}>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={(e) => {
                    e.preventDefault()
                    handleReset()
                  }}
                  disabled={pendingAction === 'reset'}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  {pendingAction === 'reset' ? 'Resetting…' : 'Reset cluster'}
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </div>

      {loading && (
        <div data-testid="cluster-loading" className="space-y-4">
          <div className="h-10 w-full animate-pulse rounded bg-muted/40" />
          <div className="h-64 animate-pulse rounded-lg bg-card/60" />
        </div>
      )}

      {!loading && error && (
        <div data-testid="cluster-error" className="rounded-lg border border-destructive/40 bg-card p-6 text-center">
          <h2 className="text-lg font-semibold text-foreground">Cluster connection unavailable</h2>
          <p className="mt-2 text-sm text-destructive font-medium">
            Can&apos;t reach the backend at {getConfig().apiBaseUrl || 'http://localhost:8080'}.
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            Start it with: <code className="font-mono text-foreground font-semibold">cd backend; .\mvnw.cmd spring-boot:run</code>
          </p>
          <Button onClick={refresh} className="mt-4" variant="outline" size="sm">
            Try again
          </Button>
        </div>
      )}

      {!loading && !error && (
        <>
          {/* Mobile Stacked Block Layout (< 768px, D3) */}
          <div className="divide-y divide-border/60 rounded-lg border border-border bg-card md:hidden" data-testid="cluster-nodes-mobile">
            {nodes.map((node) => {
              const isCrashed = isNodeCrashed(node)
              const isPending = pendingAction === `crash-${node.id}` || pendingAction === `recover-${node.id}`
              const threadCount = node.capacity?.threads ?? 0

              return (
                <div key={`mobile-node-${node.id}`} data-testid={`mobile-node-${node.id}`} className="p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <span className="font-semibold text-foreground text-sm">Node {node.id}</span>
                    <span
                      className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-medium ${
                        isCrashed
                          ? 'bg-destructive/15 text-destructive border border-destructive/30'
                          : 'bg-success/15 text-success border border-success/30'
                      }`}
                    >
                      <span className={`h-1.5 w-1.5 rounded-full ${isCrashed ? 'bg-destructive' : 'bg-success'}`} />
                      {isCrashed ? 'Crashed' : 'Up'}
                    </span>
                  </div>

                  <div className="text-xs text-foreground">
                    <span className="text-muted-foreground">Capacity: </span>
                    <span className="font-medium">{node.capacity?.name || 'MEDIUM'}</span>
                    <span className="text-muted-foreground ml-1">
                      ({threadCount} {threadCount === 1 ? 'thread' : 'threads'}, {node.capacity?.workMultiplier || 1}x work)
                    </span>
                  </div>

                  {/* 2x3 monospace grid with labels */}
                  <div>
                    <span className="text-muted-foreground text-[11px] font-medium block mb-1">Sockets & Ports</span>
                    <div className="grid grid-cols-2 gap-1.5 font-mono text-[11px]">
                      <div className="rounded bg-navy/30 px-2 py-1 border border-border/70 flex justify-between">
                        <span className="text-muted-foreground font-sans text-[10px]">RMI</span>
                        <span className="text-foreground">{node.ports?.rmi || '—'}</span>
                      </div>
                      <div className="rounded bg-navy/30 px-2 py-1 border border-border/70 flex justify-between">
                        <span className="text-muted-foreground font-sans text-[10px]">Clock</span>
                        <span className="text-foreground">{node.ports?.clock || '—'}</span>
                      </div>
                      <div className="rounded bg-navy/30 px-2 py-1 border border-border/70 flex justify-between">
                        <span className="text-muted-foreground font-sans text-[10px]">Election</span>
                        <span className="text-foreground">{node.ports?.election || '—'}</span>
                      </div>
                      <div className="rounded bg-navy/30 px-2 py-1 border border-border/70 flex justify-between">
                        <span className="text-muted-foreground font-sans text-[10px]">Replication</span>
                        <span className="text-foreground">{node.ports?.replication || '—'}</span>
                      </div>
                      <div className="rounded bg-navy/30 px-2 py-1 border border-border/70 flex justify-between">
                        <span className="text-muted-foreground font-sans text-[10px]">Requests</span>
                        <span className="text-foreground">{node.ports?.requests || '—'}</span>
                      </div>
                      <div className="rounded bg-navy/30 px-2 py-1 border border-border/70 flex justify-between">
                        <span className="text-muted-foreground font-sans text-[10px]">MapReduce</span>
                        <span className="text-foreground">{node.ports?.mapreduce || '—'}</span>
                      </div>
                    </div>
                  </div>

                  <div className="text-xs">
                    <span className="text-muted-foreground">Services: </span>
                    <span className="text-foreground">
                      {node.runningServices && node.runningServices.length > 0
                        ? node.runningServices.join(', ')
                        : 'None running yet'}
                    </span>
                  </div>

                  {node.roles && node.roles.length > 0 && (
                    <div className="text-xs">
                      <span className="text-muted-foreground">Roles: </span>
                      <span className="font-medium text-success">{node.roles.join(', ')}</span>
                    </div>
                  )}

                  <div className="pt-1">
                    {isCrashed ? (
                      <Button
                        variant="default"
                        size="sm"
                        disabled={isPending}
                        onClick={() => handleRecover(node.id)}
                        className="w-full bg-primary hover:bg-primary/90 text-primary-foreground text-xs"
                      >
                        {isPending ? `Recovering node ${node.id}…` : `Recover node ${node.id}`}
                      </Button>
                    ) : (
                      <Button
                        variant="destructive"
                        size="sm"
                        disabled={isPending}
                        onClick={() => handleCrash(node.id)}
                        className="w-full text-xs"
                      >
                        {isPending ? `Crashing node ${node.id}…` : `Crash node ${node.id}`}
                      </Button>
                    )}
                  </div>
                </div>
              )
            })}
          </div>

          {/* Desktop Table Layout (>= 768px, D3) */}
          <div className="hidden md:block rounded-lg border border-border bg-card overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs border-collapse" data-testid="cluster-nodes-table">
                <thead>
                  <tr className="border-b border-border bg-navy/30 text-muted-foreground font-medium">
                    <th scope="col" className="px-4 py-3">Node</th>
                    <th scope="col" className="px-4 py-3">Status</th>
                    <th scope="col" className="px-4 py-3">Capacity</th>
                    <th scope="col" className="px-4 py-3">Sockets & Ports (RMI, Clock, Elect, Repl, Req, MR)</th>
                    <th scope="col" className="px-4 py-3">Services</th>
                    <th scope="col" className="px-4 py-3">Roles</th>
                    <th scope="col" className="px-4 py-3 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/60">
                  {nodes.map((node) => {
                    const isCrashed = isNodeCrashed(node)
                    const isPending = pendingAction === `crash-${node.id}` || pendingAction === `recover-${node.id}`
                    const threadCount = node.capacity?.threads ?? 0

                    return (
                      <tr
                        key={`node-row-${node.id}`}
                        data-testid={`node-row-${node.id}`}
                        className="hover:bg-accent/30 transition-colors"
                      >
                        <td className="px-4 py-3 font-semibold text-foreground whitespace-nowrap">
                          Node {node.id}
                        </td>

                        <td className="px-4 py-3 whitespace-nowrap">
                          <span
                            className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-medium ${
                              isCrashed
                                ? 'bg-destructive/15 text-destructive border border-destructive/30'
                                : 'bg-success/15 text-success border border-success/30'
                            }`}
                          >
                            <span
                              className={`h-1.5 w-1.5 rounded-full ${isCrashed ? 'bg-destructive' : 'bg-success'}`}
                            />
                            {isCrashed ? 'Crashed' : 'Up'}
                          </span>
                        </td>

                        <td className="px-4 py-3 whitespace-nowrap text-foreground">
                          <span className="font-medium">{node.capacity?.name || 'MEDIUM'}</span>
                          <span className="text-muted-foreground ml-1.5">
                            ({threadCount} {threadCount === 1 ? 'thread' : 'threads'}, {node.capacity?.workMultiplier || 1}x work)
                          </span>
                        </td>

                        <td className="px-4 py-3">
                          <div className="flex flex-wrap items-center gap-1 font-mono text-[11px]">
                            <span title="RMI registry port" className="rounded bg-navy/40 px-1.5 py-0.5 text-foreground border border-border/70">
                              {node.ports?.rmi || '—'}
                            </span>
                            <span title="Clock sync UDP port" className="rounded bg-navy/40 px-1.5 py-0.5 text-foreground border border-border/70">
                              {node.ports?.clock || '—'}
                            </span>
                            <span title="Election UDP port" className="rounded bg-navy/40 px-1.5 py-0.5 text-foreground border border-border/70">
                              {node.ports?.election || '—'}
                            </span>
                            <span title="Replication TCP port" className="rounded bg-navy/40 px-1.5 py-0.5 text-foreground border border-border/70">
                              {node.ports?.replication || '—'}
                            </span>
                            <span title="Requests TCP port" className="rounded bg-navy/40 px-1.5 py-0.5 text-foreground border border-border/70">
                              {node.ports?.requests || '—'}
                            </span>
                            <span title="MapReduce TCP port" className="rounded bg-navy/40 px-1.5 py-0.5 text-foreground border border-border/70">
                              {node.ports?.mapreduce || '—'}
                            </span>
                          </div>
                        </td>

                        <td className="px-4 py-3 text-muted-foreground">
                          {node.runningServices && node.runningServices.length > 0
                            ? node.runningServices.join(', ')
                            : 'None running yet'}
                        </td>

                        <td className="px-4 py-3 text-muted-foreground">
                          {node.roles && node.roles.length > 0 ? (
                            <span className="font-medium text-success">{node.roles.join(', ')}</span>
                          ) : null}
                        </td>

                        <td className="px-4 py-3 text-right whitespace-nowrap">
                          {isCrashed ? (
                            <Button
                              variant="default"
                              size="sm"
                              disabled={isPending}
                              onClick={() => handleRecover(node.id)}
                              className="bg-primary hover:bg-primary/90 text-primary-foreground text-xs"
                            >
                              {isPending ? `Recovering node ${node.id}…` : `Recover node ${node.id}`}
                            </Button>
                          ) : (
                            <Button
                              variant="destructive"
                              size="sm"
                              disabled={isPending}
                              onClick={() => handleCrash(node.id)}
                              className="text-xs"
                            >
                              {isPending ? `Crashing node ${node.id}…` : `Crash node ${node.id}`}
                            </Button>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
