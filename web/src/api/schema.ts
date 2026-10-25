/**
 * TEMPORARY hand-written types, replaced by `npm run gen:api` when docs/api/openapi.json lands
 * (PR2). Covers only GET /api/v1/me and GET /api/v1/dashboard/summary.
 */

type Json<T> = { headers: { [name: string]: unknown }; content: { 'application/json': T } }

export interface paths {
  '/api/v1/me': {
    get: {
      parameters: { query?: never; header?: never; path?: never; cookie?: never }
      requestBody?: never
      responses: { 200: Json<components['schemas']['ApiItemMe']>; default: Json<components['schemas']['Problem']> }
    }
    put?: never
    post?: never
    delete?: never
    options?: never
    head?: never
    patch?: never
    trace?: never
  }
  '/api/v1/dashboard/summary': {
    get: {
      parameters: {
        query?: { from?: string; to?: string; region?: string; vaccine?: string; dose?: string; ageBand?: string }
        header?: never
        path?: never
        cookie?: never
      }
      requestBody?: never
      responses: { 200: Json<components['schemas']['ApiItemSummary']>; default: Json<components['schemas']['Problem']> }
    }
    put?: never
    post?: never
    delete?: never
    options?: never
    head?: never
    patch?: never
    trace?: never
  }
}

export interface components {
  schemas: {
    Meta: {
      source?: string
      synthetic?: boolean
      count?: number
      layer?: 'batch' | 'merged'
      batchGeneration?: number
      suppressedCells?: number
    }
    Problem: { type?: string; title?: string; status?: number; detail?: string; code?: string }
    Me: {
      username: string
      name?: string
      roles: string[]
      scope: { level: 'BR' | 'UF' | 'MUN'; state?: string | null; municipalityCode?: string | null; label?: string }
    }
    ApiItemMe: { data: components['schemas']['Me']; meta: components['schemas']['Meta'] }
    Summary: {
      dosesInPeriod: number
      dosesToday: number
      today: string
      batchGeneration: number
      liveDosesPending: number
    }
    ApiItemSummary: { data: components['schemas']['Summary']; meta: components['schemas']['Meta'] }
  }
}
