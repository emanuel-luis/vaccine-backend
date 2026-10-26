import { Link } from 'react-router'
import { ApiError } from '../api/client'
import { useCampaignProgressList, useCampaigns } from '../api/queries'
import { useSession } from '../auth/session'
import { ProgressBar } from '../components/ProgressBar'
import { StatePanel } from '../components/StatePanel'
import { sortCampaigns, statusOf } from '../lib/campaign'
import { fmtDate, fmtDoses, isoDay } from '../lib/format'

export const STATUS_LABEL = { active: 'Em andamento', scheduled: 'Agendada', ended: 'Encerrada' }

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 503)
    return 'Os dados ainda não estão disponíveis. Tente novamente em instantes.'
  return 'Não foi possível carregar as campanhas.'
}

export function Campanhas() {
  const { roles } = useSession()
  const list = useCampaigns()
  const today = isoDay(new Date())
  const campaigns = sortCampaigns(list.data ?? [], today)
  const progress = useCampaignProgressList(campaigns)

  return (
    <>
      <h1>Campanhas</h1>
      {roles.includes('gestor') && (
        <p>
          <Link className="btn btn-link" to="/campanhas/nova">
            Nova campanha
          </Link>
        </p>
      )}
      {list.isPending && <StatePanel state="loading" />}
      {list.isError && <StatePanel state="error" message={errorMessage(list.error)} onRetry={() => void list.refetch()} />}
      {list.data && campaigns.length === 0 && (
        <StatePanel state="empty" title="Nenhuma campanha" message="Não há campanhas na sua região." />
      )}
      {campaigns.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <caption>Campanhas, as em andamento primeiro.</caption>
            <thead>
              <tr>
                <th scope="col">Campanha</th>
                <th scope="col">Situação</th>
                <th scope="col">Região</th>
                <th scope="col">Período</th>
                <th scope="col">Progresso</th>
                <th scope="col" className="num">
                  Meta
                </th>
              </tr>
            </thead>
            <tbody>
              {campaigns.map((c, i) => {
                const p = progress[i]?.data?.progress
                return (
                  <tr key={c.campaignId}>
                    <th scope="row">
                      <Link to={`/campanhas/${c.campaignId}`}>{c.name}</Link>
                    </th>
                    <td>{STATUS_LABEL[statusOf(c, today)]}</td>
                    <td>{c.regionScope}</td>
                    <td>
                      {fmtDate(c.startsOn)} a {fmtDate(c.endsOn)}
                    </td>
                    <td>
                      {p ? <ProgressBar pct={p.suppressed ? null : p.pct} label={`Progresso de ${c.name}`} /> : '…'}
                    </td>
                    <td className="num">{fmtDoses(c.targetDoses)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
