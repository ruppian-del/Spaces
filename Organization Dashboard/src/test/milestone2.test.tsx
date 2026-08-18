import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Organization } from '../types'
import { FOUNDATION_ENTITLEMENTS, validateOrganizationIdentity } from '../data/organizationValidation'
import {
  buildFoundingOrganizationData, buildPrimaryAdministratorData, canManageOrganizationSettings, deduplicateInFlight,
} from '../data/organizationMutations'
import { OrganizationOnboardingPage } from '../pages/OrganizationOnboardingPage'

const { createOrganization, createInvitation } = vi.hoisted(() => ({ createOrganization: vi.fn(), createInvitation: vi.fn() }))

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ user: { uid: 'creator-1', displayName: 'Ian Rupp', email: 'ian@example.com' } }),
}))

vi.mock('../data/organizationMutations', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../data/organizationMutations')>()
  return {
    ...actual,
    newOrganizationRequestId: () => 'request-1',
    createFoundationOrganizationOnce: (...args: unknown[]) => createOrganization(...args),
    createAdministratorInvitation: (...args: unknown[]) => createInvitation(...args),
  }
})

const validIdentity = { name: 'ArcInteractive', description: 'Community organization', contactEmail: 'hello@example.com', website: 'https://example.com', logoDataUrl: '' }

describe('Milestone 2 validation and compatible data', () => {
  it('validates required identity and safe optional URLs', () => {
    expect(validateOrganizationIdentity({ ...validIdentity, name: '' }).name).toBe('Organization name is required.')
    expect(validateOrganizationIdentity({ ...validIdentity, contactEmail: 'bad', website: 'http://example.com' })).toMatchObject({ contactEmail: 'Enter a valid contact email.', website: 'Website must use HTTPS.' })
    expect(validateOrganizationIdentity(validIdentity)).toEqual({})
  })

  it('builds the exact Foundation organization and primary-admin schemas', () => {
    const timestamp = { server: true }
    const organization = buildFoundingOrganizationData(validIdentity, 'creator-1', 'request-1', timestamp)
    expect(organization).toMatchObject({
      name: 'ArcInteractive', memberIds: ['creator-1'], status: 'active', creationRequestId: 'request-1',
      entitlements: {
        peopleCapacity: 250, activeSpaceCapacity: 10, mediaStorageCapacityBytes: 10 * 1024 ** 3,
        enabledModuleIds: ['activity', 'events', 'general', 'members', 'polls', 'settings'],
      },
      usage: { peopleCount: 1, activeSpaceCount: 0, mediaStorageBytes: 0 },
    })
    expect(organization.entitlements.enabledModuleIds).toHaveLength(FOUNDATION_ENTITLEMENTS.enabledModuleIds.length)
    expect(buildPrimaryAdministratorData({ uid: 'creator-1', displayName: 'Ian Rupp', email: 'ian@example.com' }, timestamp)).toMatchObject({ userId: 'creator-1', role: 'primary_admin', status: 'active' })
  })

  it('deduplicates simultaneous submissions and allows a later safe retry', async () => {
    const operations = new Map<string, Promise<string>>(); let calls = 0
    const operation = () => new Promise<string>((resolve) => { calls += 1; queueMicrotask(() => resolve('created')) })
    const first = deduplicateInFlight(operations, 'request-1', operation)
    const duplicate = deduplicateInFlight(operations, 'request-1', operation)
    expect(first).toBe(duplicate); expect(await first).toBe('created'); expect(calls).toBe(1)
    await deduplicateInFlight(operations, 'request-1', operation)
    expect(calls).toBe(2)
  })

  it('limits management permission to the protected primary administrator', () => {
    expect(canManageOrganizationSettings({ role: 'primary_admin' })).toBe(true)
    expect(canManageOrganizationSettings({ role: 'admin' })).toBe(false)
    expect(canManageOrganizationSettings({ role: 'member' })).toBe(false)
  })
})

describe('Milestone 2 onboarding states', () => {
  beforeEach(() => { createOrganization.mockReset(); createInvitation.mockReset() })

  it('shows validation, all major steps, and a free starting plan by default', () => {
    render(<MemoryRouter><OrganizationOnboardingPage /></MemoryRouter>)
    expect(screen.getByLabelText('Organization name')).toHaveFocus()
    expect(screen.getByText('Identity')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /continue/i }))
    expect(screen.getByText('Organization name is required.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/^Organization name/), { target: { value: 'ArcInteractive' } })
    fireEvent.click(screen.getByRole('button', { name: /continue/i }))
    expect(screen.getByRole('heading', { name: 'Administrators' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /continue/i }))
    expect(screen.getByRole('heading', { name: 'Choose your starting plan' })).toBeInTheDocument()
    expect(screen.getByText('Free organization')).toBeInTheDocument(); expect(screen.getByText('Foundation')).toBeInTheDocument()
    expect(screen.getByText('25 unique members · 1 active Space · 1 GB pooled storage')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /continue/i }))
    expect(screen.getByRole('heading', { name: 'Review organization' })).toBeInTheDocument()
  })

  it('prevents repeat clicks while creating and renders success', async () => {
    let resolveCreation!: (organization: Organization) => void
    createOrganization.mockReturnValue(new Promise((resolve) => { resolveCreation = resolve }))
    render(<MemoryRouter><OrganizationOnboardingPage /></MemoryRouter>)
    fireEvent.change(screen.getByLabelText('Organization name'), { target: { value: 'ArcInteractive' } })
    fireEvent.click(screen.getByRole('button', { name: /continue/i })); fireEvent.click(screen.getByRole('button', { name: /continue/i })); fireEvent.click(screen.getByRole('button', { name: /continue/i }))
    const submit = screen.getByRole('button', { name: 'Create free organization' })
    fireEvent.click(submit); fireEvent.click(submit)
    expect(createOrganization).toHaveBeenCalledTimes(1)
    resolveCreation({ id: 'org-new', name: 'ArcInteractive', description: null, contactEmail: null, website: null, logoDataUrl: null, status: 'active', entitlements: { peopleCapacity: 250, activeSpaceCapacity: 10, enabledModuleIds: [...FOUNDATION_ENTITLEMENTS.enabledModuleIds], mediaStorageCapacityBytes: 10 * 1024 ** 3 }, usage: { peopleCount: 1, activeSpaceCount: 0, mediaStorageBytes: 0 } })
    await waitFor(() => expect(screen.getByRole('heading', { name: 'ArcInteractive is ready' })).toBeInTheDocument())
  })

  it('renders a retry-safe network failure', async () => {
    createOrganization.mockRejectedValue({ code: 'firestore/unavailable' })
    render(<MemoryRouter><OrganizationOnboardingPage /></MemoryRouter>)
    fireEvent.change(screen.getByLabelText('Organization name'), { target: { value: 'ArcInteractive' } })
    fireEvent.click(screen.getByRole('button', { name: /continue/i })); fireEvent.click(screen.getByRole('button', { name: /continue/i })); fireEvent.click(screen.getByRole('button', { name: /continue/i })); fireEvent.click(screen.getByRole('button', { name: 'Create free organization' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('safe to retry')
  })

  it('renders permission denied without losing the review state', async () => {
    createOrganization.mockRejectedValue({ code: 'firestore/permission-denied' })
    render(<MemoryRouter><OrganizationOnboardingPage /></MemoryRouter>)
    fireEvent.change(screen.getByLabelText('Organization name'), { target: { value: 'ArcInteractive' } })
    fireEvent.click(screen.getByRole('button', { name: /continue/i })); fireEvent.click(screen.getByRole('button', { name: /continue/i })); fireEvent.click(screen.getByRole('button', { name: /continue/i })); fireEvent.click(screen.getByRole('button', { name: 'Create free organization' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('do not have permission')
    expect(screen.getByRole('heading', { name: 'Review organization' })).toBeInTheDocument()
  })

})
