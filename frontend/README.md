# Creastrix frontend

FRONTEND-BOOTSTRAP-001 provides a UI-only scaffold in this checkout.
This independently built React/TypeScript/Vite application belongs to the existing
Creastrix repository; it is not a separate Git project.

## Local use

Use Node **22.14.0** and its npm **10.9.2** (also pinned in `.nvmrc` and package
engines). This matches the existing author toolchain; no global upgrade is required.
From the repository root:

```sh
cd frontend
npm ci
npm run dev
```

Open `http://localhost:3000`. Both dev and preview bind to `127.0.0.1:3000`,
with strict port selection. If the port is occupied, stop and resolve the conflict;
do not stop an unrelated process or expose the server publicly.

Stop your own dev server with `Ctrl+C` before starting preview. Run the following
commands from `frontend` (the directory selected above):

```sh
npm run typecheck
npm run lint
npm test
npm run build
npm run preview
```

Dev and preview support direct reloads of `/`, `/login`, `/account` and the local
not-found screen. Preview serves the built `dist` assets. Do not run dev and preview
at the same time on port 3000. Neither command is a deployment service.

## What is present

- A neutral light UI and the selected, unchanged **3C / SYMMETRY** logo/favicon.
  A colour palette or Atelier/Studio variant has not been approved.
- Local DE/EN dictionaries; primary browser language `de`/`de-*` selects German,
  otherwise English. A saved valid explicit choice takes precedence. German uses `du`.
- Redux Toolkit and typed React Redux hooks for shared **language preference only**.
  `creastrix.ui.language` is the only persisted value. Unavailable localStorage is
  tolerated; the in-memory selection continues to work.
- Routes with visible focus, keyboard navigation, skip link and a fictional Alex
  profile. `/account` is an openly accessible demo, not a protected account area.
- Explanations of future hosted sign-in, with disabled email/password, Google and
  Apple controls and a separate **View demo** action. No credentials are collected.
- An optional, empty Workspace state. No Workspace is created, no one-per-user
  restriction is introduced, and ownership is not seller eligibility.

React 19.3.0, Vite 8.3.1, Redux Toolkit 2.13.0 and React Redux 9.3.0 are pinned.
React Router 7.18.4 is the current compatible 7.x release (8.x requires a newer Node).
TypeScript 6.0.3 remains within typescript-eslint's supported range; 7.x is not used.
Vitest 5.0.2, Testing Library and happy-dom 20.14.5 provide focused UI tests, not a
replacement for browser smoke or independent review. All dependencies are locked.

## Deliberate boundaries

There is **no backend proxy, API client, Auth0 SDK, token/cookie handling, CSRF,
real login, callback, registration, logout or persisted user/domain state**.
The Vite development transport is tooling, not a backend WebSocket integration.
No Workspace/RMP forms, role selection, commerce or permissions model are implemented.

`AUTH-COOKIE-FOLLOWUP-001` remains **OPEN**. This demo is neither React/browser
authentication verification F nor real Auth0 verification P. It does not resume the
separate stopped browser proof or transfer any external server candidate into main.
Backend implementation state remains separate from this UI scaffold.

## Verification and IDE

Open the existing repository or its `frontend` directory in WebStorm. Keep the
existing Node/IDE settings; npm scripts are defined in `package.json`. Never create a
nested repository or commit shared IDE state. Ignored dependencies/build output may
remain locally; `.env`, `.idea`, coverage and caches are not source files.

The [Frontend CI](../.github/workflows/frontend-ci.yml) workflow runs locked install,
typecheck, lint, tests and build on changes under `frontend/**` or to that workflow,
for pull requests and pushes to main. It has read-only contents permission and pinned
official actions. Backend CI has separate path filters; frontend verification does
not substitute for Maven, Docker or backend checks.

See the [root README](../README.md#frontend-ui-foundation) for repository context.
