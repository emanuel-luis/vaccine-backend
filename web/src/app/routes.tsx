import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { RequireAuth } from '../auth/RequireAuth'
import { RequireRole } from '../auth/RequireRole'
import { StatePanel } from '../components/StatePanel'
import { AppShell } from '../layout/AppShell'
import { AccessDenied, NotFound } from '../pages/Messages'
import { Placeholder } from '../pages/Placeholder'
import { Sobre } from '../pages/Sobre'

// Dashboards (ECharts) and the map (MapLibre) stay out of the initial bundle.
const Painel = lazy(() => import('../pages/Painel').then((m) => ({ default: m.Painel })))
const Cobertura = lazy(() => import('../pages/Cobertura').then((m) => ({ default: m.Cobertura })))
const lazyPage = (el: React.ReactNode) => <Suspense fallback={<StatePanel state="loading" />}>{el}</Suspense>

const page = (title: string) => <Placeholder title={title} />

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="sobre" element={<Sobre />} />
        <Route element={<RequireAuth />}>
          <Route index element={<Navigate to="/painel" replace />} />
          <Route path="painel" element={lazyPage(<Painel />)} />
          <Route path="cobertura" element={lazyPage(<Cobertura />)} />
          <Route path="cobertura/:uf" element={lazyPage(<Cobertura />)} />
          <Route path="cobertura/:uf/:municipio" element={lazyPage(<Cobertura />)} />
          <Route path="alertas" element={page('Alertas de baixa cobertura')} />
          <Route path="campanhas" element={page('Campanhas')} />
          <Route element={<RequireRole roles={['gestor']} />}>
            <Route path="campanhas/nova" element={page('Nova campanha')} />
            <Route path="campanhas/:id/editar" element={page('Editar campanha')} />
          </Route>
          <Route path="campanhas/:id" element={page('Progresso da campanha')} />
          <Route path="dados" element={<RequireRole roles={['data-admin']} />}>
            <Route path="fontes" element={page('Fontes de dados')} />
            <Route path="execucoes" element={page('Execuções')} />
            <Route path="execucoes/:jobId" element={page('Execuções')} />
            <Route path="testes" element={page('Testes de qualidade (dbt)')} />
            <Route path="reconciliacao" element={page('Reconciliação e ingestão')} />
          </Route>
          <Route path="acesso-negado" element={<AccessDenied />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Route>
    </Routes>
  )
}
