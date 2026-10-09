import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ApiError } from '@/services/api'
import exportText from '@/test/fixtures/mapreduce/events-export.txt?raw'
import { EventLogDownload } from './EventLogDownload'

let createObjectURL
let revokeObjectURL
let clicked

beforeEach(() => {
  clicked = []
  createObjectURL = vi.fn(() => 'blob:events')
  revokeObjectURL = vi.fn()
  URL.createObjectURL = createObjectURL
  URL.revokeObjectURL = revokeObjectURL
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click() {
    clicked.push({ href: this.href, download: this.download })
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  delete URL.createObjectURL
  delete URL.revokeObjectURL
})

describe('EventLogDownload', () => {
  it('downloads MapReduce\'s own events as a .txt file and frees the link afterwards', async () => {
    const api = { exportEvents: vi.fn().mockResolvedValue(exportText) }
    render(<EventLogDownload api={api} />)

    fireEvent.click(screen.getByRole('button', { name: 'Download event log' }))

    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Saved 25 events as mapreduce-events.txt.'))
    expect(api.exportEvents).toHaveBeenCalledWith({ module: 'mapreduce' })
    expect(clicked).toEqual([{ href: 'blob:events', download: 'mapreduce-events.txt' }])
    expect(createObjectURL.mock.calls[0][0].type).toBe('text/plain;charset=utf-8')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:events')
  })

  it('exports every module when asked', async () => {
    const api = { exportEvents: vi.fn().mockResolvedValue('') }
    render(<EventLogDownload api={api} />)

    fireEvent.click(screen.getByLabelText('Every module, not only MapReduce'))
    fireEvent.click(screen.getByRole('button', { name: 'Download event log' }))

    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Saved 0 events as cluster-events.txt.'))
    expect(api.exportEvents).toHaveBeenCalledWith({})
  })

  it('shows the backend\'s message when the export fails', async () => {
    const api = { exportEvents: vi.fn().mockRejectedValue(new ApiError({ status: 400, title: 'Invalid request parameters', detail: 'Invalid request parameter: limit' })) }
    render(<EventLogDownload api={api} />)

    fireEvent.click(screen.getByRole('button', { name: 'Download event log' }))

    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Invalid request parameter: limit'))
    expect(clicked).toEqual([])
  })
})
