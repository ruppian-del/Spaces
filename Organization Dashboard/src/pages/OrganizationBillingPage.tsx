import { ArrowLeft, Check, CreditCard, Minus, Plus, ShieldCheck } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { httpsCallable } from 'firebase/functions'
import { CAPACITY_ADD_ONS, FOUNDATION_PLAN, MODULE_ADD_ONS, subscriptionCapacity, subscriptionMonthlyTotal, type SubscriptionSelection } from '../billing/pricingCatalog'
import { Brand } from '../components/Brand'
import { functions } from '../data/firebase'

const emptySelection: SubscriptionSelection = { peoplePacks: 0, spacePacks: 0, storage100Packs: 0, storage500Packs: 0, moduleIds: [] }
type SubscriptionData = { selection: SubscriptionSelection | null; status?: string; cancelAtPeriodEnd?: boolean; currentPeriodEnd?: string | null }

function Quantity({ label, price, value, onChange }: { label: string; price: number; value: number; onChange: (next: number) => void }) {
  return <div className="billing-quantity"><span><strong>{label}</strong><small>${price}/month each</small></span><div className="count-controls"><button aria-label={`Remove ${label}`} disabled={!value} onClick={() => onChange(value - 1)}><Minus /></button><output>{value}</output><button aria-label={`Add ${label}`} onClick={() => onChange(value + 1)}><Plus /></button></div></div>
}

export function OrganizationBillingPage() {
  const { organizationId } = useParams()
  const [selection, setSelection] = useState(emptySelection)
  const [status, setStatus] = useState<string | null>(null)
  const [periodEnd, setPeriodEnd] = useState<string | null>(null)
  const [cancelsAtPeriodEnd, setCancelsAtPeriodEnd] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const capacity = useMemo(() => subscriptionCapacity(selection), [selection])
  const total = useMemo(() => subscriptionMonthlyTotal(selection), [selection])
  const subscribed = Boolean(status)

  useEffect(() => {
    if (!functions || !organizationId) return
    const getPlan = httpsCallable<{ organizationId: string }, SubscriptionData>(functions, 'getOrganizationSubscription')
    void getPlan({ organizationId }).then(({ data }) => { if (data.selection) setSelection(data.selection); setStatus(data.status ?? null); setPeriodEnd(data.currentPeriodEnd ?? null); setCancelsAtPeriodEnd(data.cancelAtPeriodEnd === true) }).catch(() => setError('Your subscription could not be loaded.'))
  }, [organizationId])
  useEffect(() => {
    if (!status) return
    const heading = document.querySelector('.billing-heading p')
    const date = periodEnd ? new Intl.DateTimeFormat(undefined, { dateStyle: 'long' }).format(new Date(periodEnd)) : 'the end of the current period'
    if (heading) heading.textContent = 'Update the plan below; changes apply to this subscription.'
    const stats = document.querySelector('.plan-summary dl')
    const existing = stats?.querySelector('[data-billing-period]')
    const row = existing ?? document.createElement('div')
    row.setAttribute('data-billing-period', '')
    row.replaceChildren()
    const label = document.createElement('dt'); label.textContent = cancelsAtPeriodEnd ? 'Plan ends' : 'Renews'
    const value = document.createElement('dd'); value.textContent = date
    row.append(label, value)
    if (!existing) stats?.append(row)
  }, [status, periodEnd, cancelsAtPeriodEnd])

  function setQuantity(key: keyof Pick<SubscriptionSelection, 'peoplePacks' | 'spacePacks' | 'storage100Packs' | 'storage500Packs'>, value: number) { setSelection((current) => ({ ...current, [key]: value })) }
  function toggleModule(id: string) { setSelection((current) => ({ ...current, moduleIds: current.moduleIds.includes(id) ? current.moduleIds.filter((item) => item !== id) : [...current.moduleIds, id] })) }
  async function save() {
    if (!functions || !organizationId || busy) return
    setBusy(true); setError(null)
    try {
      const checkout = httpsCallable<{ organizationId: string; selection: SubscriptionSelection; returnOrigin: string }, { url: string | null }>(functions, 'createOrganizationCheckout')
      const result = await checkout({ organizationId, selection, returnOrigin: window.location.origin })
      if (result.data.url) window.location.assign(result.data.url)
      else setStatus('active')
    } catch { setError('Your subscription could not be updated. Please try again.') } finally { setBusy(false) }
  }
  async function cancel() {
    if (!functions || !organizationId || busy || !confirm('Cancel this plan at the end of its current paid period?')) return
    setBusy(true); setError(null)
    try { await httpsCallable<{ organizationId: string }, { cancelled: boolean }>(functions, 'cancelOrganizationSubscription')({ organizationId }); setCancelsAtPeriodEnd(true) } catch { setError('Your cancellation could not be saved. Please try again.') } finally { setBusy(false) }
  }

  return <div className="app-page"><header className="topbar"><Link to={`/organizations/${organizationId}`} className="brand-link"><Brand compact /></Link></header><main className="billing-page"><Link className="back-link" to={`/organizations/${organizationId}`}><ArrowLeft size={16} /> Dashboard</Link><div className="billing-heading"><span className="eyebrow">Subscription planning</span><h1>{subscribed ? 'Manage your organization plan' : 'Build your organization plan'}</h1><p>{subscribed ? `Current subscription: ${status}. Update the plan below; changes apply to this subscription.` : 'Start with Foundation, then add only the capacity and modules your organization needs.'}</p></div><div className="billing-layout"><div className="billing-options"><section className="setup-card foundation-plan"><div className="plan-title"><div><span className="panel-icon violet"><CreditCard /></span><span><strong>Foundation</strong><small>Required base plan</small></span></div><strong>$39<small>/month</small></strong></div><ul className="plan-includes"><li><Check /> 250 unique members</li><li><Check /> 10 active Spaces</li><li><Check /> 10 GB pooled storage</li><li><Check /> Pings, Events, Polls, Activity, Members, and Settings</li></ul></section><section className="setup-card"><h2>Capacity add-ons</h2><div className="billing-quantities"><Quantity label={CAPACITY_ADD_ONS.people250.name} price={15} value={selection.peoplePacks} onChange={(value) => setQuantity('peoplePacks', value)} /><Quantity label={CAPACITY_ADD_ONS.spaces10.name} price={15} value={selection.spacePacks} onChange={(value) => setQuantity('spacePacks', value)} /><Quantity label={CAPACITY_ADD_ONS.storage100.name} price={15} value={selection.storage100Packs} onChange={(value) => setQuantity('storage100Packs', value)} /><Quantity label={CAPACITY_ADD_ONS.storage500.name} price={50} value={selection.storage500Packs} onChange={(value) => setQuantity('storage500Packs', value)} /></div></section><section className="setup-card"><h2>Module add-ons</h2><div className="module-picker">{MODULE_ADD_ONS.map((module) => <label key={module.id} className={selection.moduleIds.includes(module.id) ? 'module-option selected' : 'module-option'}><input type="checkbox" checked={selection.moduleIds.includes(module.id)} onChange={() => toggleModule(module.id)} /><span><strong>{module.name}</strong><small>{module.description}</small></span><b>${module.monthlyPrice}/mo</b></label>)}</div></section></div><aside className="plan-summary"><span className="eyebrow">{subscribed ? 'Updated plan' : 'Plan estimate'}</span><div className="estimate-price"><strong>${total}</strong><span>per month</span></div><dl><div><dt>Unique members</dt><dd>{capacity.people}</dd></div><div><dt>Active Spaces</dt><dd>{capacity.spaces}</dd></div><div><dt>Pooled storage</dt><dd>{capacity.storageGB} GB</dd></div><div><dt>Paid modules</dt><dd>{selection.moduleIds.length || 'None'}</dd></div></dl><button className="primary-button" disabled={busy} onClick={() => void save()}>{busy ? 'Saving…' : subscribed ? 'Save subscription changes' : 'Continue to secure checkout'}</button>{subscribed && <button className="secondary-button billing-cancel" disabled={busy || status === 'cancels at period end'} onClick={() => void cancel()}>{status === 'cancels at period end' ? 'Cancellation scheduled' : 'Cancel plan'}</button>}{error && <div className="form-alert" role="alert">{error}</div>}<p className="checkout-status"><ShieldCheck /> {subscribed ? 'Changes update your existing subscription.' : 'Secure checkout verifies your primary-administrator access.'}</p></aside></div></main></div>
}
