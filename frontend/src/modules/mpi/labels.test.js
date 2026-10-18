import { describe, expect, it } from 'vitest'
import {
  defaultRootText,
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

  it('says in plain words which rule chose the root; an unknown rule shows as it came, never a crash', () => {
    expect(rootSourceLabel('REQUESTED')).toBe('the node you chose')
    expect(rootSourceLabel('LEADER')).toBe('the leader elected by Experiment 4')
    expect(rootSourceLabel('LOWEST_LIVE_ID')).toBe('LOWEST_LIVE_ID')   // no longer served since 9A.3
    expect(rootSourceLabel(null)).toBe('')
  })

  it('names the default root: the leader, none yet, or every node down', () => {
    const up = [{ nodeStatus: 'UP' }, { nodeStatus: 'CRASHED' }]
    const down = [{ nodeStatus: 'CRASHED' }, { nodeStatus: 'CRASHED' }]
    expect(defaultRootText({ defaultRootNodeId: 5, defaultRootSource: 'LEADER' }, up))
      .toBe('Default: node 5 (the leader elected by Experiment 4)')
    expect(defaultRootText({ defaultRootNodeId: null, defaultRootSource: null }, up))
      .toBe('Default: the leader elected by Experiment 4 (none yet; Experiment 4 elects one first)')
    expect(defaultRootText({ defaultRootNodeId: null, defaultRootSource: null }, down)).toBe('Default: none, every node is down')
    expect(defaultRootText(null, [])).toBe('Default: none, every node is down')
  })

  it('names directions and refusal reasons, and falls back to sentence case for unknown values (a root rule: as it came)', () => {
    expect(directionLabel('TO_RANK')).toBe('root to rank')
    expect(directionLabel('FROM_RANK')).toBe('rank to root')
    expect(refusalLabel('COUNT_BELOW_PARTICIPANTS')).toBe('Count below participants')
    expect(operationLabel('ALL_REDUCE')).toBe('All reduce')
    expect(rootSourceLabel('SOME_RULE')).toBe('SOME_RULE')
  })

  it('shortens a checksum for a cell and keeps null as null', () => {
    expect(shortChecksum('5fe35a36404c487bd16f7d889412ae6f')).toBe('5fe35a36404c...')
    expect(shortChecksum('abc')).toBe('abc')
    expect(shortChecksum(null)).toBeNull()
  })
})
