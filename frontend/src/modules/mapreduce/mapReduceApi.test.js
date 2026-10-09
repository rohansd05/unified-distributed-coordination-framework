import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/services/api'
import runAccepted from '@/test/fixtures/mapreduce/run-accepted.json'
import { createMapReduceApi } from './mapReduceApi'

function fakeBase({ get, post } = {}) {
  return {
    client: {
      get: get ?? vi.fn().mockResolvedValue({ data: 'data' }),
      post: post ?? vi.fn().mockResolvedValue({ data: runAccepted }),
    },
    recoverNode: vi.fn().mockResolvedValue({}),
  }
}

describe('createMapReduceApi', () => {
  it('reads the overview, the history and one run from the module paths', async () => {
    const base = fakeBase()
    const api = createMapReduceApi(base)
    await api.getOverview()
    await api.getRuns()
    await api.getRun('d4055014')
    expect(base.client.get.mock.calls.map((call) => call[0])).toEqual([
      '/api/modules/mapreduce',
      '/api/modules/mapreduce/runs',
      '/api/modules/mapreduce/runs/d4055014',
    ])
  })

  it('getLatestRun: a 404 means "no run yet" and becomes null; other errors pass through', async () => {
    const notFound = createMapReduceApi(fakeBase({ get: vi.fn().mockRejectedValue(new ApiError({ status: 404, title: 'Unknown run' })) }))
    await expect(notFound.getLatestRun()).resolves.toBeNull()

    const failing = createMapReduceApi(fakeBase({ get: vi.fn().mockRejectedValue(new ApiError({ status: 0, title: 'Backend unreachable' })) }))
    await expect(failing.getLatestRun()).rejects.toMatchObject({ status: 0 })
  })

  it('startRun sends only jobId and inputType for a plain run', async () => {
    const base = fakeBase()
    await createMapReduceApi(base).startRun({ jobId: 'word-count', inputType: 'SAMPLE', upload: null, crashWorkerId: null })
    expect(base.client.post).toHaveBeenCalledWith('/api/modules/mapreduce/runs', { jobId: 'word-count', inputType: 'SAMPLE' })
  })

  it('startRun sends the upload body and the crash worker exactly as the API expects', async () => {
    const base = fakeBase()
    const result = await createMapReduceApi(base).startRun({
      jobId: 'word-count',
      inputType: 'UPLOAD',
      upload: { fileName: 'a.txt', contentType: 'text/plain', contentBase64: 'aGk=', bytes: 2 },
      crashWorkerId: 3,
    })
    expect(base.client.post).toHaveBeenCalledWith('/api/modules/mapreduce/runs', {
      jobId: 'word-count',
      inputType: 'UPLOAD',
      upload: { fileName: 'a.txt', contentType: 'text/plain', contentBase64: 'aGk=' },
      crashWorkerId: 3,
    })
    expect(result).toEqual(runAccepted)
  })

  it('startRun passes the backend\'s ApiError through', async () => {
    const refused = new ApiError({ status: 409, title: 'Module busy', detail: 'busy' })
    const api = createMapReduceApi(fakeBase({ post: vi.fn().mockRejectedValue(refused) }))
    await expect(api.startRun({ jobId: 'word-count', inputType: 'SAMPLE' })).rejects.toBe(refused)
  })

  it('exportEvents fetches the export as text, filtered to a module or for every module', async () => {
    const get = vi.fn().mockResolvedValue({ data: 'line 1\nline 2\n' })
    const api = createMapReduceApi(fakeBase({ get }))

    await expect(api.exportEvents({ module: 'mapreduce' })).resolves.toBe('line 1\nline 2\n')
    await api.exportEvents({})

    expect(get.mock.calls[0][0]).toBe('/api/events/export')
    expect(get.mock.calls[0][1]).toMatchObject({ params: { module: 'mapreduce' }, responseType: 'text' })
    expect(get.mock.calls[0][1].transformResponse[0]('{"not":"parsed"}')).toBe('{"not":"parsed"}')
    expect(get.mock.calls[1][1].params).toEqual({})
  })

  it('recoverNode uses the shared cluster call', async () => {
    const base = fakeBase()
    await createMapReduceApi(base).recoverNode(3)
    expect(base.recoverNode).toHaveBeenCalledWith(3)
  })
})
