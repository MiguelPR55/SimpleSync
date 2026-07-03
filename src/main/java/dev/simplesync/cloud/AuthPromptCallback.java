package dev.simplesync.cloud;

/**
 * Callback interface triggered when the OAuth Device Authorization Grant requires user interaction.
 */
@FunctionalInterface
public interface AuthPromptCallback {
    /**
     * Called when the device authorization code and verification URL are received from Google.
     *
     * @param userCode         The code to be entered by the user (e.g. "ABCD-EFGH")
     * @param verificationUrl  The URL where the user should enter the code (e.g. "https://www.google.com/device")
     * @param expiresInSeconds The duration in seconds before the code expires
     * @param onCancel         Runnable to invoke if the user cancels the authentication from the UI
     */
    void onAuthPrompt(String userCode, String verificationUrl, long expiresInSeconds, Runnable onCancel);
}
