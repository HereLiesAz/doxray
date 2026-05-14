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