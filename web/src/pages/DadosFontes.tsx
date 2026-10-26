import { useState } from 'react'
import { useSources } from '../api/queries'
import { StatePanel } from '../components/StatePanel'
import { StatusBadge } from '../components/StatusBadge'
import { adminErrorMessage } from '../lib/admin'
import { fmtCount, fmtDateTime } from '../lib/formatTime'
import { fmtDate } from '../lib/format'

export function DadosFontes() {
  const q = useSources()
  const [copied, setCopied] = useState<string | null>(null)

  const copy = async (id: string, sha: string) => {
    try {
      await navigator.clipboard.writeText(sha)
      setCopied(id)
    } catch {
      setCopied(null)
    }
  }

  const sources = q.data?.sources ?? []
  return (
    <>
      <h1>Fontes de dados</h1>
      {q.isPending && <StatePanel state="loading" />}
      {q.isError && <StatePanel state="error" message={adminErrorMessage(q.error, 'as fontes de dados')} onRetry={() => void q.refetch()} />}
      {q.data && sources.length === 0 && (
        <StatePanel state="empty" title="Nenhuma carga ainda" message="Rode Recarregar dados de referência em Execuções para registrar as fontes." />
      )}
      {sources.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <caption>Fontes da última carga de referência, concluída em {fmtDateTime(q.data?.loadedAt)}.</caption>
            <thead>
              <tr>
                <th scope="col">Fonte</th>
                <th scope="col">Publicador</th>
                <th scope="col">Arquivo</th>
                <th scope="col">Tabela</th>
                <th scope="col" className="num">Linhas</th>
                <th scope="col">sha256</th>
                <th scope="col">Licença</th>
                <th scope="col">Obtido em</th>
              </tr>
            </thead>
            <tbody>
              {sources.map((s) => (
                <tr key={s.sourceId}>
                  <th scope="row">
                    <a href={s.sourceUrl} target="_blank" rel="noreferrer">
                      {s.sourceId}
                    </a>
                  </th>
                  <td>{s.publisher}</td>
                  <td>{s.path}</td>
                  <td>{s.tableName ?? '—'}</td>
                  <td className="num">{fmtCount(s.rows)}</td>
                  <td>
                    <code title={s.sha256}>{s.sha256.slice(0, 12)}</code>{' '}
                    <button type="button" className="btn" aria-label={`Copiar sha256 de ${s.sourceId}`} onClick={() => void copy(s.sourceId, s.sha256)}>
                      {copied === s.sourceId ? 'Copiado' : 'Copiar'}
                    </button>
                  </td>
                  <td title={s.licenseNote}>
                    <StatusBadge tone={s.licenseStatus === 'confirmed' ? 'ok' : 'warn'}>
                      {s.licenseStatus === 'confirmed' ? 'Confirmada' : 'A confirmar'}
                    </StatusBadge>
                  </td>
                  <td>{fmtDate(s.retrievedAt.slice(0, 10))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
