import { describe, expect, it } from 'vitest'
import {
  directionLabel,
  operationLabel,
  outcomeLabel,
  refusalLabel,
  rmiStateLabel,
  rootSourceLabel,
  shortChecksum,
  transferStatusLabel,
} from './labels'

describe('mpi labels', () => {
  it('names every operation, outcome, transfer status and RMI state without capitals', () => {
    expect(['BROADCAST', 'SCATTER', 'GATHER'].map(operationLabel)).toEqual(['Broadcast', 'Scatter', 'Gather'])
    expect(['COMPLETE', 'PARTIAL', 'FAILED', 'REFUSED'].map(outcomeLabel)).toEqual(['Complete', 'Partial', 'Failed', 'Refused'])
    expect(['LOCAL', 'DELIVERED', 'FAILED', 'NOT_ATTEMPTED'].map(transferStatusLabel))
      .toEqual(['Local (root)', 'Delivered', 'Failed', 'Not attempted'])
    expect(['NOT_STARTED', 'EXPORTED', 'CRASHED'].map(rmiStateLabel)).toEqual(['Not started', 'Exported', 'Crashed'])
  })

  it('says in plain words which rule chose the root', () => {
    expect(rootSourceLabel('REQUESTED')).toBe('the node you chose')
    expect(rootSourceLabel('LEADER')).toBe('the elected leader, read from the cluster')
    expect(rootSourceLabel('LOWEST_LIVE_ID')).toBe('the lowest live node id; no leader is elected')
  })

  it('names directions and refusal reasons, and falls back to sentence case for unknown values', () => {
    expect(directionLabel('TO_RANK')).toBe('root to rank')
    expect(directionLabel('FROM_RANK')).toBe('rank to root')
    expect(refusalLabel('COUNT_BELOW_PARTICIPANTS')).toBe('Count below participants')
    expect(operationLabel('ALL_REDUCE')).toBe('All reduce')
    expect(rootSourceLabel('SOME_RULE')).toBe('some rule')
  })

  it('shortens a checksum for a cell and keeps null as null', () => {
    expect(shortChecksum('5fe35a36404c487bd16f7d889412ae6f')).toBe('5fe35a36404c...')
    expect(shortChecksum('abc')).toBe('abc')
    expect(shortChecksum(null)).toBeNull()
  })
})
