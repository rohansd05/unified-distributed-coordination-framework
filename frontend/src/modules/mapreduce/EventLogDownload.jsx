import { useId, useState } from 'react'
import { Download } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { mapReduceApi } from './mapReduceApi'

/**
 * "Download event log": GET /api/events/export (link L5), the same format the MapReduce log
 * jobs read, saved as a .txt file. MapReduce's own events by default; the checkbox exports every
 * module's events. The text is handed to the browser once and not kept.
 *
 * @param {object} props
 * @param {object} [props.api] see createMapReduceApi
 */
export function EventLogDownload({ api = mapReduceApi }) {
  const [everyModule, setEveryModule] = useState(false)
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState(null)
  const [error, setError] = useState(null)
  const checkboxId = useId()

  const download = async () => {
    setPending(true)
    setError(null)
    setMessage(null)
    try {
      const text = await api.exportEvents(everyModule ? {} : { module: 'mapreduce' })
      const content = typeof text === 'string' ? text : ''
      const lines = content === '' ? 0 : content.split('\n').filter((line) => line !== '').length
      const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }))
      const link = document.createElement('a')
      link.href = url
      link.download = everyModule ? 'cluster-events.txt' : 'mapreduce-events.txt'
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      setMessage(`Saved ${lines} ${lines === 1 ? 'event' : 'events'} as ${link.download}.`)
    } catch (err) {
      setError(err?.detail || err?.message || 'The export failed.')
    } finally {
      setPending(false)
    }
  }

  return (
    <div data-testid="event-log-download" className="flex flex-wrap items-center gap-3 rounded-lg border bg-card p-3 text-sm">
      <Button type="button" variant="outline" size="sm" onClick={download} disabled={pending}>
        <Download aria-hidden="true" className="mr-1.5 size-4" />
        {pending ? 'Preparing…' : 'Download event log'}
      </Button>
      <label htmlFor={checkboxId} className="flex items-center gap-2 text-xs">
        <input
          id={checkboxId}
          type="checkbox"
          checked={everyModule}
          onChange={(event) => setEveryModule(event.target.checked)}
          className="accent-primary"
        />
        Every module, not only MapReduce
      </label>
      <p className="w-full text-xs text-muted-foreground">
        One event per line in the format the log jobs read. The same file can be uploaded as a .txt input.
      </p>
      {message && <p role="status" className="w-full text-xs">{message}</p>}
      {error && <p role="alert" className="w-full text-xs text-destructive">{error}</p>}
    </div>
  )
}
