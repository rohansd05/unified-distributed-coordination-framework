import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/rmi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/rmi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/rmi/overview-node-crashed.json'
import overviewTimeout from '@/test/fixtures/rmi/overview-timeout-backend.json'
import startFixture from '@/test/fixtures/rmi/start.json'
import registriesAllUp from '@/test/fixtures/rmi/registries-all-up.json'
import registriesBefore from '@/test/fixtures/rmi/registries-before-start.json'
import registriesCrashed from '@/test/fixtures/rmi/registries-node-3-crashed.json'
import registryNode2 from '@/test/fixtures/rmi/registry-node-2.json'
import callPing from '@/test/fixtures/rmi/call-ping.json'
import callNodeInfo from '@/test/fixtures/rmi/call-node-info.json'
import callEcho from '@/test/fixtures/rmi/call-echo.json'
import callHash from '@/test/fixtures/rmi/call-compute-hash.json'
import callSelf from '@/test/fixtures/rmi/call-self.json'
import callTimedOut from '@/test/fixtures/rmi/call-timed-out.json'
import callCrashedTarget from '@/test/fixtures/rmi/call-crashed-target.json'
import callLatest from '@/test/fixtures/rmi/call-latest.json'
import callById from '@/test/fixtures/rmi/call-by-id.json'
import callsHistory from '@/test/fixtures/rmi/calls-history.json'
import comparisonHash from '@/test/fixtures/rmi/comparison-compute-hash.json'
import comparisonPing from '@/test/fixtures/rmi/comparison-ping.json'
import events from '@/test/fixtures/rmi/events.json'
import { chartModel, historyRows, latestCall, mapModel, needsStart, noticeModel } from './rmiModel'

/** Every field the page reads from an RmiCallDto (E1c). */
const CALL_FIELDS = ['id', 'calledAt', 'method', 'fromNode', 'targetNode', 'path', 'argumentSummary', 'resultSummary', 'outcome',
  'failureStage', 'failureKind', 'failureDescription', 'failureClass', 'failureMessage', 'failureCauseClass', 'lookupNanos',
  'roundTripNanos', 'timeToFailureNanos', 'argumentBytes', 'resultBytes', 'payloadSizeBasis', 'stubClassName',
  'senderLamportAtSend', 'replyLamport', 'senderLamportAfterReply', 'reply']
const CALLS = { callPing, callNodeInfo, callEcho, callHash, callSelf, callTimedOut, callCrashedTarget }
const OVERVIEWS = { overviewBefore, overviewAfter, overviewCrashed, overviewTimeout, startFixture }

describe('rmi contract fixtures', () => {
  it.each(Object.entries(OVERVIEWS))('%s has every overview field the page reads', (_, overview) => {
    for (const key of ['status', 'actionInProgress', 'nodes', 'methods', 'limits', 'payloadSizeBasis', 'lookupBasis', 'coHosting',
      'comparisonNote', 'failureKinds', 'latest']) {
      expect(overview).toHaveProperty(key)
    }
    expect(overview.coHosting.simulated).toBe(true)
    expect(Object.keys(overview.limits).sort()).toEqual(['callTimeoutMillis', 'connectTimeoutMillis', 'historySize',
      'maxEchoLength', 'maxFailureMessageLength', 'maxHashRounds'])
    for (const node of overview.nodes) {
      expect(Object.keys(node).sort()).toEqual(['nodeId', 'nodeStatus', 'registryPort', 'startFailure', 'state'])
    }
    expect(mapModel(overview, latestCall(overview.latest)).nodes).toHaveLength(overview.nodes.length)
  })

  it('needs a start only on a fresh backend', () => {
    expect(Object.entries(OVERVIEWS).filter(([, o]) => needsStart(o)).map(([name]) => name)).toEqual(['overviewBefore'])
  })

  it.each(Object.entries(CALLS))('%s has every call field, and null (never 0) where nothing was measured', (_, call) => {
    expect(Object.keys(call).sort()).toEqual([...CALL_FIELDS].sort())
    if (call.outcome === 'SUCCESS') {
      expect(call.timeToFailureNanos).toBeNull()
      expect(call.roundTripNanos).toBeGreaterThan(0)
    } else {
      expect(call.roundTripNanos).toBeNull()
      expect(call.resultBytes).toBeNull()
      expect(call.timeToFailureNanos).toBeGreaterThan(0)
    }
  })

  it('history entries carry exactly one of call and comparison, and the page reads them all', () => {
    for (const entry of [...callsHistory, callLatest, callById]) {
      expect(['CALL', 'COMPARISON']).toContain(entry.kind)
      expect((entry.call === null) !== (entry.comparison === null)).toBe(true)
    }
    expect(historyRows(callsHistory)).toHaveLength(callsHistory.length)
    expect(chartModel(callsHistory).bars.length).toBeGreaterThan(0)
    expect(noticeModel(callsHistory)).toHaveLength(3)
  })

  it('comparisons carry both halves and the figures the page shows', () => {
    for (const comparison of [comparisonHash, comparisonPing]) {
      for (const key of ['localNanos', 'remoteNanos', 'overheadNanos', 'ratio', 'digestsMatch', 'note', 'local', 'remote']) {
        expect(comparison).toHaveProperty(key)
      }
      expect(comparison.local.path).toBe('LOCAL')
      expect(comparison.remote.path).toBe('REMOTE')
      expect(comparison.local.argumentBytes).toBeNull()
    }
    expect(comparisonHash.overheadNanos).toBeLessThan(0)
  })

  it('registries name an unreachable node with names null, never []', () => {
    for (const registry of [...registriesAllUp, ...registriesBefore, ...registriesCrashed, registryNode2]) {
      if (registry.reachable) {
        expect(Array.isArray(registry.names)).toBe(true)
      } else {
        expect(registry.names).toBeNull()
        expect(registry.failureKind).toBeTruthy()
      }
    }
  })

  it('events use only the five E1c types', () => {
    const types = new Set(events.map((event) => event.type))
    expect([...types].sort()).toEqual(['CALL_FAILED', 'CALL_SERVED', 'CALL_SUCCEEDED', 'COMPARISON_FINISHED', 'ENDPOINT_EXPORTED'])
    expect(events.every((event) => event.module === 'rmi' && Number.isInteger(event.lamportTime))).toBe(true)
  })
})
