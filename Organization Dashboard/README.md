# theSpaces. Organization Dashboard

A separate PWA setup and control surface for organization administrators. Native iOS and Android remain the member content experience; this dashboard never reads or renders Space content such as messages, Rooms, Announcements, Media, Files, Polls, Events, Lists, or Notes.

Milestone 2 adds Foundation organization onboarding, optional identity metadata, primary-administrator creation, administrator invitations, and supported role management. Capacity and paid module entitlements remain read-only.

## Local setup

1. Use Node.js 20.19+ or 22.12+.
2. Run `npm install`.
3. Copy `.env.example` to `.env.local` and add the existing theSpaces. Firebase web-app configuration.
4. Run `npm run dev` and open the displayed local URL.

Required environment variables:

- `VITE_FIREBASE_API_KEY`
- `VITE_FIREBASE_AUTH_DOMAIN`
- `VITE_FIREBASE_PROJECT_ID`
- `VITE_FIREBASE_STORAGE_BUCKET`
- `VITE_FIREBASE_MESSAGING_SENDER_ID`
- `VITE_FIREBASE_APP_ID`

The local `.env.local` is configured for the dedicated `theSpaces. Organization Dashboard` web app registration in the existing `spaces-by-circl` Firebase project and remains excluded from version control. Google and Apple sign-in use the same Firebase Authentication users as the native apps.

## Data contract

The web reader mirrors the v1.6 mobile contract:

- Organizations: `organizations`, filtered with `memberIds array-contains <uid>`.
- Administrators: `organizations/{organizationId}/members`, filtered in the client to roles `primary_admin` and `admin`.
- Owned Spaces: top-level `spaces`, filtered with `organizationId == <organizationId>`.
- Entitlements: `peopleCapacity`, `activeSpaceCapacity`, `enabledModuleIds`, and `mediaStorageCapacityBytes` under `entitlements`.
- Usage: `peopleCount`, `activeSpaceCount`, and `mediaStorageBytes` under `usage`.
- Archived Spaces are identified by `isArchived == true` and do not count as active.
- Optional identity metadata is additive on the organization document: `description`, `contactEmail`, `website`, and `logoDataUrl`. The logo is imported locally as a PNG, JPEG, or WebP image (250 KB maximum), not supplied as an external URL. Native clients safely ignore these fields.
- Invitations use `organizationInvites/{code}` with the native fields `organizationId`, `organizationName`, `role`, `createdBy`, `createdAt`, and `active`.

Foundation creation writes the organization and `organizations/{organizationId}/members/{uid}` primary-administrator document in one atomic batch. It applies only the approved Foundation values: 250 unique members, 10 active Spaces, 10 GB pooled storage, and module IDs `general`, `events`, `polls`, `activity`, `members`, and `settings`.

The dashboard does not include billing, subscription planning, paid entitlement activation, or content decryption. Firebase rules remain owned and managed separately and are never deployed by this project.

## Current backend authorization limits

The checked-in v1.6 Firestore rules authorize Foundation bootstrap, invitation creation, and primary-admin role updates. They currently reject post-setup identity updates, administrator-member deletion, and administrator-driven invitation revocation. The PWA reports permission-denied errors for rejected operations and does not work around the rules. No Firebase rules were changed for Milestone 2.

## Verification

- `npm test` — mapping, dashboard-state, validation, Foundation-default, idempotency, permission, and onboarding-state tests.
- `npm run build` — type-check and production PWA bundle.
