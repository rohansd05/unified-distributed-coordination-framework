import { NotAvailable } from '@/modules/rmi/CallResult'
import { nodeStatusText, rmiStateLabel } from './labels'

/**
 * Every node as the overview reports it: up or down, its RMI state, registry port, whether it
 * exports CollectiveEndpoint, the leader marker, the collective ids it still keeps (null while
 * its endpoint is not exported, shown as n/a; [] is "none"), and a start failure if there was
 * one. A crashed node's row is struck through in colour and says "Down" in words.
 *
 * @param {object} props
 * @param {object[]|null} props.participants overview.participants
 */
export function NodeStrip({ participants }) {
  const nodes = Array.isArray(participants) ? participants : []
  if (nodes.length === 0) return null
  return (
    <div className="space-y-1">
      <h3 className="text-sm font-semibold">Nodes</h3>
      <div className="overflow-x-auto rounded-lg border bg-card">
        <table data-testid="node-strip" className="w-full min-w-[36rem] text-left text-sm">
          <thead className="border-b text-xs text-muted-foreground">
            <tr>
              <th scope="col" className="px-3 py-2 font-medium">Node</th>
              <th scope="col" className="px-2 py-2 font-medium">Status</th>
              <th scope="col" className="px-2 py-2 font-medium">RMI</th>
              <th scope="col" className="px-2 py-2 font-medium">Registry port</th>
              <th scope="col" className="px-2 py-2 font-medium">CollectiveEndpoint</th>
              <th scope="col" className="px-3 py-2 font-medium">Collectives kept</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {nodes.map((node) => {
              const crashed = node.nodeStatus === 'CRASHED'
              return [
                <tr key={node.nodeId} data-testid={`node-row-${node.nodeId}`} data-crashed={crashed ? 'true' : 'false'}
                  className={crashed ? 'bg-destructive/10 text-muted-foreground' : undefined}>
                  <td className="px-3 py-1.5 font-medium">
                    Node {node.nodeId}
                    {node.leader && <span data-testid="leader-marker" className="ml-2 rounded border border-success/50 px-1 text-xs text-success">leader</span>}
                  </td>
                  <td className={`px-2 py-1.5 ${crashed ? 'font-medium text-destructive' : ''}`}>{nodeStatusText(node.nodeStatus)}</td>
                  <td className="px-2 py-1.5">{rmiStateLabel(node.rmiState)}</td>
                  <td className="px-2 py-1.5 font-mono">{node.registryPort}</td>
                  <td className="px-2 py-1.5">{node.collectiveEndpointExported ? 'Exported' : 'Not exported'}</td>
                  <td className="px-3 py-1.5" data-testid={`stored-${node.nodeId}`}>
                    {node.storedCollectiveIds === null || node.storedCollectiveIds === undefined
                      ? <NotAvailable reason="The endpoint is not exported, so it keeps nothing" />
                      : node.storedCollectiveIds.length === 0
                        ? 'none'
                        : <span className="font-mono">{node.storedCollectiveIds.join(', ')}</span>}
                  </td>
                </tr>,
                node.startFailure ? (
                  <tr key={`${node.nodeId}-failure`} data-testid={`start-failure-${node.nodeId}`}>
                    <td colSpan={6} className="px-3 pb-2 text-xs text-destructive">
                      Start failed: <span className="whitespace-pre-wrap break-words">{node.startFailure}</span>
                    </td>
                  </tr>
                ) : null,
              ]
            })}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-muted-foreground">
        Collectives kept: the ids whose payload each node still holds; the root keeps nothing of its own.
      </p>
    </div>
  )
}
