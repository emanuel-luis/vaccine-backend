import { useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { useConfig } from '../config'

export type ExportDataset = 'doses-daily' | 'coverage-municipality' | 'health-units'

/** CSV download with the current query params. Render it for gestor only (the API enforces it too). */
export function ExportButton({ dataset, query }: { dataset: ExportDataset; query: Record<string, string | undefined> }) {
  const { apiBaseUrl } = useConfig()
  const token = useAuth().user?.access_token
  const [error, setError] = useState(false)

  async function download() {
    setError(false)
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(query)) if (v) qs.set(k, v)
    try {
      const res = await fetch(`${apiBaseUrl}/api/v1/exports/${dataset}?${qs}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const url = URL.createObjectURL(await res.blob())
      const a = document.createElement('a')
      a.href = url
      const named = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')
      a.download = named?.[1] ?? `${dataset}.csv`
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      setError(true)
    }
  }

  return (
    <>
      <button type="button" className="btn" onClick={() => void download()}>
        Exportar CSV
      </button>
      {error && <span role="alert"> Não foi possível exportar.</span>}
    </>
  )
}
