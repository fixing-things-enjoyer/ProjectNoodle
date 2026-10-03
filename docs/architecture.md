# Repository architecture

`app/` contains the Android app, sharing service and HTTP/HTTPS server. `webui/` contains the React client, its locked dependencies and browser tests. The browser connects directly to the Android server.

Android Gradle runs `installWebUi` → `buildWebUi` → `preBuild`. Vite builds `webui/dist/`, which is included as an Android asset source directory. Generated files are ignored by Git. Editing React source and running `./gradlew assembleDebug` produces an APK containing that exact UI. A clean checkout requires Node and Java, not a sibling repository or manually copied bundle.

## Previous UI provenance

The old bundled assets were produced by `~/dev/project-noodle-webui`, whose `dist/assets/` files exactly matched both files in `app/src/main/assets/assets/`:

| Asset | SHA-256 |
| --- | --- |
| `index-CFrsfol6.css` | `df26edc9e9966f11525fd6664f93032e97ba3a2b0e4f55fa2c4569cf2b535d36` |
| `index-CeAnbL3S.js` | `5e146b15c34c280035b35584ced3c7904809feaec5c8c325f7638fb9b5dbefaa` |

`~/dev/noodle-webui` only contained `.vite` cache files. Neither sibling is used by this repository now. The React app was rebuilt here rather than importing the old bundle or its environment-specific API configuration.

## Runtime

- Android stores the selected SAF tree's read and write grants, and remembers sharing options in the existing preferences file.
- `SharingSession` exposes a lifecycle-aware state flow to Compose. Looking at status never starts a service or changes a running session's configuration.
- The foreground service starts storage and certificate work on a worker thread. A notification offers Stop sharing. Network changes refresh the displayed address. Android's data-sync timeout stops the session cleanly.
- Optional connection approval uses the app and separate notifications for each client. Public UI assets load before approval; file APIs stay protected. Pending requests are deduplicated.
- The React app uses relative, same-origin URLs and checks the live connection in the background. It encodes filenames once, queues uploads, shows progress and errors, and confirms deletions.
- The server rejects traversal, invalid names, cross-origin API requests and duplicate uploads. It serializes mutations, removes incomplete uploads, and supports read-only folders. Directory metadata is read in one provider query. Shared HTML and SVG download as attachments.
- HTTPS uses an explicitly selected Bouncy Castle provider and a local certificate with an IP subject alternative name. Browsers still require approval for this self-signed certificate.

## API

| Endpoint | Method | Parameters |
| --- | --- | --- |
| `/api/list` | GET | `path` (default `/`) |
| `/api/mkdir` | POST | `path`, `newDirName` |
| `/api/rename` | POST | `path`, `newName` |
| `/api/delete` | POST | `path` |
| `/api/upload` | POST | `path` in query; multipart `fileName` and one `file` |
| `/files/{path}` | GET | `download=1` forces download |

Mutation parameters use URL-encoded forms. API failures return JSON with a readable `message`. Listings include `sharedFolderName`, `currentPath`, `canWrite` and `items`. Paths are literal logical document names after the HTTP parser decodes the request once.

## Tests

Browser tests run desktop and phone layouts with a mock API, exercise browsing, search, filters, mutations, uploads, read-only/error/approval states, and check accessibility in both themes. Device tests use a test-only SAF provider with private sandbox files to exercise the real NanoHTTPD server, multipart uploads, filenames, access control, HTTPS and bundled assets. Compose tests cover the first-run, start, stop and connection-request flows. The test provider is never included in production APKs.

The built APK was also checked through Android's real folder picker and storage provider: select a writable folder, start sharing, connect the bundled React app from a desktop browser, upload, download, create a folder, rename and delete. Download bytes and literal `+`/`%` filenames matched.
