import { useRef, useState } from 'react'
import { ArrowLeft, ArrowRight, Check, ShieldCheck, UserRound } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Brand } from '../components/Brand'
import { createFoundationOrganizationOnce, newOrganizationRequestId } from '../data/organizationMutations'
import { FOUNDATION_ENTITLEMENTS, validateOrganizationIdentity, type IdentityErrors } from '../data/organizationValidation'
import type { Organization, OrganizationIdentityInput } from '../types'

const EMPTY_IDENTITY: OrganizationIdentityInput = { name: '', description: '', contactEmail: '', website: '', logoDataUrl: '' }
const STEPS = ['Identity', 'Administrators', 'Foundation', 'Review'] as const

function errorMessage(error: unknown): string {
  const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : ''
  if (code.includes('permission-denied')) return 'You do not have permission to create this organization.'
  if (code.includes('unavailable') || code.includes('network')) return 'The network is unavailable. Your setup request is safe to retry.'
  return error instanceof Error ? error.message : 'Organization setup could not be completed.'
}

function readLogo(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => typeof reader.result === 'string' ? resolve(reader.result) : reject(new Error('The image could not be read.'))
    reader.onerror = () => reject(new Error('The image could not be read.'))
    reader.readAsDataURL(file)
  })
}

export function OrganizationOnboardingPage() {
  const { user } = useAuth(); const navigate = useNavigate()
  const [step, setStep] = useState(0); const [identity, setIdentity] = useState(EMPTY_IDENTITY)
  const [errors, setErrors] = useState<IdentityErrors>({}); const [logoError, setLogoError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false); const [submitError, setSubmitError] = useState<string | null>(null)
  const [created, setCreated] = useState<Organization | null>(null)
  const requestId = useRef(newOrganizationRequestId())
  function update(field: Exclude<keyof OrganizationIdentityInput, 'logoDataUrl'>, value: string) { setIdentity((current) => ({ ...current, [field]: value })); setErrors((current) => ({ ...current, [field]: undefined })) }
  async function chooseLogo(file: File | undefined) {
    if (!file) return
    setLogoError(null)
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > 250 * 1024) { setLogoError('Choose a PNG, JPEG, or WebP image under 250 KB.'); return }
    try { const logoDataUrl = await readLogo(file); setIdentity((current) => ({ ...current, logoDataUrl })) }
    catch (error) { setLogoError(error instanceof Error ? error.message : 'The image could not be read.') }
  }
  function next() { if (step === 0) { const nextErrors = validateOrganizationIdentity(identity); setErrors(nextErrors); if (Object.keys(nextErrors).length) return }; setStep((current) => Math.min(current + 1, STEPS.length - 1)); window.scrollTo({ top: 0, behavior: 'smooth' }) }
  async function submit() { if (!user || submitting) return; const nextErrors = validateOrganizationIdentity(identity); if (Object.keys(nextErrors).length) { setErrors(nextErrors); setStep(0); return }; setSubmitting(true); setSubmitError(null); try { setCreated(await createFoundationOrganizationOnce(identity, user, requestId.current)) } catch (error) { setSubmitError(errorMessage(error)) } finally { setSubmitting(false) } }
  if (created) return <div className="app-page"><header className="topbar"><Brand compact /></header><main className="onboarding success-layout"><div className="success-mark"><Check /></div><span className="eyebrow">Setup complete</span><h1>{created.name} is ready</h1><p>Your Foundation organization and protected primary-administrator record were created successfully. Invite administrators one at a time from Settings.</p><div className="button-row"><button className="primary-button" onClick={() => navigate(`/organizations/${created.id}`, { replace: true })}>Open dashboard <ArrowRight size={18} /></button><button className="secondary-button" onClick={() => navigate(`/organizations/${created.id}/settings`, { replace: true })}>Organization settings</button></div></main></div>
  return <div className="app-page"><header className="topbar"><Link to="/organizations" className="brand-link"><Brand compact /></Link></header><main className="onboarding"><Link className="back-link" to="/organizations"><ArrowLeft size={16} /> Organizations</Link><div className="onboarding-intro"><span className="eyebrow">Foundation setup</span><h1>Create an organization</h1><p>Set up the organization control plane. Content remains in the native theSpaces. apps.</p></div><ol className="stepper" aria-label="Organization setup progress">{STEPS.map((label, index) => <li className={index === step ? 'current' : index < step ? 'complete' : ''} key={label} aria-current={index === step ? 'step' : undefined}><span>{index < step ? <Check size={15} /> : index + 1}</span>{label}</li>)}</ol><section className="setup-card" aria-labelledby="setup-step-title">{step === 0 && <><h2 id="setup-step-title">Organization identity</h2><p>Use a local image import for the organization logo. No external image link is required.</p><div className="form-grid"><label className="field full">Organization name <input autoFocus value={identity.name} onChange={(event) => update('name', event.target.value)} aria-invalid={Boolean(errors.name)} maxLength={100} />{errors.name && <small className="field-error">{errors.name}</small>}</label><label className="field full">Description <textarea value={identity.description} onChange={(event) => update('description', event.target.value)} maxLength={500} rows={4} /></label><label className="field">Contact email <input type="email" value={identity.contactEmail} onChange={(event) => update('contactEmail', event.target.value)} placeholder="hello@example.org" />{errors.contactEmail && <small className="field-error">{errors.contactEmail}</small>}</label><label className="field">Website <input type="url" value={identity.website} onChange={(event) => update('website', event.target.value)} placeholder="https://example.org" />{errors.website && <small className="field-error">{errors.website}</small>}</label><label className="field full">Organization logo <input type="file" accept="image/png,image/jpeg,image/webp" onChange={(event) => void chooseLogo(event.target.files?.[0])} />{logoError && <small className="field-error">{logoError}</small>}<small>PNG, JPEG, or WebP up to 250 KB.</small>{identity.logoDataUrl && <img className="logo-preview" src={identity.logoDataUrl} alt="Selected organization logo" />}</label></div></>}{step === 1 && <><h2 id="setup-step-title">Administrators</h2><p>You will be the protected primary administrator. After setup, create administrator invitations individually from Settings so each invitation is intentional.</p><div className="primary-admin-preview"><span className="person-avatar"><UserRound /></span><span><strong>{user?.displayName || user?.email || 'Signed-in account'}</strong><small>{user?.email || 'Primary administrator'}</small></span><span className="role-pill">Primary admin</span></div></>}{step === 2 && <><h2 id="setup-step-title">Foundation includes</h2><p>Foundation is applied exactly as approved. Paid add-ons and arbitrary entitlement editing are not available here.</p><div className="foundation-grid"><div><strong>250</strong><span>Unique members</span></div><div><strong>10</strong><span>Active Spaces</span></div><div><strong>10 GB</strong><span>Pooled storage</span></div></div><ul className="module-summary">{FOUNDATION_ENTITLEMENTS.enabledModuleIds.map((id) => <li key={id}><Check size={16} />{{ general: 'Space Pings', events: 'Events', polls: 'Polls', activity: 'Activity', members: 'Members', settings: 'Settings' }[id]}</li>)}</ul><div className="boundary-inline"><ShieldCheck /><span>Communication, Agenda, Content, and capacity additions cannot be self-activated during setup.</span></div></>}{step === 3 && <><h2 id="setup-step-title">Review organization</h2><p>Confirm these details before creating the Firestore organization and primary-administrator records.</p><dl className="review-list"><div><dt>Organization</dt><dd>{identity.name.trim()}</dd></div><div><dt>Logo</dt><dd>{identity.logoDataUrl ? 'Imported image' : 'No logo yet'}</dd></div><div><dt>Primary administrator</dt><dd>{user?.displayName || user?.email}</dd></div><div><dt>Foundation capacity</dt><dd>250 members · 10 active Spaces · 10 GB</dd></div><div><dt>Modules</dt><dd>Space Pings, Events, Polls, Activity, Members, Settings</dd></div></dl>{submitError && <div className="form-alert" role="alert">{submitError}</div>}</>}</section><div className="onboarding-actions">{step > 0 && <button className="secondary-button" disabled={submitting} onClick={() => setStep((current) => current - 1)}><ArrowLeft size={17} /> Back</button>}<span />{step < STEPS.length - 1 ? <button className="primary-button" onClick={next}>Continue <ArrowRight size={17} /></button> : <button className="primary-button" disabled={submitting} onClick={() => void submit()}>{submitting ? 'Creating organization…' : 'Create Foundation organization'}</button>}</div></main></div>
}
