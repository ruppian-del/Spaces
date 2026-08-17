import type { OrganizationIdentityInput } from '../types'

export const FOUNDATION_ENTITLEMENTS = {
  peopleCapacity: 250,
  activeSpaceCapacity: 10,
  enabledModuleIds: ['general', 'events', 'polls', 'activity', 'members', 'settings'],
  mediaStorageCapacityBytes: 10 * 1024 ** 3,
} as const

export type IdentityErrors = Partial<Record<keyof OrganizationIdentityInput, string>>

function optionalHttpsUrl(value: string): string | null {
  if (!value.trim()) return null
  try {
    const url = new URL(value.trim())
    if (url.protocol !== 'https:') return 'Website must use HTTPS.'
    return null
  } catch {
    return 'Enter a valid website URL.'
  }
}

export function validateOrganizationIdentity(input: OrganizationIdentityInput): IdentityErrors {
  const errors: IdentityErrors = {}
  const name = input.name.trim()
  if (!name) errors.name = 'Organization name is required.'
  else if (name.length < 2) errors.name = 'Organization name must be at least 2 characters.'
  else if (name.length > 100) errors.name = 'Organization name must be 100 characters or fewer.'
  if (input.description.trim().length > 500) errors.description = 'Description must be 500 characters or fewer.'
  if (input.contactEmail.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(input.contactEmail.trim())) errors.contactEmail = 'Enter a valid contact email.'
  const websiteError = optionalHttpsUrl(input.website)
  if (websiteError) errors.website = websiteError
  if (input.logoDataUrl && (!input.logoDataUrl.startsWith('data:image/') || input.logoDataUrl.length > 350_000)) errors.logoDataUrl = 'Choose an image under 250 KB.'
  return errors
}

export function cleanOrganizationIdentity(input: OrganizationIdentityInput): OrganizationIdentityInput {
  return {
    name: input.name.trim(),
    description: input.description.trim(),
    contactEmail: input.contactEmail.trim().toLowerCase(),
    website: input.website.trim(),
    logoDataUrl: input.logoDataUrl,
  }
}
