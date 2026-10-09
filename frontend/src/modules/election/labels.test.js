import { describe, expect, it } from 'vitest'
import {
  DETECTION_NOTE, algorithmLabel, formatMillis, messageLabel, moduleStatusLabel, outcomeLabel, secondsFrom, triggerLabel,
} from './labels'

const ALL_CAPS_WORD = /\b[A-Z]{2,}\b/

describe('election labels', () => {
  it('names algorithms, triggers, outcomes, statuses and message types in sentence case', () => {
    expect(algorithmLabel('BULLY')).toBe('Bully')
    expect(algorithmLabel('RING')).toBe('Ring')
    expect(triggerLabel('MANUAL')).toBe('Started by hand')
    expect(triggerLabel('LEADER_FAILURE')).toBe('After the leader failed')
    expect(triggerLabel('RECOVERY')).toBe('After a recovery')
    expect(outcomeLabel('TIMED_OUT')).toBe('Timed out')
    expect(moduleStatusLabel('BUSY')).toBe('Election in progress')
    expect(messageLabel('OK')).toBe('Answer')
    expect(messageLabel('RING_ELECTION')).toBe('Ring token')
  })

  it('falls back to sentence case for an unknown value, and to "" for none, never all capitals', () => {
    expect(outcomeLabel('SPLIT_VOTE')).toBe('Split vote')
    expect(triggerLabel(null)).toBe('')
    for (const value of ['BULLY', 'RING', 'MANUAL', 'LEADER_FAILURE', 'RECOVERY', 'IN_PROGRESS', 'ELECTED',
      'TIMED_OUT', 'IDLE', 'RUNNING', 'BUSY', 'ERROR', 'ELECTION', 'OK', 'COORDINATOR', 'RING_ELECTION', 'RING_COORDINATOR']) {
      for (const label of [algorithmLabel, triggerLabel, outcomeLabel, moduleStatusLabel, messageLabel]) {
        expect(label(value)).not.toMatch(ALL_CAPS_WORD)
      }
    }
  })

  it('labels the leader-failure duration as measured from detection', () => {
    expect(DETECTION_NOTE).toBe('Measured from detection, not from the crash')
  })

  it('formats a measured duration and gives null (shown as "—") for one that was not measured', () => {
    expect(formatMillis(931.3051)).toBe('931')
    expect(formatMillis(2.3657)).toBe('2.4')
    expect(formatMillis(0)).toBe('0.0')
    expect(formatMillis(null)).toBeNull()
    expect(formatMillis(undefined)).toBeNull()
    expect(formatMillis(Number.NaN)).toBeNull()
  })

  it('turns the configured round timeout into seconds, or null when unknown', () => {
    expect(secondsFrom(10000)).toBe(10)
    expect(secondsFrom(2500)).toBe(2.5)
    expect(secondsFrom(null)).toBeNull()
  })
})
