# SimpleSync

SimpleSync is a Minecraft mod developed for the Fabric platform (Java 21) that automatically synchronizes your single-player world saves with Google Drive.

## Features

- Background synchronization: Upload and download operations run asynchronously on a background thread to prevent game freezes.
- Conflict detection and resolution: If a world has been modified both locally and in the cloud, a GUI is displayed so you can choose which version to keep.
- Visual HUD overlay: Displays real-time sync status (compressing, uploading, downloading, etc.) along with the completion percentage.
- Robustness and security:
  - Automatic self-healing for abnormal shutdowns (cleans up and restores backups).
  - Prevention of path traversal attacks (zip-slip).
  - Retries with exponential backoff, failing immediately on permanent client-side errors (4xx).

## Requirements and Installation

1. Ensure you have Minecraft with Fabric Loader installed.
2. Place the mod's `.jar` file in your `mods/` directory.
3. Create a project in the Google Cloud Console, enable the Google Drive API, create OAuth2 credentials for a desktop application, and download the credentials JSON file.
4. Place the downloaded JSON file as `client_secret.json` in the path `config/simplesync/client_secret.json`.
5. Upon launching the game for the first time, the mod will prompt you for authorization in your web browser.

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
