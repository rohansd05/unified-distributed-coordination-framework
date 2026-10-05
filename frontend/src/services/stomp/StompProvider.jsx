import { useEffect, useRef, useState } from 'react'
import { Client, ReconnectionTimeMode } from '@stomp/stompjs'
import { StompContext } from './StompContext'

/**
 * Shared STOMP connection provider.
 *
 * Manages a single @stomp/stompjs Client for the entire application,
 * exposes connection status, parses incoming JSON frames, and automatically
 * re-subscribes all active subscriptions across reconnects.
 */
export function StompProvider({ wsUrl, children }) {
  const [status, setStatus] = useState('connecting')
  const clientRef = useRef(null)
  const subscriptionsRef = useRef(new Map())
  const connectListenersRef = useRef(new Set())
  const subCounterRef = useRef(0)

  useEffect(() => {
    if (!wsUrl) {
      setStatus('disconnected')
      return undefined
    }

    const client = new Client({
      brokerURL: wsUrl,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      reconnectDelay: 1000,
      maxReconnectDelay: 30000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      onConnect: (frame) => {
        setStatus('connected')

        // Re-subscribe all active subscriptions
        subscriptionsRef.current.forEach((sub, id) => {
          sub.stompSub = client.subscribe(
            sub.destination,
            (message) => {
              let payload = message.body
              try {
                payload = JSON.parse(message.body)
              } catch {
                // Keep raw string if non-JSON
              }
              sub.handler(payload, message)
            },
            { id },
          )
        })

        // Notify all onConnect listeners
        connectListenersRef.current.forEach((listener) => {
          try {
            listener(frame)
          } catch (err) {
            console.error('Error in STOMP onConnect listener:', err)
          }
        })
      },
      onWebSocketClose: () => {
        if (client.active) {
          setStatus('reconnecting')
        } else {
          setStatus('disconnected')
        }
      },
      onWebSocketError: () => {
        setStatus('error')
      },
      onStompError: () => {
        setStatus('error')
      },
    })

    clientRef.current = client
    setStatus('connecting')
    client.activate()

    const activeSubscriptions = subscriptionsRef.current
    return () => {
      // Clear stompSub references from active registry
      activeSubscriptions.forEach((sub) => {
        sub.stompSub = null
      })
      client.deactivate()
      setStatus('disconnected')
    }
  }, [wsUrl])

  const subscribe = (destination, handler) => {
    const id = `sub-${++subCounterRef.current}`
    const sub = {
      destination,
      handler,
      stompSub: null,
    }

    subscriptionsRef.current.set(id, sub)

    const client = clientRef.current
    if (client && client.connected) {
      sub.stompSub = client.subscribe(
        destination,
        (message) => {
          let payload = message.body
          try {
            payload = JSON.parse(message.body)
          } catch {
            // Keep raw string if non-JSON
          }
          handler(payload, message)
        },
        { id },
      )
    }

    return () => {
      const registered = subscriptionsRef.current.get(id)
      if (registered) {
        if (registered.stompSub) {
          try {
            registered.stompSub.unsubscribe()
          } catch {
            // Ignore unsubscribe failures if already disconnected
          }
        }
        subscriptionsRef.current.delete(id)
      }
    }
  }

  const onConnect = (callback) => {
    connectListenersRef.current.add(callback)
    return () => {
      connectListenersRef.current.delete(callback)
    }
  }

  return (
    <StompContext.Provider
      value={{
        client: clientRef.current,
        status,
        subscribe,
        onConnect,
      }}
    >
      {children}
    </StompContext.Provider>
  )
}
