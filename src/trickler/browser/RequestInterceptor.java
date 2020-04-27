package trickler.browser;

public interface RequestInterceptor {
    void onRequestPaused(PausedRequest request);
}
