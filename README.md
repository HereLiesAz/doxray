# doxray
Real-time facial recognition based person search using Meta Ray Bans

## Features
- Real-time video/image capture stream from Meta Ray Bans using the official Meta Wearables DAT SDK.
- Local Face Detection and Tracking using Google ML Kit (5-second focus requirement).
- Offline Facial Embeddings via TensorFlow Lite (MobileFaceNet).
- **Investigative Flow & Persistent Cache:** Uses Android Room Database to persist matched faces and their metadata locally. Instantly recognizes previously identified people without needing a network call, providing encounter counts and last-seen timestamps via audio feedback.
- Real-time facial recognition using Lenso.ai (Eyematch) and FaceSeek as a fallback.
- Additional identity correlation via Yandex Reverse Image Search.
- Robust Scraper Fallbacks (Jsoup) for all APIs in case of rate limits or service unavailability.
- Package Namespace: `com.hereliesaz.doxray`

## Setup

### API keys
Put any of the following in a project-root `local.properties` (gitignored):

```
SERPAPI_KEY=<your-serpapi-key>
FACESEEK_KEY=<your-faceseek-key>
LENSO_KEY=<your-lenso-key>
FACECHECK_KEY=<your-facecheck-key>
PIMEYES_KEY=<your-pimeyes-bearer-token>
TINEYE_KEY=<your-tineye-public-key>
TINEYE_SECRET=<your-tineye-private-key>
```

Any missing key causes that service to skip the API path and fall back to the scraper.

Google Lens has no official API key — the scraper handles all Google Lens queries.

### Meta Wearables DAT SDK (closed beta)
The real `com.facebook.wearables:dat-android` artifact is published to a private GitHub Packages repo. To use it instead of the local stub fallback, add the following to `local.properties`:

```
gh.user=<your-github-username>
gh.token=<personal-access-token-with-read:packages>
gh.packages.url=https://maven.pkg.github.com/facebook/meta-wearables-dat-android
```

Then verify with:

```
./gradlew verifyMetaSdk
```

If `gh.packages.url` is left empty the build uses local stubs from `app/src/stub/java/` and the glasses-dependent code paths no-op at runtime.

### Debug HTTP capture
Debug builds set `BuildConfig.DEBUG_CAPTURE_HTTP=true`. Every HTTP request/response from the network layer is written to
`Android/data/com.hereliesaz.doxray/files/captures/{timestamp}_{seq}_{host}.{req|resp}.bin`
on the device. Pull them with:

```
adb shell run-as com.hereliesaz.doxray ls /sdcard/Android/data/com.hereliesaz.doxray/files/captures/
adb pull /sdcard/Android/data/com.hereliesaz.doxray/files/captures/ ./captures/
```