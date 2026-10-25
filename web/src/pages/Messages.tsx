import { Link } from 'react-router'

export function AccessDenied() {
  return (
    <>
      <h1>Acesso negado</h1>
      <p>Seu perfil não tem permissão para ver esta tela.</p>
      <Link to="/painel">Voltar ao painel</Link>
    </>
  )
}

export function NotFound() {
  return (
    <>
      <h1>Página não encontrada</h1>
      <p>O endereço não existe ou foi movido.</p>
      <Link to="/painel">Voltar ao painel</Link>
    </>
  )
}
