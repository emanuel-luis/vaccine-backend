import { type ReactNode, useEffect, useId, useRef } from 'react'

interface Props {
  title: string
  children: ReactNode
  confirmLabel: string
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/** Modal confirm: focus starts on Cancel, Escape cancels, Tab stays inside, focus returns to the opener. */
export function ConfirmDialog({ title, children, confirmLabel, busy, onConfirm, onCancel }: Props) {
  const titleId = useId()
  const box = useRef<HTMLDivElement>(null)
  const cancel = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    cancel.current?.focus()
    return () => opener?.focus()
  }, [])

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') onCancel()
    if (e.key !== 'Tab') return
    const items = box.current?.querySelectorAll<HTMLElement>('button:not([disabled])')
    if (!items?.length) return
    const first = items[0]!
    const last = items[items.length - 1]!
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault()
      first.focus()
    }
  }

  return (
    <div className="dialog-backdrop">
      <div ref={box} className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} onKeyDown={onKeyDown}>
        <h2 id={titleId}>{title}</h2>
        {children}
        <div className="dialog-actions">
          <button ref={cancel} type="button" className="btn" onClick={onCancel}>
            Cancelar
          </button>
          <button type="button" className="btn btn-danger" disabled={busy} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
