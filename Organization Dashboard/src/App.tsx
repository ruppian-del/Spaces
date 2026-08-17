import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { StateView } from './components/StateView'
import { SignInPage } from './pages/SignInPage'
import { OrganizationSelectPage } from './pages/OrganizationSelectPage'
import { DashboardPage } from './pages/DashboardPage'
import { OrganizationOnboardingPage } from './pages/OrganizationOnboardingPage'
import { OrganizationSettingsPage } from './pages/OrganizationSettingsPage'
import { OrganizationBillingPage } from './pages/OrganizationBillingPage'

function Protected({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <main className="centered-state"><StateView state={{ kind: 'loading' }} /></main>
  return user ? children : <Navigate to="/sign-in" replace />
}

export default function App() {
  return <AuthProvider><BrowserRouter><Routes>
    <Route path="/sign-in" element={<SignInPage />} />
    <Route path="/organizations" element={<Protected><OrganizationSelectPage /></Protected>} />
    <Route path="/organizations/new" element={<Protected><OrganizationOnboardingPage /></Protected>} />
    <Route path="/organizations/:organizationId" element={<Protected><DashboardPage /></Protected>} />
    <Route path="/organizations/:organizationId/settings" element={<Protected><OrganizationSettingsPage /></Protected>} />
    <Route path="/organizations/:organizationId/billing" element={<Protected><OrganizationBillingPage /></Protected>} />
    <Route path="*" element={<Navigate to="/sign-in" replace />} />
  </Routes></BrowserRouter></AuthProvider>
}
