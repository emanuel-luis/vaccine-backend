import { createContext, useContext } from 'react'

export interface AppConfig {
  apiBaseUrl: string
  oidc: { authority: string; clientId: string }
}

export const ConfigContext = createContext<AppConfig | null>(null)

export function useConfig(): AppConfig {
  const config = useContext(ConfigContext)
  if (!config) throw new Error('ConfigContext missing')
  return config
}

/** Runtime config served as /config.json (nginx templates it from env in the container). */
export async function loadConfig(): Promise<AppConfig> {
  const res = await fetch('/config.json', { cache: 'no-store' })
  if (!res.ok) throw new Error(`config.json: HTTP ${res.status}`)
  return (await res.json()) as AppConfig
}
