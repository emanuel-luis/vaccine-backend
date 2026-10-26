interface Props {
  state: 'loading' | 'empty' | 'error'
  title?: string
  message?: string
  onRetry?: () => void
}

const DEFAULTS = {
  loading: { title: 'Carregando', message: 'Aguarde um instante…' },
  empty: { title: 'Nada por aqui', message: 'Não há dados para os filtros escolhidos.' },
  error: { title: 'Algo deu errado', message: 'Não foi possível carregar os dados.' },
}

export function StatePanel({ state, title, message, onRetry }: Props) {
  const d = DEFAULTS[state]
  return (
    <div className={`state-panel state-${state}`} role={state === 'error' ? 'alert' : 'status'}>
      <p className="state-title">{title ?? d.title}</p>
      <p>{message ?? d.message}</p>
      {state === 'error' && onRetry && (
        <button type="button" className="btn" onClick={onRetry}>
          Tentar novamente
        </button>
      )}
    </div>
  )
}
