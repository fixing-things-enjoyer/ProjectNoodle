# Builds and releases

## Every push and pull request

`.github/workflows/ci.yml` runs three checks:

1. React type/build/format checks, desktop and phone browser tests, light/dark accessibility checks.
2. Android debug build, unit tests and lint. The debug APK is downloadable from the workflow's artifacts.
3. API 35 emulator tests of Compose and the real HTTP/HTTPS server.

The same checks run before a tagged release. Dependencies and GitHub Actions are reviewed through weekly Dependabot PRs.

## Publish a release

Keep `noodleVersion` in `gradle.properties` at the next intended version for local builds. Push a tag at the intended release commit:

```bash
git tag v2.0.0
git push origin v2.0.0
```

The release workflow accepts `vMAJOR.MINOR.PATCH`, with each number between 0 and 999. It uses the tag as the APK's version name and computes the Android version code as `major × 1,000,000 + minor × 1,000 + patch`. Increase versions for upgrades; do not reuse released tags.

After all checks pass, `.github/workflows/android-release.yml`:

1. Validates the tag and signing secrets.
2. Decodes the original release keystore into runner temporary storage.
3. Builds React and the release APK from the same tagged checkout.
4. Verifies the APK signature.
5. Publishes `ProjectNoodle-{version}.apk`, `SHA256SUMS` and generated release notes to GitHub Releases.
6. Removes temporary signing material.

The repository's existing secret names are preserved:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_STORE_PASSWORD`

All four names were present when the repository was inspected. Their contents were not read or changed. Missing secrets fail the workflow before signing or publishing. Use the same original key to preserve update compatibility with installed APKs.

## Local builds

Java 17, Node 24 (minimum 22.12), and Android SDK 35 are required. Set `JAVA_HOME` and `ANDROID_HOME` for your installation or use Android Studio's Gradle JDK and SDK settings. Gradle builds and embeds the web UI automatically:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

With an emulator or phone connected:

```bash
./gradlew connectedDebugAndroidTest
```

A local release build works without signing secrets and produces an unsigned APK:

```bash
./gradlew assembleRelease
```

To sign locally, copy `.env.example` to `.env` and fill in the original key's location and passwords. Environment variables take precedence. The ignored keystore and `.env` files must remain outside Git. CI publishes only signed APKs.

## React development

```bash
cd webui
npm ci
npm run dev
```

Forward a running Android server to the local proxy's port (replace `PHONE_PORT` with the number displayed in the app):

```bash
adb forward tcp:8080 tcp:PHONE_PORT
```

Vite proxies `/api` and `/files` to `http://127.0.0.1:8080`. Start the phone server with HTTPS off while using this development proxy. The normal bundled UI supports both HTTP and HTTPS.

```bash
npm run format:check
npm run build
npx playwright install chromium
npm test
```

Run npm dependency installation and Gradle builds sequentially when sharing a checkout with an active browser test run: Gradle may execute `npm ci` when package metadata changes.
