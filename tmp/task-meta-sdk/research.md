# Meta Ray-Ban SDK Real-Time Video Capture Stream Research

Based on recent updates, here are the key details regarding the real-time video and image capture streams using the Meta Ray-Ban SDK (officially known as the **Meta Wearables Device Access Toolkit**):

## Overview
The toolkit enables developers to access real-time video capture streams and photos from Meta's smart glasses. Supported devices include:
*   Ray-Ban Meta (Gen 1 and Gen 2)
*   Ray-Ban Meta Display
*   Oakley Meta HSTN
*   Oakley Meta Vanguard

## Key Features & Specifications
*   **Core Functionality:** The SDK allows mobile apps to capture photos or start video streams directly from the smart glasses. This is primarily aimed at creating hands-free experiences like livestreaming and AI-assisted applications.
*   **Resolution and Frame Rate:** Video streams max out at **720p resolution and 30 FPS**. These limits are due to Bluetooth bandwidth constraints. If bandwidth drops, the resolution and frame rate will automatically scale down.

## Development & Distribution
*   **SDK Availability:** Meta offers iOS and Android SDKs, including pre-built libraries, sample apps, and API documentation. 
*   **Developer Mode:** To build and test using personal smart glasses, a specific "Developer Mode" must be activated within the Meta AI companion app.
*   **Publishing Limits:** Currently, general public release of third-party apps using this SDK is **restricted to select partners**. While anyone might be able to experiment locally, broad distribution via app stores requires partnering with Meta.

## Use Cases & Future Enhancements
*   **Current Applications:** Developers have utilized the stream for platforms like Twitch and Instagram (livestreaming), as well as integrating with AI vision tools (e.g., Microsoft Seeing AI for real-time visual assistance).
*   **Upcoming Features:** Meta is continuing to integrate Meta AI capabilities and exploring ways to display imagery on the Meta Ray-Ban Display's heads-up display (HUD). They are also looking to incorporate gestures from the Meta Neural Band in future updates.

## Sources
*   [UploadVR - Meta Smart Glasses Toolkit](https://uploadvr.com/)
*   [Meta Official Developer Documentation](https://meta.com/)
*   [DoubleTapOnAir](https://doubletaponair.com/)
*   [LushBinary](https://lushbinary.com/)