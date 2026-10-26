/** Same-origin relative path only ("/x", never "//host" or "/\host"); anything else falls back to /painel. */
export function safeReturnPath(state: unknown): string {
  return typeof state === 'string' && /^\/(?![/\\])/.test(state) ? state : '/painel'
}

/** After the OIDC callback: restore the deep link saved as signin state and tell BrowserRouter. */
export function restoreReturnPath(user: { state?: unknown } | void) {
  window.history.replaceState({}, document.title, safeReturnPath(user?.state))
  window.dispatchEvent(new PopStateEvent('popstate'))
}
