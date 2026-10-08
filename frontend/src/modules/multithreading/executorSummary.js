/** The one-sentence state of a node's executor, for screen readers. */
export function executorSummary(node) {
  if (!node) {
    return 'No node selected.'
  }
  if (node.nodeStatus === 'CRASHED') {
    return `Node ${node.nodeId} is crashed; its executor is shut down.`
  }
  const stats = node.stats
  if (!stats) {
    return `Node ${node.nodeId} has not started its executor yet.`
  }
  return `${stats.activeThreads} of ${stats.maxPoolSize} threads busy, ${stats.queuedRequests} of ${stats.queueCapacity} queued.`
}
