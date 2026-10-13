import { describe, expect, it } from 'vitest'
import overview from '@/test/fixtures/rmi/overview-after-start.json'
import {
  clockTime,
  exactNanos,
  failureKindDescription,
  failureKindLabel,
  formatBytes,
  formatNanos,
  formatRatio,
  methodLabel,
  moduleStatusLabel,
  nodeStateLabel,
  nodeStatusText,
  outcomeLabel,
  pathLabel,
  stageLabel,
} from './labels'

const MICRO = String.fromCharCode(0xb5)

describe('formatNanos', () => {
  it('shows real fixture values at the precision the data has, never rounded to zero', () => {
    expect(formatNanos(7237200)).toBe('7.237 ms')
    expect(formatNanos(560600)).toBe(`560.6 ${MICRO}s`)
    expect(formatNanos(4500)).toBe(`4.5 ${MICRO}s`)
    expect(formatNanos(999)).toBe('999 ns')
    expect(formatNanos(0)).toBe('0 ns')
  })

  it('keeps a negative overhead negative, with a plain hyphen-minus', () => {
    expect(formatNanos(-4563400)).toBe('-4.563 ms')
    expect(formatNanos(-4563400).charCodeAt(0)).toBe(45)
  })

  it('is null for null, undefined and non-finite values (shown as n/a by the page, never 0)', () => {
    expect([null, undefined, Number.NaN, Infinity, '7'].map(formatNanos)).toEqual([null, null, null, null, null])
    expect(exactNanos(51309900)).toBe('51309900 ns')
    expect(exactNanos(null)).toBeNull()
  })
})

describe('other formats', () => {
  it('shows a ratio with three decimals, a tiny one with three significant digits, and null as null', () => {
    expect(formatRatio(0.9493729025100541)).toBe('0.949')
    expect(formatRatio(71.79069767441861)).toBe('71.791')
    expect(formatRatio(0.0000123)).toBe('0.0000123')
    expect(formatRatio(null)).toBeNull()
  })

  it('counts bytes in words', () => {
    expect(formatBytes(96)).toBe('96 bytes')
    expect(formatBytes(1)).toBe('1 byte')
    expect(formatBytes(null)).toBeNull()
  })

  it('shows the UTC wall time of a call', () => {
    expect(clockTime('2026-10-11T19:28:41.533190300Z')).toBe('19:28:41')
    expect(clockTime(null)).toBeNull()
  })
})

describe('labels', () => {
  it('names methods by their Java names and enums in sentence case, never in capitals', () => {
    expect(['PING', 'NODE_INFO', 'ECHO', 'COMPUTE_HASH'].map(methodLabel)).toEqual(['ping', 'nodeInfo', 'echo', 'computeHash'])
    expect(nodeStateLabel('NOT_STARTED')).toBe('Not started')
    expect(outcomeLabel('REMOTE_FAILURE')).toBe('Remote failure')
    expect(pathLabel('LOCAL')).toBe('Local (direct call)')
    expect(stageLabel('LOOKUP')).toBe('registry lookup')
    expect(failureKindLabel('CONNECTION_REFUSED')).toBe('Connection refused')
    expect(nodeStatusText('CRASHED')).toBe('Down')
    expect(moduleStatusLabel('BUSY')).toBe('Busy')
  })

  it('takes every failure kind\'s description from the overview, for all 11 kinds', () => {
    expect(overview.failureKinds).toHaveLength(11)
    for (const { kind, description } of overview.failureKinds) {
      expect(failureKindDescription(overview, kind)).toBe(description)
    }
    expect(failureKindDescription(overview, 'NOPE')).toBeNull()
    expect(failureKindDescription(null, 'TIMED_OUT')).toBeNull()
  })
})
