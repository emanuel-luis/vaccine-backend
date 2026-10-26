import { useQueryClient } from '@tanstack/react-query'
import { createParser } from 'eventsource-parser'
import { createContext, type ReactNode, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { useConfig } from '../config'
import { type Scope } from '../auth/claims'

export type LiveState = 'live' | 'reconnecting' | 'paused'

export interface LiveValue {
  status: LiveState
  /** Doses seen on the stream in the last 5 minutes; refreshed at most every 30 s. */
  recentDoses: number
}

const LiveContext = createContext<LiveValue>({ status: 'paused', recentDoses: 0 })

export function useLive(): LiveValue {
  return useContext(LiveContext)
}

export const INVALIDATE_EVERY_MS = 5_000
export const TICKER_EVERY_MS = 30_000
const WINDOW_MS = 5 * 60_000
const BACKOFF_MIN_MS = 1_000
const BACKOFF_MAX_MS = 30_000

export function backoffMs(attempt: number): number {
  return Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS * 2 ** attempt)
}

function regionOf(scope: Scope | null | undefined): string | undefined {
  if (!scope) return undefined
  if (scope.level === 'BR') return 'BR'
  return scope.level === 'UF' ? `UF:${scope.state}` : `MUN:${scope.municipalityCode}`
}

/** Calls `fn` now, then at most once per `ms`: later calls inside the window collapse into one trailing call. */
function throttle(fn: () => void, ms: number) {
  let last = -Infinity
  let timer: ReturnType<typeof setTimeout> | undefined
  const run = () => {
    timer = undefined
    last = Date.now()
    fn()
  }
  return {
    call() {
      if (timer) return
      const wait = last + ms - Date.now()
      if (wait <= 0) run()
      else timer = setTimeout(run, wait)
    },
    cancel() {
      clearTimeout(timer)
      timer = undefined
    },
  }
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const t = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(t)
      resolve()
    })
  })
}

/**
 * Connects to GET /api/v1/stream with fetch + Authorization (no token in the URL) and parses
 * the events with eventsource-parser. `scope` is the session scope; `enabled` is false while signed out.
 */
export function LiveProvider({ children, scope }: { children: ReactNode; scope: Scope | null | undefined }) {
  const { apiBaseUrl } = useConfig()
  const token = useAuth().user?.access_token
  const qc = useQueryClient()
  const [conn, setConn] = useState<'live' | 'reconnecting'>('reconnecting')
  const [recentDoses, setRecentDoses] = useState(0)
  const [visible, setVisible] = useState(() => document.visibilityState !== 'hidden')
  const entries = useRef<{ at: number; total: number }[]>([])
  const region = regionOf(scope)

  useEffect(() => {
    const onChange = () => setVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', onChange)
    return () => document.removeEventListener('visibilitychange', onChange)
  }, [])

  // A new token (silent renewal), scope or visibility restarts the connection at once with a fresh backoff.
  useEffect(() => {
    if (!token || !visible) return
    const ctl = new AbortController()
    const refresh = throttle(() => void qc.invalidateQueries({ queryKey: ['live'] }), INVALIDATE_EVERY_MS)
    const ticker = throttle(() => {
      const cutoff = Date.now() - WINDOW_MS
      entries.current = entries.current.filter((e) => e.at >= cutoff)
      setRecentDoses(entries.current.reduce((s, e) => s + e.total, 0))
    }, TICKER_EVERY_MS)
    let attempt = 0

    const onEvent = (type: string | undefined, data: string) => {
      let body: Record<string, unknown>
      try {
        body = JSON.parse(data) as Record<string, unknown>
      } catch {
        return
      }
      if (type === 'ready') {
        attempt = 0
        setConn('live')
        void qc.invalidateQueries({ queryKey: ['live'] })
      } else if (type === 'doses') {
        if (typeof body.total === 'number') entries.current.push({ at: Date.now(), total: body.total })
        ticker.call()
        refresh.call()
      } else if (type === 'batch') {
        void qc.invalidateQueries({ queryKey: ['live'] })
        void qc.invalidateQueries({ queryKey: ['alerts'] })
      }
    }

    const url = new URL(`${apiBaseUrl}/api/v1/stream`, window.location.origin)
    if (region) url.searchParams.set('region', region)

    const connect = async () => {
      while (!ctl.signal.aborted) {
        setConn('reconnecting')
        try {
          const res = await fetch(url, {
            headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
            signal: ctl.signal,
          })
          if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`)
          const parser = createParser({ onEvent: (e) => onEvent(e.event, e.data) })
          const decoder = new TextDecoder()
          const reader = res.body.getReader()
          for (;;) {
            const { done, value } = await reader.read()
            if (done) break
            parser.feed(decoder.decode(value, { stream: true }))
          }
        } catch {
          // Network error, bad status or abort: handled below.
        }
        if (ctl.signal.aborted) return
        setConn('reconnecting')
        await sleep(backoffMs(attempt++), ctl.signal)
      }
    }
    void connect()

    return () => {
      ctl.abort()
      refresh.cancel()
      ticker.cancel()
    }
  }, [token, visible, region, apiBaseUrl, qc])

  const status: LiveState = token && visible ? conn : 'paused'
  const value = useMemo(() => ({ status, recentDoses }), [status, recentDoses])
  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>
}
