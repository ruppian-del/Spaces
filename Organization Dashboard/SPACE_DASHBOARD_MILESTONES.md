# Organization Space Dashboard — Milestone Plan

## Confirmed boundary

- This is a separate, lower-level dashboard for one Organization Space.
- It never exposes organization billing, organization-wide settings, or Spaces the signed-in user does not administer.
- A Space admin may administer more than one Space. They sign in once and can switch only among their assigned Spaces.
- Space membership and Space administration remain Space-specific.
- The Organization Dashboard only tracks the unique-member capacity across organization-owned Spaces; it does not manage memberships.

## Milestones to define before implementation

1. Space Dashboard foundation and access
   - Space switcher limited to Spaces the user administers
   - Read-only Space overview: identity, role, member count, enabled modules, and recent activity
   - Organization Dashboard button shown only when the signed-in user is an organization administrator
2. Space people and administration
   - Members do not access the Space Dashboard.
   - A Space owner is an organization administrator; ownership remains an organization-level role.
   - Organization admins can access every Space Dashboard in their organization.
   - Space admins can access only the Space Dashboards they administer; moderators do not access the dashboard.
   - Space admins manage the roster for their own Space.
   - Space admins assign and remove Space-admin access for their own Space.
   - Every member belongs directly to a Space; there is no organization-wide member roster.
3. Space identity and settings
   - The Space Dashboard mirrors the Space-admin capabilities available in the theSpaces. app for that Space.
   - It does not create a separate or reduced set of Space-admin permissions.
   - Space identity: name, emoji, color, description, and template.
   - Space controls: privacy, Safe Mode, and Space notification preference.
   - Invite controls: member-invite permission and the existing Space invite link.
   - Module settings: enabled modules and module order, limited to the organization-entitled modules.
4. Space planning workspace
   - Module-based planning tools available when enabled for the Space and entitled by the organization plan.
   - Events, Agenda tools (lists and notes), Content (files and media), and relevant Communication tools.
   - This is a planning workspace, not a separate billing or organization-management surface.
## Mobile-only

- Quick, day-to-day Space interaction—including Pings—remains mobile-first.

## Planning rule

Every milestone must have its scope, permissions, screens, data source, and acceptance checks agreed before any implementation begins.
