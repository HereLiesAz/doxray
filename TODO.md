# Doxray Project Plan

## High-Level Goals
- Real-time facial recognition based person search
- Target Hardware: Meta Ray Bans
- Package Namespace: `com.hereliesaz.doxray`
- Tech Stack: Meta SDK, faceseek.online (API & Scrape), Lenso.ai (API & Scrape), Yandex Search (API & Scrape)

## Tasks
- [x] Research Meta Ray Bans SDK for real-time video/image capture stream.
- [x] Research faceseek.online API capabilities and integration methods.
- [x] Research Yandex Search API for reverse image or general search integration.
- [x] Setup initial project repository and architecture (Package: `com.hereliesaz.doxray`).
- [x] Implement video/image stream capture from glasses.
- [x] Implement integration with faceseek.online for facial recognition.
- [x] Implement integration with Lenso.ai (eyematch.ai) for facial recognition.
- [x] Implement integration with Yandex Search API to correlate recognized faces with online identity data.
- [x] Implement result display/audio feedback to the user via Meta Ray Bans.
- [x] Implement scraper fallback for FaceSeek.
- [x] Implement scraper fallback for Lenso.ai.
- [x] Implement scraper fallback for Yandex.
- [x] Design and implement Android UI (Connection status, recent matches).
- [x] Implement robust error handling and runtime permissions.
- [x] Implement Room Database for persistent identity history and investigative flows.
- [x] Implement scraper for smartbackgroundchecks.com to gather deep background data.
- [x] Implement scraper for cyberbackgroundchecks.com to gather deep background data.
- [ ] Create unit tests and instrumented tests.
- [ ] End-to-end testing and refinement.
