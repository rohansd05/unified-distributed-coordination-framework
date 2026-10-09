import { describe, expect, it } from 'vitest'
import {
  isLeader,
  isNodeCrashed,
  isNodeUp,
  nodeActionLabel,
  nodeStatusLabel,
} from './clusterStatus'

describe('clusterStatus helper', () => {
  it('correctly identifies crashed nodes for CRASHED and DOWN', () => {
    expect(isNodeCrashed({ status: 'CRASHED' })).toBe(true)
    expect(isNodeCrashed({ status: 'crashed' })).toBe(true)
    expect(isNodeCrashed({ status: 'DOWN' })).toBe(true)
    expect(isNodeCrashed({ status: 'down' })).toBe(true)
    expect(isNodeCrashed({ status: 'UP' })).toBe(false)
    expect(isNodeCrashed(null)).toBe(false)
    expect(isNodeCrashed(undefined)).toBe(false)
    expect(isNodeCrashed({})).toBe(false)
  })

  it('correctly identifies up nodes', () => {
    expect(isNodeUp({ status: 'UP' })).toBe(true)
    expect(isNodeUp({ status: 'up' })).toBe(true)
    expect(isNodeUp({ status: 'CRASHED' })).toBe(false)
    expect(isNodeUp({ status: 'DOWN' })).toBe(false)
    expect(isNodeUp(null)).toBe(false)
    expect(isNodeUp(undefined)).toBe(false)
  })

  it('provides matching status labels', () => {
    expect(nodeStatusLabel({ status: 'CRASHED' })).toBe('Crashed')
    expect(nodeStatusLabel({ status: 'DOWN' })).toBe('Crashed')
    expect(nodeStatusLabel({ status: 'UP' })).toBe('Up')
  })

  it('provides matching action labels', () => {
    expect(nodeActionLabel({ id: 2, status: 'CRASHED' })).toBe('Recover node 2')
    expect(nodeActionLabel({ id: 2, status: 'DOWN' })).toBe('Recover node 2')
    expect(nodeActionLabel({ id: 1, status: 'UP' })).toBe('Crash node 1')
    expect(nodeActionLabel(null)).toBe('')
  })

  it('identifies the leader by role, ignoring case', () => {
    expect(isLeader({ roles: ['LEADER'] })).toBe(true)
    expect(isLeader({ roles: ['leader'] })).toBe(true)
    expect(isLeader({ roles: ['PRIMARY', 'LEADER'] })).toBe(true)
    expect(isLeader({ roles: [] })).toBe(false)
    expect(isLeader({ roles: ['PRIMARY'] })).toBe(false)
    expect(isLeader({})).toBe(false)
    expect(isLeader(null)).toBe(false)
    expect(isLeader(undefined)).toBe(false)
  })
})
