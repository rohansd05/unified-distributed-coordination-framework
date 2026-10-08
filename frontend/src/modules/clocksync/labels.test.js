import { describe, expect, it } from 'vitest'
import {
  actionStatusText,
  deliveryStatusText,
  eventTypeLabel,
  formatDriftRate,
  formatMillis,
  formatOffset,
  formatSpread,
  moduleStatusText,
  nodeStatusText,
  sentenceCase,
  violationTypeLabel,
} from './labels'

describe('clocksync labels', () => {
  it('formats node status into sentence case', () => {
    expect(nodeStatusText('UP')).toBe('Up')
    expect(nodeStatusText('CRASHED')).toBe('Crashed')
    expect(nodeStatusText('DOWN')).toBe('Crashed')
  })

  it('formats module and action status', () => {
    expect(moduleStatusText('IDLE')).toBe('Idle')
    expect(moduleStatusText('BUSY')).toBe('Busy')
    expect(actionStatusText('ACCEPTED')).toBe('Accepted')
    expect(actionStatusText('FINISHED')).toBe('Finished')
  })

  it('formats delivery status', () => {
    expect(deliveryStatusText('SENT')).toBe('Sent')
    expect(deliveryStatusText('UNKNOWN')).toBe('Unknown')
  })

  it('formats event types and violation types', () => {
    expect(eventTypeLabel('SEND')).toBe('Send')
    expect(eventTypeLabel('RECV')).toBe('Receive')
    expect(eventTypeLabel('LOCAL')).toBe('Local')
    expect(violationTypeLabel('RECEIVE_NOT_AFTER_SEND')).toBe('Receive not after send')
  })

  it('formats offset and drift rate with signs', () => {
    expect(formatOffset(242)).toBe('+242 ms')
    expect(formatOffset(-165)).toBe('-165 ms')
    expect(formatOffset(0)).toBe('0 ms')
    expect(formatOffset(null)).toBeNull()

    expect(formatDriftRate(1.5)).toBe('+1.5 ms/s')
    expect(formatDriftRate(-1.0)).toBe('-1.0 ms/s')
    expect(formatDriftRate(0)).toBe('0.0 ms/s')
    expect(formatDriftRate(null)).toBeNull()
  })

  it('formats spread and millis', () => {
    expect(formatSpread(407)).toBe('407 ms')
    expect(formatSpread(null)).toBeNull()
    expect(formatMillis(4.4586)).toBe('4.5 ms')
    expect(formatMillis(null)).toBeNull()
  })

  it('sentenceCase falls back gracefully without ALL-CAPS', () => {
    expect(sentenceCase('SOME_UNKNOWN_VALUE')).toBe('Some unknown value')
    expect(sentenceCase(null)).toBe('')
  })
})
