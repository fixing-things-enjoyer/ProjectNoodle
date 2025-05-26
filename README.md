# Project Noodle

<img src="app/src/main/assets/project_noodle.png" alt="Project Noodle Icon" width="100">

Project Noodle turns your Android device into a simple file server, accessible via a web browser on your local network.

## Features
- Share files and folders from your Android device.
- Web interface for browsing, uploading, downloading, renaming, deleting, and creating folders.
- Access from any device on the same Wi-Fi network.
- Optional connection approval via notifications.
- Optional HTTPS with self-signed certificates (requires browser trust bypass).
- Runs as a foreground service for reliability.

## Getting Started
1. Open Project Noodle ([grab latest apk here](https://github.com/fixing-things-enjoyer/ProjectNoodle/releases)).
2. Select a directory to share (uses [SAF](https://developer.android.com/guide/topics/providers/document-provider)).
3. (Optional) Enable connection approval or HTTPS.
4. Start the server to get a URL (e.g., `http://192.168.1.100:54321`).
5. Access the URL in a browser on the same Wi-Fi.
6. Approve connections if enabled.
7. Manage files via the browser (can optionally drag/drop files onto webui to upload).
8. Stop the server when done.

## Built With
- Kotlin
- Android Jetpack Compose
- NanoHTTPD
- Bouncy Castle (for HTTPS)

## Contributing
Open issues or pull requests are welcome.

## License
Apache License 2.0 (see `LICENSE`).

## Author
fixingthingsenjoyer
