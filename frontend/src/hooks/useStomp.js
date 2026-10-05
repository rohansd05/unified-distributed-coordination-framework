import { useContext } from 'react'
import { StompContext } from '@/services/stomp/StompContext'

const DEFAULT_STOMP_CONTEXT = {
  client: null,
  status: 'disconnected',
  subscribe: () => () => {},
  onConnect: () => () => {},
}

/**
 * Access the shared STOMP connection.
 * Safely falls back to default values when used outside StompProvider.
 */
export function useStomp() {
  const context = useContext(StompContext)
  return context || DEFAULT_STOMP_CONTEXT
}
