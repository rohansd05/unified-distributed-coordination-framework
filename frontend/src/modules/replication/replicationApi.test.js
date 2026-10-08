import { describe, expect, it } from 'vitest'
import { ApiError, createApi } from '@/services/api'
import overviewBefore from '@/test/fixtures/replication/overview-before.json'
import replicasConverged from '@/test/fixtures/replication/replicas-converged.json'
import readStale from '@/test/fixtures/replication/read-stale.json'
import writeSync from '@/test/fixtures/replication/write-sync.json'
import crashBackup from '@/test/fixtures/replication/crash-backup.json'
import recoverBackup from '@/test/fixtures/replication/recover-backup.json'
import antiEntropy from '@/test/fixtures/replication/anti-entropy.json'
import staleInjection from '@/test/fixtures/replication/stale-injection.json'
import nodeDownFixture from '@/test/fixtures/replication/error-409-node-down.json'
import validationFixture from '@/test/fixtures/replication/error-400-validation.json'
import { createReplicationApi } from './replicationApi'

/** An api whose axios adapter records each request and answers with `respond(config)`; no network. */
function apiAnswering(respond) {
  const calls = []
  const base = createApi({
    baseURL: 'http://localhost:8080',
    adapter: async (config) => {
      calls.push(config)
      const { status = 200, data } = respond(config)
      if (status >= 400) {
        const error = new Error(`Request failed with status code ${status}`)
        error.response = { status, data, statusText: '', headers: {}, config }
        throw error
      }
      return { status, data, statusText: 'OK', headers: {}, config }
    },
  })
  return { calls, api: createReplicationApi(base) }
}

describe('replicationApi', () => {
  it('reads the overview, the replicas and one replica', async () => {
    const { calls, api } = apiAnswering((config) => ({
      data: config.url.endsWith('/replicas') ? replicasConverged
        : config.url.includes('/values') ? readStale : overviewBefore,
    }))

    expect(await api.getOverview()).toEqual(overviewBefore)
    expect(await api.getReplicas()).toEqual(replicasConverged)
    expect(await api.read(2, 'a;b~c')).toEqual(readStale)
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['get', '/api/modules/replication'],
      ['get', '/api/modules/replication/replicas'],
      ['get', '/api/modules/replication/nodes/2/values'],
    ])
    expect(calls[2].params).toEqual({ key: 'a;b~c' })
  })

  it('POSTs a write with exactly key, value and model', async () => {
    const { calls, api } = apiAnswering(() => ({ data: writeSync }))
    expect(await api.write({ key: 'balance', value: '1000', model: 'SYNCHRONOUS', extra: 1 })).toEqual(writeSync)
    expect(calls[0].url).toBe('/api/modules/replication/writes')
    expect(JSON.parse(calls[0].data)).toEqual({ key: 'balance', value: '1000', model: 'SYNCHRONOUS' })
  })

  it('crashes and recovers through the module endpoints', async () => {
    const { calls, api } = apiAnswering((config) => ({ data: config.url.endsWith('/crash') ? crashBackup : recoverBackup }))
    expect(await api.crashBackup(3)).toEqual(crashBackup)
    expect(await api.recover(3)).toEqual(recoverBackup)
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['post', '/api/modules/replication/nodes/3/crash'],
      ['post', '/api/modules/replication/nodes/3/recover'],
    ])
  })

  it('POSTs anti-entropy and a stale injection', async () => {
    const { calls, api } = apiAnswering((config) => ({ data: config.url.endsWith('/anti-entropy') ? antiEntropy : staleInjection }))
    expect(await api.antiEntropy(3)).toEqual(antiEntropy)
    expect(await api.injectStale({ backupNodeId: 2, key: 'balance', staleValue: '500' })).toEqual(staleInjection)
    expect(JSON.parse(calls[0].data)).toEqual({ targetNodeId: 3 })
    expect(JSON.parse(calls[1].data)).toEqual({ backupNodeId: 2, key: 'balance', staleValue: '500' })
  })

  it('turns a real 409 and 400 ProblemDetail into an ApiError with title, detail, nodeId and errors', async () => {
    const down = apiAnswering(() => ({ status: 409, data: nodeDownFixture })).api
    const invalid = apiAnswering(() => ({ status: 400, data: validationFixture })).api

    const conflict = await down.antiEntropy(4).catch((e) => e)
    const badRequest = await invalid.write({ key: '', value: 'v', model: 'SYNCHRONOUS' }).catch((e) => e)

    expect(conflict).toBeInstanceOf(ApiError)
    expect(conflict).toMatchObject({ status: 409, title: 'Node down', nodeId: 4 })
    expect(badRequest).toMatchObject({ status: 400, title: 'Invalid request parameters' })
    expect(Object.keys(badRequest.errors)).toContain('key')
  })
})
