import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { RequireAuth } from '../auth/RequireAuth'
import { RequireRole } from '../auth/RequireRole'
import { StatePanel } from '../components/StatePanel'
import { AppShell } from '../layout/AppShell'
import { AccessDenied, NotFound } from '../pages/Messages'
import { Sobre } from '../pages/Sobre'

// Dashboards (ECharts) and the map (MapLibre) stay out of the initial bundle.
const Painel = lazy(() => import('../pages/Painel').then((m) => ({ default: m.Painel })))
const Cobertura = lazy(() => import('../pages/Cobertura').then((m) => ({ default: m.Cobertura })))
const Alertas = lazy(() => import('../pages/Alertas').then((m) => ({ default: m.Alertas })))
const Campanhas = lazy(() => import('../pages/Campanhas').then((m) => ({ default: m.Campanhas })))
const CampanhaDetalhe = lazy(() => import('../pages/CampanhaDetalhe').then((m) => ({ default: m.CampanhaDetalhe })))
const CampanhaNova = lazy(() => import('../pages/CampanhaForm').then((m) => ({ default: m.CampanhaNova })))
const CampanhaEditar = lazy(() => import('../pages/CampanhaForm').then((m) => ({ default: m.CampanhaEditar })))
const DadosFontes = lazy(() => import('../pages/DadosFontes').then((m) => ({ default: m.DadosFontes })))
const DadosExecucoes = lazy(() => import('../pages/DadosExecucoes').then((m) => ({ default: m.DadosExecucoes })))
const DadosExecucaoDetalhe = lazy(() => import('../pages/DadosExecucaoDetalhe').then((m) => ({ default: m.DadosExecucaoDetalhe })))
const DadosTestes = lazy(() => import('../pages/DadosTestes').then((m) => ({ default: m.DadosTestes })))
const DadosReconciliacao = lazy(() => import('../pages/DadosReconciliacao').then((m) => ({ default: m.DadosReconciliacao })))
const lazyPage = (el: React.ReactNode) => <Suspense fallback={<StatePanel state="loading" />}>{el}</Suspense>

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
          <Route path="alertas" element={lazyPage(<Alertas />)} />
          <Route path="campanhas" element={lazyPage(<Campanhas />)} />
          <Route element={<RequireRole roles={['gestor']} />}>
            <Route path="campanhas/nova" element={lazyPage(<CampanhaNova />)} />
            <Route path="campanhas/:id/editar" element={lazyPage(<CampanhaEditar />)} />
          </Route>
          <Route path="campanhas/:id" element={lazyPage(<CampanhaDetalhe />)} />
          <Route path="dados" element={<RequireRole roles={['data-admin']} />}>
            <Route path="fontes" element={lazyPage(<DadosFontes />)} />
            <Route path="execucoes" element={lazyPage(<DadosExecucoes />)} />
            <Route path="execucoes/:jobId" element={lazyPage(<DadosExecucaoDetalhe />)} />
            <Route path="testes" element={lazyPage(<DadosTestes />)} />
            <Route path="reconciliacao" element={lazyPage(<DadosReconciliacao />)} />
          </Route>
          <Route path="acesso-negado" element={<AccessDenied />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Route>
    </Routes>
  )
}
