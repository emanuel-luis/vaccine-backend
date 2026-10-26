import { type ReactNode, useId, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client'
import { useCampaignForEdit, useRegions, useSaveCampaign, useVaccines } from '../api/queries'
import type { Campaign } from '../api/types'
import { useSession } from '../auth/session'
import { StatePanel } from '../components/StatePanel'
import {
  API_CODES,
  type CampaignValues,
  type FieldErrors,
  fromCampaign,
  STALE_MESSAGE,
  toRequest,
  validateCampaign,
} from '../lib/campaign'

const EMPTY: CampaignValues = {
  name: '',
  vaccineCode: '',
  doseLabel: '',
  regionScope: '',
  startsOn: '',
  endsOn: '',
  targetDoses: '',
}

function Field({ label, error, children }: { label: string; error?: string; children: (p: object) => ReactNode }) {
  const id = useId()
  const errId = `${id}-err`
  return (
    <div className="form-field">
      <label htmlFor={id}>{label}</label>
      {children({ id, 'aria-invalid': error ? true : undefined, 'aria-describedby': error ? errId : undefined })}
      {error && (
        <p id={errId} className="field-error">
          {error}
        </p>
      )}
    </div>
  )
}

function CampaignForm({ campaign }: { campaign?: Campaign }) {
  const navigate = useNavigate()
  const { scope } = useSession()
  const vaccines = useVaccines().data ?? []
  const regions = useRegions(
    scope?.level === 'UF' ? scope.state : undefined,
    scope?.level === 'BR' || scope?.level === 'UF',
  ).data
  const save = useSaveCampaign()
  // The version is frozen when the form opens: that is the one If-Match must carry.
  const [loaded] = useState(campaign)
  const [values, setValues] = useState<CampaignValues>(() => (campaign ? fromCampaign(campaign) : EMPTY))
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string>()

  const set = (k: keyof CampaignValues, v: string) => setValues((s) => ({ ...s, [k]: v }))

  const regionOptions: { value: string; label: string }[] = []
  if (scope?.level === 'BR') regionOptions.push({ value: 'BR', label: 'Brasil' })
  if (scope?.level === 'UF') regionOptions.push({ value: `UF:${scope.state}`, label: `UF ${scope.state}` })
  if (scope?.level === 'MUN')
    regionOptions.push({ value: `MUN:${scope.municipalityCode}`, label: `Município ${scope.municipalityCode}` })
  for (const r of regions ?? [])
    regionOptions.push({ value: r.level === 'state' ? `UF:${r.abbr ?? r.code}` : `MUN:${r.code}`, label: r.name })
  const unique = [...new Map(regionOptions.map((o) => [o.value, o])).values()]
  if (values.regionScope && !unique.some((o) => o.value === values.regionScope))
    unique.push({ value: values.regionScope, label: values.regionScope })

  const vaccineOptions = [...new Map(vaccines.map((v) => [v.vaccineCode, v.vaccineName])).entries()]
  const doseOptions = vaccines.filter((v) => v.vaccineCode === values.vaccineCode).map((v) => v.doseLabel)

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setFormError(undefined)
    const found = validateCampaign(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    save.mutate(
      { body: toRequest(values), id: loaded?.campaignId, version: loaded?.version },
      {
        onSuccess: (c) => void navigate(`/campanhas/${c.campaignId}`),
        onError: (err) => {
          if (err instanceof ApiError && err.status === 412) return setFormError(STALE_MESSAGE)
          const known = err instanceof ApiError && err.code ? API_CODES[err.code] : undefined
          if (known) return setErrors({ [known.field]: known.message })
          setFormError('Não foi possível salvar a campanha.')
        },
      },
    )
  }

  return (
    <>
      <h1>{loaded ? 'Editar campanha' : 'Nova campanha'}</h1>
      <form className="campaign-form" aria-label="Campanha" noValidate onSubmit={submit}>
        <Field label="Nome" error={errors.name}>
          {(p) => <input {...p} type="text" maxLength={120} value={values.name} onChange={(e) => set('name', e.target.value)} />}
        </Field>
        <Field label="Vacina" error={errors.vaccineCode}>
          {(p) => (
            <select {...p} value={values.vaccineCode} onChange={(e) => setValues((s) => ({ ...s, vaccineCode: e.target.value, doseLabel: '' }))}>
              <option value="">Escolha</option>
              {vaccineOptions.map(([code, name]) => (
                <option key={code} value={code}>
                  {name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Dose" error={errors.doseLabel}>
          {(p) => (
            <select {...p} value={values.doseLabel} disabled={!values.vaccineCode} onChange={(e) => set('doseLabel', e.target.value)}>
              <option value="">Todas as doses</option>
              {doseOptions.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Região" error={errors.regionScope}>
          {(p) => (
            <select {...p} value={values.regionScope} onChange={(e) => set('regionScope', e.target.value)}>
              <option value="">Escolha</option>
              {unique.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Início" error={errors.startsOn}>
          {(p) => <input {...p} type="date" value={values.startsOn} onChange={(e) => set('startsOn', e.target.value)} />}
        </Field>
        <Field label="Fim" error={errors.endsOn}>
          {(p) => <input {...p} type="date" value={values.endsOn} onChange={(e) => set('endsOn', e.target.value)} />}
        </Field>
        <Field label="Meta de doses" error={errors.targetDoses}>
          {(p) => (
            <input {...p} type="text" inputMode="numeric" value={values.targetDoses} onChange={(e) => set('targetDoses', e.target.value)} />
          )}
        </Field>
        {formError && (
          <p role="alert" className="field-error">
            {formError}
          </p>
        )}
        <p className="actions">
          <button type="submit" className="btn btn-primary" disabled={save.isPending}>
            Salvar
          </button>
          <button type="button" className="btn" onClick={() => void navigate(-1)}>
            Cancelar
          </button>
        </p>
      </form>
    </>
  )
}

export function CampanhaNova() {
  return <CampaignForm />
}

export function CampanhaEditar() {
  const { id = '' } = useParams()
  const q = useCampaignForEdit(id)
  if (q.isPending) return <StatePanel state="loading" />
  if (q.isError) {
    const notFound = q.error instanceof ApiError && q.error.status === 404
    return (
      <StatePanel
        state="error"
        title={notFound ? 'Campanha não encontrada' : undefined}
        message={notFound ? 'Ela não existe ou está fora da sua região.' : 'Não foi possível carregar a campanha.'}
        onRetry={notFound ? undefined : () => void q.refetch()}
      />
    )
  }
  return <CampaignForm campaign={q.data} />
}
