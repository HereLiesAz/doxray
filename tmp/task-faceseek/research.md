# FaceSeek.online API Research

FaceSeek provides an AI-powered Face Search API that allows matching faces from large datasets or the web using a single photo.

## Key Features
*   **Reverse Image Search for Faces**: Focuses on facial features and structure rather than exact pixel matches, allowing it to work across edits, crops, formats, and reposts.
*   **Use Cases**: Verifying dating profiles, spotting fake accounts, identity verification, tracing online presence.
*   **Ethical Use**: Operates on the principle of collecting/analyzing publicly available info and claims compliance with global privacy regulations (GDPR, CCPA).

## API Details
*   **Host**: `https://faceseek.online`
*   **Authentication**: Requires a valid `API_KEY` passed in the request payload under `api_token` or `token`.
*   **Format**: JSON responses.

### Endpoints
*   **`/search_face` (POST)**: 
    *   Purpose: Upload an image or image URL to search for matching faces.
*   **`/token_status` (POST)**:
    *   Purpose: Check API key balance and remaining usage quota.

## Integration Methods
The API can be integrated into applications, websites, or backend services. Examples found mention integration using:
*   **Python**: using `gradio_client`
*   **JavaScript**: using `@gradio/client`

## References
*   [GitHub Documentation/Examples](https://github.com/)
*   [FaceSeek.online Official Site](https://faceseek.online/)
*   [Hugging Face Spaces](https://huggingface.co/)