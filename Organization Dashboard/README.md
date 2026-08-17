# theSpaces. Organization Dashboard

A separate, read-only PWA control surface for organization administrators. Native iOS and Android remain the member content experience; this dashboard never reads or renders Space content such as messages, Rooms, Announcements, Media, Files, Polls, Events, Lists, or Notes.

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

The dashboard only performs reads. It does not include rules, deploy configuration, billing, subscription planning, mutations, or content decryption.

## Verification

- `npm test` — mapping and dashboard-state tests.
- `npm run build` — type-check and production PWA bundle.
