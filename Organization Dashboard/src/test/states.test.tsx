import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StateView } from '../components/StateView'
import { DashboardContent } from '../pages/DashboardPage'
import { dashboardFixture } from './fixtures'

describe('dashboard states', () => {
  it.each([
    [{ kind: 'loading' } as const, 'Loading your organization'],
    [{ kind: 'empty' } as const, 'No organizations yet'],
    [{ kind: 'permission-denied' } as const, 'You don’t have access'],
    [{ kind: 'error', message: 'Connection unavailable.' } as const, 'We couldn’t load the dashboard'],
  ])('renders the %s state', (state, heading) => {
    render(<StateView state={state} />)
    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
  })

  it('renders the read-only organization overview and privacy boundary', () => {
    render(<DashboardContent data={dashboardFixture} />)
    expect(screen.getByRole('heading', { name: 'Northstar Community' })).toBeInTheDocument()
    expect(screen.getByText('Unique members')).toBeInTheDocument()
    expect(screen.getByText('Space Pings')).toBeInTheDocument()
    expect(screen.getByText('Summer Gathering 2025')).toBeInTheDocument()
    expect(screen.getByText('Private by design')).toBeInTheDocument()
    expect(screen.getByText('of 250').previousSibling).toHaveTextContent('3')
  })

  it('renders Foundation allowances when dashboard route data contains raw null entitlements', () => {
    render(<DashboardContent data={{
      ...dashboardFixture,
      organization: {
        ...dashboardFixture.organization,
        entitlements: { peopleCapacity: null, activeSpaceCapacity: null, enabledModuleIds: [], mediaStorageCapacityBytes: null },
        usage: { peopleCount: 2, activeSpaceCount: 1, mediaStorageBytes: 0 },
      },
    }} />)
    expect(screen.getByText('of 250')).toBeInTheDocument()
    expect(screen.getByText('of 10')).toBeInTheDocument()
    expect(screen.getByText('of 10 GB')).toBeInTheDocument()
    expect(screen.getByText('6 entitlements available')).toBeInTheDocument()
  })

  it('renders override capacities and override-enabled modules', () => {
    render(<DashboardContent data={{
      ...dashboardFixture,
      organization: {
        ...dashboardFixture.organization,
        entitlements: {
          peopleCapacity: 3,
          activeSpaceCapacity: 1,
          enabledModuleIds: ['general', 'announcements', 'photos', 'events', 'members'],
          mediaStorageCapacityBytes: 1000 * 1024 ** 2,
        },
      },
    }} />)
    expect(screen.getByText('of 3')).toBeInTheDocument()
    expect(screen.getByText('of 1')).toBeInTheDocument()
    expect(screen.getByText('of 1.0 GB')).toBeInTheDocument()
    expect(screen.getByText('5 entitlements available')).toBeInTheDocument()
    expect(screen.getByText('Announcements')).toBeInTheDocument()
    expect(screen.getByText('Media')).toBeInTheDocument()
  })
})
