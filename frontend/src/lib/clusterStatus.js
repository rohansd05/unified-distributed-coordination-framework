/**
 * Canonical cluster node status helpers.
 *
 * The backend NodeDto serializes status from NodeStatus enum as "UP" or "CRASHED".
 * These helpers provide a single source of truth for the entire frontend
 * so the graph, inspector, and table/list views never disagree.
 */

/**
 * Returns true if the node is in a crashed state.
 * Supports "CRASHED" (backend wire format) and "DOWN" (legacy mock format).
 *
 * @param {{ status?: string } | null | undefined} node
 * @returns {boolean}
 */
export function isNodeCrashed(node) {
  if (!node || !node.status) return false
  const status = String(node.status).toUpperCase()
  return status === 'CRASHED' || status === 'DOWN'
}

/**
 * Returns true if the node is alive / up.
 *
 * @param {{ status?: string } | null | undefined} node
 * @returns {boolean}
 */
export function isNodeUp(node) {
  if (!node || !node.status) return false
  return !isNodeCrashed(node)
}

/**
 * Returns the human-readable status label ("Crashed" or "Up").
 *
 * @param {{ status?: string } | null | undefined} node
 * @returns {string}
 */
export function nodeStatusLabel(node) {
  return isNodeCrashed(node) ? 'Crashed' : 'Up'
}

/**
 * Returns the action button label for a node ("Recover node <id>" or "Crash node <id>").
 *
 * @param {{ id: number|string, status?: string } | null | undefined} node
 * @returns {string}
 */
export function nodeActionLabel(node) {
  if (!node) return ''
  return isNodeCrashed(node) ? `Recover node ${node.id}` : `Crash node ${node.id}`
}
