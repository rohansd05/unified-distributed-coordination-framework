import { useContext } from 'react'
import { ClusterContext } from '@/services/cluster/ClusterContext'

const DEFAULT_CLUSTER_CONTEXT = {
  info: null,
  cluster: null,
  modules: null,
  loading: true,
  error: null,
  refresh: () => {},
}

/**
 * Access cluster state, system information, and module statuses.
 * Safely falls back to default values when used outside ClusterProvider.
 */
export function useCluster() {
  const context = useContext(ClusterContext)
  return context || DEFAULT_CLUSTER_CONTEXT
}
