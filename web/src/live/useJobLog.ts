import { useCallback, useEffect, useRef, useState } from 'react'
import { useFetchJobLog } from '../api/queries'
import { useLive } from './LiveProvider'

export interface LogLine {
  seq: number
  loggedAt: string
  level: string
  message: string
}

const PAGE = 1000

/**
 * Log of one job: loaded with GET .../log?afterSeq= on mount, on `ready` (reconnect) and when the
 * stream skips a seq; SSE lines are appended when they are the next seq and ignored when already seen.
 * Mount it with key={jobId}: the state belongs to one job.
 */
export function useJobLog(jobId: string) {
  const fetchLog = useFetchJobLog()
  const { subscribe } = useLive()
  const [lines, setLines] = useState<LogLine[]>([])
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)
  const last = useRef(0)
  const busy = useRef(false)
  const again = useRef(false)
  const fetchRef = useRef(fetchLog)
  useEffect(() => {
    fetchRef.current = fetchLog
  }, [fetchLog])

  /** Appends lines newer than the last seen seq; returns how many were new. */
  const append = useCallback((batch: LogLine[]) => {
    const fresh = batch.filter((l) => l.seq > last.current).sort((a, b) => a.seq - b.seq)
    if (fresh.length === 0) return 0
    last.current = fresh[fresh.length - 1]!.seq
    setLines((prev) => [...prev, ...fresh])
    return fresh.length
  }, [])

  const sync = useCallback(async () => {
    if (busy.current) {
      again.current = true
      return
    }
    busy.current = true
    try {
      do {
        again.current = false
        for (;;) {
          const page = await fetchRef.current(jobId, last.current)
          if (append(page) === 0 || page.length < PAGE) break
        }
      } while (again.current)
      setFailed(false)
    } catch {
      setFailed(true)
    } finally {
      busy.current = false
      setLoaded(true)
    }
  }, [jobId, append])

  useEffect(() => {
    queueMicrotask(() => void sync())
    return subscribe((e) => {
      if (e.type === 'ready') return void sync()
      if (e.jobId !== jobId || e.seq === undefined || e.seq <= last.current) return
      if (e.seq === last.current + 1)
        append([{ seq: e.seq, loggedAt: new Date().toISOString(), level: e.level ?? 'info', message: e.message ?? '' }])
      else void sync() // a gap: the missing lines come from the API
    })
  }, [jobId, subscribe, sync, append])

  return { lines, loaded, failed, retry: sync }
}
