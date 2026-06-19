package com.example;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

public class MainActivity extends Activity {

    // Models representation for high-fidelity parsed channels
    public static class IptvChannel {
        public String name;
        public String url;
        public String logoUrl;
        public String group;

        public IptvChannel(String name, String url, String logoUrl, String group) {
            this.name = name != null ? name.trim() : "Unknown Channel";
            this.url = url != null ? url.trim() : "";
            this.logoUrl = logoUrl != null ? logoUrl.trim() : "";
            this.group = (group != null && !group.trim().isEmpty()) ? group.trim() : "General";
        }
    }

    // Newly added elements for dynamic IPTV / M3U Playlist features
    private android.widget.EditText mEditM3uUrl;
    private android.widget.Button mBtnLoadCustom;
    private android.widget.EditText mEditSearchChannel;
    private android.widget.ListView mListCategories;
    private android.widget.ListView mListChannels;
    private android.widget.TextView mTvActiveStatus;
    private android.widget.TextView mTvLoadingText;
    private android.widget.TextView mTvLoadingSubtext;

    // Presets and Channels State
    private final java.util.List<IptvChannel> mAllChannels = new java.util.ArrayList<>();
    private final java.util.List<String> mCategories = new java.util.ArrayList<>();
    private String mSelectedCategory = "ALL CHANNELS";
    private String mSearchText = "";

    private CategoryAdapter mCategoryAdapter;
    private ChannelAdapter mChannelAdapter;

    private String mSelectedChannelUrl = "";

    private LinearLayout mLayoutLanding;
    private RelativeLayout mLayoutLoadingOverlay;
    private LinearLayout mLayoutOffline;
    private WebView mWebView;
    private Button mBtnUploadFile;
    private Button mBtnRetry;

    // Connectivity monitoring
    private BroadcastReceiver mNetworkReceiver;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private boolean mIsConnected = true;

    // Background thread for keeping cache directories continuously populated
    private volatile boolean mDestroyed = false;
    private Thread mDirWatcherThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Pre-create WebView/Chromium Code Cache directories as early as possible with global permissions
        ensureWebViewCacheDirectoriesExist();
        startCacheDirectoryKeepAliveDaemon();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Hide system bars for a clean immersive TV / touchscreen full-screen experience
        hideSystemUI();

        // Bind layout views
        mLayoutLanding = findViewById(R.id.layout_landing);
        mLayoutLoadingOverlay = findViewById(R.id.layout_loading_overlay);
        mLayoutOffline = findViewById(R.id.layout_offline);
        mWebView = findViewById(R.id.webview_iptv);
        mBtnUploadFile = findViewById(R.id.btn_upload_file);
        mBtnRetry = findViewById(R.id.btn_retry);

        // Bind newly added M3U IPTV interface views
        mEditM3uUrl = findViewById(R.id.edit_m3u_url);
        mBtnLoadCustom = findViewById(R.id.btn_load_custom);
        mEditSearchChannel = findViewById(R.id.edit_search_channel);
        mListCategories = findViewById(R.id.list_categories);
        mListChannels = findViewById(R.id.list_channels);
        mTvActiveStatus = findViewById(R.id.tv_active_status);
        mTvLoadingText = findViewById(R.id.tv_loading_text);
        mTvLoadingSubtext = findViewById(R.id.tv_loading_subtext);

        // Setup WebView
        setupWebView();

        // Setup local file picker action (VITAL FOR DIRECT USER FILE UPLOAD / PLAYING!)
        mBtnUploadFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                String[] mimetypes = {"audio/x-mpegurl", "application/x-mpegurl", "text/plain", "application/octet-stream"};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select M3U Playlist File"), 1001);
                } catch (Exception e) {
                    onPlaylistLoadFailed("Could not open file picker: " + e.getMessage());
                }
            }
        });

        // Parse custom typed URL
        mBtnLoadCustom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String customUrl = mEditM3uUrl.getText().toString().trim();
                if (!customUrl.isEmpty()) {
                    loadM3uPlaylist(customUrl);
                }
            }
        });

        // Real-time responsive search filter
        mEditSearchChannel.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mSearchText = s.toString();
                filterAndDisplayChannels();
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        // Category/Group list selection listener
        mListCategories.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                mSelectedCategory = mCategories.get(position);
                if (mCategoryAdapter != null) {
                    mCategoryAdapter.setSelectedPosition(position);
                }
                filterAndDisplayChannels();
            }
        });

        // Channel list selection listener -> opens web player or HlsJS embedded container
        mListChannels.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                IptvChannel ch = (IptvChannel) mChannelAdapter.getItem(position);
                if (ch != null && ch.url != null && !ch.url.isEmpty()) {
                    mSelectedChannelUrl = ch.url;
                    openIptvStream();
                }
            }
        });

        // Offline screen retry listener
        mBtnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isNetworkAvailable()) {
                    hideOfflineScreen();
                    reloadOrOpenIptv();
                }
            }
        });

        // Set list selectors to highlight TV navigations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mListCategories.setSelector(new android.graphics.drawable.ColorDrawable(0x401557B0));
            mListChannels.setSelector(new android.graphics.drawable.ColorDrawable(0x401557B0));
        }

        // Set focus on the first button initially
        mBtnUploadFile.requestFocus();

        // Setup connectivity monitoring broadcast receiver
        setupNetworkReceiver();

        mEditM3uUrl.setText("");
        if (mTvActiveStatus != null) {
            mTvActiveStatus.setText("Ready - Load a URL or Choose a File");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    private void setupWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true); // REQUIRED for HTML5 streaming video players
        settings.setDatabaseEnabled(true); // REQUIRED for indexing and buffering IPTV stream chunks
        settings.setAllowFileAccess(true); // Allow file access so local caches work better
        settings.setGeolocationEnabled(false); // Explicitly disable geolocation API to prevent AppOps warnings and save memory
        settings.setMediaPlaybackRequiresUserGesture(false); // Stream automatically without requiring extra TV controller OK clicks
        
        // Use a modern, widely accepted desktop-class User-Agent so that the TV matches high-fidelity desktop web players
        // and avoids broken mobile app redirection pages.
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        
        // Diagnostic check: Force hardware acceleration to improve streaming performance.
        // On very low-end hardware, we verify memory and window capabilities before choosing layers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            boolean isLowRam = false;
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                isLowRam = am.isLowRamDevice();
            }

            // Check if the system has hardware acceleration enabled at window level from the Manifest
            if (getWindow() != null && getWindow().getDecorView().isHardwareAccelerated()) {
                mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            } else if (!isLowRam) {
                // Device has sufficient RAM, force hardware rendering to boost loading & media decoding
                mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            } else {
                // Software rendering safe fallback for ultra-low-RAM legacy TV configurations to avoid GL out-of-memory crashes
                mWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
        }

        // High performance TV configuration: Enable app cache on supported older platforms via reflection to avoid SDK compilation errors
        if (Build.VERSION.SDK_INT < 33) {
            try {
                java.lang.reflect.Method setAppCacheEnabledMethod = settings.getClass().getMethod("setAppCacheEnabled", boolean.class);
                setAppCacheEnabledMethod.invoke(settings, true);
                
                java.lang.reflect.Method setAppCachePathMethod = settings.getClass().getMethod("setAppCachePath", String.class);
                String path = getCacheDir().getAbsolutePath() + "/appcache";
                setAppCachePathMethod.invoke(settings, path);
            } catch (Exception ignored) {}
        }
        
        // Minimal network traffic: Initialize using optimized TV caching
        updateCacheModeBasedOnNetwork();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Prevent horizontal and vertical scrollbar clutter for a clean layout
        mWebView.setHorizontalScrollBarEnabled(false);
        mWebView.setVerticalScrollBarEnabled(false);

        // Disables long click context menu, text selection and copying
        mWebView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true; // Consume long-press events
            }
        });
        mWebView.setLongClickable(false);

        // Override Geolocation Permissions to automatically deny
        mWebView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
        });

        // Use custom client with low-RAM and smart cache management logic
        mWebView.setWebViewClient(new OptimizedTvWebViewClient());
    }

    private void openIptvStream() {
        if (!isNetworkAvailable()) {
            showOfflineScreen();
            return;
        }

        // Hide landing menu and show fullscreen web container
        mLayoutLanding.setVisibility(View.GONE);
        mLayoutOffline.setVisibility(View.GONE);
        mWebView.setVisibility(View.VISIBLE);
        if (mLayoutLoadingOverlay != null) {
            mLayoutLoadingOverlay.setVisibility(View.VISIBLE);
        }

        // Pre-create WebView/Chromium Code Cache directories
        ensureWebViewCacheDirectoriesExist();
        ensureWebViewCacheDirectoriesExistWithDelay(100);
        ensureWebViewCacheDirectoriesExistWithDelay(300);

        // Start loading the target address
        if (isDirectVideoStream(mSelectedChannelUrl)) {
            playDirectStream(mSelectedChannelUrl);
        } else {
            mWebView.loadUrl(mSelectedChannelUrl);
        }
    }

    private void reloadOrOpenIptv() {
        if (mWebView.getVisibility() == View.VISIBLE) {
            String currentUrl = mWebView.getUrl();
            
            if (mLayoutLoadingOverlay != null) {
                mLayoutLoadingOverlay.setVisibility(View.VISIBLE);
            }

            // Pre-create WebView/Chromium Code Cache directories
            ensureWebViewCacheDirectoriesExist();
            ensureWebViewCacheDirectoriesExistWithDelay(100);
            ensureWebViewCacheDirectoriesExistWithDelay(300);

            if (currentUrl == null || currentUrl.equals("about:blank")) {
                if (isDirectVideoStream(mSelectedChannelUrl)) {
                    playDirectStream(mSelectedChannelUrl);
                } else {
                    mWebView.loadUrl(mSelectedChannelUrl);
                }
            } else {
                mWebView.reload();
            }
        } else {
            openIptvStream();
        }
    }

    private void showOfflineScreen() {
        if (mLayoutLoadingOverlay != null) {
            mLayoutLoadingOverlay.setVisibility(View.GONE);
        }
        mWebView.setVisibility(View.GONE);
        mLayoutLanding.setVisibility(View.GONE);
        mLayoutOffline.setVisibility(View.VISIBLE);
        mBtnRetry.requestFocus(); // Focus gets redirected to retry action on Android TV remote
    }

    private void hideOfflineScreen() {
        mLayoutOffline.setVisibility(View.GONE);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
            return capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            );
        } else {
            @SuppressWarnings("deprecation")
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    private void setupNetworkReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!mIsConnected) {
                                mIsConnected = true;
                                if (mLayoutOffline.getVisibility() == View.VISIBLE) {
                                    hideOfflineScreen();
                                    reloadOrOpenIptv();
                                }
                            }
                        }
                    });
                }

                @Override
                public void onLost(android.net.Network network) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mIsConnected = false;
                        }
                    });
                }
            };
        } else {
            mNetworkReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    boolean connectedNow = isNetworkAvailable();
                    if (connectedNow && !mIsConnected) {
                        // Internet connection returned. Automatically reload!
                        if (mLayoutOffline.getVisibility() == View.VISIBLE) {
                            hideOfflineScreen();
                            reloadOrOpenIptv();
                        }
                    }
                    mIsConnected = connectedNow;
                }
            };
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        checkMemoryAndClearCacheIfNeeded();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && mNetworkCallback != null) {
                try {
                    cm.registerDefaultNetworkCallback(mNetworkCallback);
                } catch (Exception e) {
                    // Fallback in case of system registering restrictions
                    registerReceiverFallback();
                }
            } else {
                registerReceiverFallback();
            }
        } else {
            registerReceiverFallback();
        }

        // Ensure focus is in front
        if (mLayoutLanding.getVisibility() == View.VISIBLE) {
            mBtnUploadFile.requestFocus();
        } else if (mLayoutOffline.getVisibility() == View.VISIBLE) {
            mBtnRetry.requestFocus();
        }
    }

    private void registerReceiverFallback() {
        if (mNetworkReceiver != null) {
            try {
                registerReceiver(mNetworkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && mNetworkCallback != null) {
                try {
                    cm.unregisterNetworkCallback(mNetworkCallback);
                } catch (Exception ignored) {}
            }
        }
        
        if (mNetworkReceiver != null) {
            try {
                unregisterReceiver(mNetworkReceiver);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Aggressive Cache Eviction Policy on transitioning to the background
        if (mWebView != null) {
            try {
                // Deletes references, RAM cache, and local disk cache structures
                mWebView.clearCache(true);
                
                // Clears Web SQL and localStorage manifest references
                android.webkit.WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {}
        }
        
        // Instantly re-verify and create safe directory templates
        // to appease Chromium opendir on resume
        ensureWebViewCacheDirectoriesExist();
        
        // Suggest garbage collection to reclaim memory bounds
        System.gc();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        if (mDirWatcherThread != null) {
            mDirWatcherThread.interrupt();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mWebView.getVisibility() == View.VISIBLE && mWebView.canGoBack()) {
            mWebView.goBack();
        } else if (mWebView.getVisibility() == View.VISIBLE || mLayoutOffline.getVisibility() == View.VISIBLE) {
            // Cancel current loading state, reset the web cache to keep RAM footprint low
            mWebView.stopLoading();
            mWebView.loadUrl("about:blank");

            // Bring the user back to the primary landing menu screen
            mWebView.setVisibility(View.GONE);
            if (mLayoutLoadingOverlay != null) {
                mLayoutLoadingOverlay.setVisibility(View.GONE);
            }
            mLayoutOffline.setVisibility(View.GONE);
            mLayoutLanding.setVisibility(View.VISIBLE);

            // Regain focus on the primary button
            mBtnUploadFile.requestFocus();
        } else {
            // Native TV exit confirmation when we are on the main screen
            showExitConfirmation();
        }
    }

    private void showExitConfirmation() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Exit Application")
                .setMessage("Are you sure you want to close Pavel IPTV Enterprise?")
                .setPositiveButton("Exit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Updates the cache mode of the WebView.
     * To keep network usage minimal: 
     * - If network is online, we prioritize local cache hits with LOAD_CACHE_ELSE_NETWORK.
     * - If offline, we enforce loading strictly from cached resource assets via LOAD_CACHE_ONLY.
     */
    private void updateCacheModeBasedOnNetwork() {
        if (mWebView != null) {
            WebSettings settings = mWebView.getSettings();
            if (isNetworkAvailable()) {
                settings.setCacheMode(WebSettings.LOAD_DEFAULT); // Ensures live HLS/DASH media manifests are loaded freshly and not stale cached
            } else {
                settings.setCacheMode(WebSettings.LOAD_CACHE_ONLY);
            }
        }
    }

    /**
     * Checks if the system memory is critically low using ActivityManager.MemoryInfo,
     * and clears the WebView cache to free up memory on legacy/low-RAM TV devices.
     */
    private void checkMemoryAndClearCacheIfNeeded() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
            try {
                am.getMemoryInfo(memoryInfo);
                if (memoryInfo.lowMemory) {
                    if (mWebView != null) {
                        mWebView.clearCache(true);
                        ensureWebViewCacheDirectoriesExist();
                    }
                    System.gc();
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Programmatically ensures that Chromium's internal cache directories (js/wasm)
     * exist, and configures global permissions on each segment to appease the isolated
     * WebView renderer process sandbox. Cleans up any legacy .keep files that can corrupt Simple Cache format.
     */
    private void ensureWebViewCacheDirectoriesExist() {
        try {
            java.io.File cacheDir = getCacheDir();
            if (cacheDir != null) {
                java.io.File webViewDir = new java.io.File(cacheDir, "WebView");
                java.io.File defaultDir = new java.io.File(webViewDir, "Default");
                
                // Legacy / Standard path: WebView/Default/HTTP Cache/Code Cache
                java.io.File httpCacheDir = new java.io.File(defaultDir, "HTTP Cache");
                java.io.File codeCacheDir1 = new java.io.File(httpCacheDir, "Code Cache");
                java.io.File jsDir1 = new java.io.File(codeCacheDir1, "js");
                java.io.File wasmDir1 = new java.io.File(codeCacheDir1, "wasm");

                // Modern Chromium path: WebView/Default/Code Cache
                java.io.File codeCacheDir2 = new java.io.File(defaultDir, "Code Cache");
                java.io.File jsDir2 = new java.io.File(codeCacheDir2, "js");
                java.io.File wasmDir2 = new java.io.File(codeCacheDir2, "wasm");

                createAndConfigureDirectory(webViewDir);
                createAndConfigureDirectory(defaultDir);

                // Configure standard path
                createAndConfigureDirectory(httpCacheDir);
                createAndConfigureDirectory(codeCacheDir1);
                createAndConfigureDirectory(jsDir1);
                createAndConfigureDirectory(wasmDir1);

                // Configure modern path
                createAndConfigureDirectory(codeCacheDir2);
                createAndConfigureDirectory(jsDir2);
                createAndConfigureDirectory(wasmDir2);

                // Clean up .keep files that can corrupt Simple Cache's disk format
                cleanupKeepFile(jsDir1);
                cleanupKeepFile(wasmDir1);
                cleanupKeepFile(jsDir2);
                cleanupKeepFile(wasmDir2);
            }
        } catch (Exception ignored) {}
    }

    private void cleanupKeepFile(java.io.File dir) {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            java.io.File keepFile = new java.io.File(dir, ".keep");
            if (keepFile.exists()) {
                keepFile.delete();
            }
        }
    }

    private void createAndConfigureDirectory(java.io.File dir) {
        if (dir == null) return;
        if (!dir.exists()) {
            dir.mkdirs();
        } else if (!dir.isDirectory()) {
            dir.delete();
            dir.mkdirs();
        }
        try {
            // Ensure world permissions are always set so any isolated process has absolute traversal and access
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false); // Essential for path traversal / opendir
        } catch (Exception ignored) {}
    }

    /**
     * Helper to schedule directory verification on a delayed main thread execution
     * pattern because Chromium initializes internal directories asynchronously.
     */
    private void ensureWebViewCacheDirectoriesExistWithDelay(long delayMs) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureWebViewCacheDirectoriesExist();
            }
        }, delayMs);
    }

    /**
     * Periodically keep the 'js' and 'wasm' directories populated in the background 
     * throughout the application lifecycle to bypass Chromium async cleanup race condition warnings.
     */
    private void startCacheDirectoryKeepAliveDaemon() {
        mDirWatcherThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!mDestroyed) {
                    ensureWebViewCacheDirectoriesExist();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        mDirWatcherThread.setDaemon(true);
        mDirWatcherThread.start();
    }

    /**
     * Low memory management to prevent crashes on extremely low RAM TVs.
     * When critical signals are caught, we free RAM caches while keeping offline capabilities intact.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL || level == TRIM_MEMORY_MODERATE) {
            if (mWebView != null) {
                try {
                    mWebView.freeMemory();
                } catch (Exception ignored) {}
            }
            System.gc();
        }
    }

    /**
     * A customized WebViewClient designed specifically for low-overhead TV hardware.
     */
    private class OptimizedTvWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            ensureWebViewCacheDirectoriesExist();
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                return false; // Let WebView handle standard HTTP/HTTPS links natively (crucial for streaming tokens and cookies)
            }
            // For custom schemes (intent, local players, android resources), launch external handlers safely
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                view.getContext().startActivity(intent);
            } catch (Exception ignored) {}
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
            ensureWebViewCacheDirectoriesExist();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                String url = request.getUrl().toString();
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    return false; // Let WebView handle standard web links natively
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, request.getUrl());
                    view.getContext().startActivity(intent);
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
            // Bypass SSL certificate errors which frequently happen on public live stream feeds and domains
            handler.proceed();
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (mLayoutLoadingOverlay != null) {
                mLayoutLoadingOverlay.setVisibility(View.VISIBLE);
            }
            // Ensure directories exist immediately and via delayed retry schedules
            ensureWebViewCacheDirectoriesExist();
            ensureWebViewCacheDirectoriesExistWithDelay(100);
            ensureWebViewCacheDirectoriesExistWithDelay(300);
            ensureWebViewCacheDirectoriesExistWithDelay(600);
            ensureWebViewCacheDirectoriesExistWithDelay(1200);
            ensureWebViewCacheDirectoriesExistWithDelay(2500);
            ensureWebViewCacheDirectoriesExistWithDelay(5000);
            
            updateCacheModeBasedOnNetwork();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (mLayoutLoadingOverlay != null) {
                mLayoutLoadingOverlay.setVisibility(View.GONE);
            }
            
            // Ensure directories are intact after loading finishes
            ensureWebViewCacheDirectoriesExist();
            ensureWebViewCacheDirectoriesExistWithDelay(100);
            ensureWebViewCacheDirectoriesExistWithDelay(500);
            
            // Extra layer of security: Inject CSS to completely lock down selection & copying capabilities
            String selectNoneCss = "* { -webkit-user-select: none !important; " +
                    "-moz-user-select: none !important; " +
                    "-ms-user-select: none !important; " +
                    "user-select: none !important; " +
                    "-webkit-touch-callout: none !important; }";
            
            view.loadUrl("javascript:(function() { " +
                    "var parent = document.getElementsByTagName('head').item(0);" +
                    "var style = document.createElement('style');" +
                    "style.type = 'text/css';" +
                    "style.innerHTML = '" + selectNoneCss + "';" +
                    "parent.appendChild(style);" +
                    "})()");
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            // Only show offline screen for critical network connection failures, down states, DNS, or timeouts
            if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT || errorCode == ERROR_TIMEOUT) {
                showOfflineScreen();
            }
        }

        @Override
        public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (request.isForMainFrame()) {
                    int errorCode = error.getErrorCode();
                    // Prevent minor subresource load failures (e.g. ad, track, css files) from falsely triggering offline screen
                    if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT || errorCode == ERROR_TIMEOUT) {
                        showOfflineScreen();
                    }
                }
            } else {
                showOfflineScreen();
            }
        }
    }

    // --- Dynamic IPTV / M3U Parser and View Adapters ---

    private boolean isDirectVideoStream(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mpd") 
            || lower.contains(".mkv") || lower.contains(".webm") || lower.contains("/m3u8") 
            || lower.contains("m3u8?");
    }

    private void playDirectStream(String streamUrl) {
        String htmlContent = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">\n" +
                "    <style>\n" +
                "        body, html { margin:0; padding:0; width:100%; height:100%; background-color:#121212; overflow:hidden; display:flex; justify-content:center; align-items:center; }\n" +
                "        video { width:100%; height:100%; object-fit: contain; }\n" +
                "        #loading { position:absolute; color:#00E5FF; font-family:sans-serif; font-size:16px; font-weight:bold; letter-spacing:1px; }\n" +
                "    </style>\n" +
                "    <script src=\"https://cdn.jsdelivr.net/npm/hls.js@latest\"></script>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div id=\"loading\">Buffering Live Stream...</div>\n" +
                "    <video id=\"video\" controls autoplay playsinline></video>\n" +
                "    <script>\n" +
                "        var video = document.getElementById('video');\n" +
                "        var streamUrl = \"" + streamUrl + "\";\n" +
                "        var loading = document.getElementById('loading');\n" +
                "\n" +
                "        video.addEventListener('playing', function() {\n" +
                "            loading.style.display = 'none';\n" +
                "        });\n" +
                "\n" +
                "        if (Hls.isSupported()) {\n" +
                "            var hls = new Hls({\n" +
                "                maxMaxBufferLength: 10,\n" +
                "                enableWorker: true,\n" +
                "                lowLatencyMode: true\n" +
                "            });\n" +
                "            hls.loadSource(streamUrl);\n" +
                "            hls.attachMedia(video);\n" +
                "            hls.on(Hls.Events.MANIFEST_PARSED, function() {\n" +
                "                video.play().catch(function() {\n" +
                "                    var playBtn = document.createElement('button');\n" +
                "                    playBtn.innerHTML = 'CLICK TO PLAY';\n" +
                "                    playBtn.style = 'position:absolute; padding:15px 30px; font-size:18px; color:white; background:#1A73E8; border:none; border-radius:5px; cursor:pointer; font-weight:bold;';\n" +
                "                    playBtn.onclick = function() { video.play(); playBtn.remove(); };\n" +
                "                    document.body.appendChild(playBtn);\n" +
                "                });\n" +
                "            });\n" +
                "            hls.on(Hls.Events.ERROR, function(event, data) {\n" +
                "                if (data.fatal) {\n" +
                "                    switch(data.type) {\n" +
                "                        case Hls.ErrorTypes.NETWORK_ERROR:\n" +
                "                            hls.startLoad();\n" +
                "                            break;\n" +
                "                        case Hls.ErrorTypes.MEDIA_ERROR:\n" +
                "                            hls.recoverMediaError();\n" +
                "                            break;\n" +
                "                        default:\n" +
                "                            break;\n" +
                "                    } \n" +
                "                }\n" +
                "            });\n" +
                "        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {\n" +
                "            video.src = streamUrl;\n" +
                "            video.addEventListener('canplay', function() {\n" +
                "                video.play();\n" +
                "            });\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
                
        mWebView.loadDataWithBaseURL("https://localhost", htmlContent, "text/html", "UTF-8", null);
    }

    private void loadLocalM3uFile(final android.net.Uri fileUri) {
        if (mLayoutLoadingOverlay != null) {
            mLayoutLoadingOverlay.setVisibility(View.VISIBLE);
        }
        
        mTvLoadingText.setText("PARSING LOCAL FILE...");
        mTvLoadingSubtext.setText("Reading M3U playlist content...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.InputStream is = getContentResolver().openInputStream(fileUri);
                    if (is == null) {
                        onPlaylistLoadFailed("Unable to open the selected file.");
                        return;
                    }
                    final java.util.List<IptvChannel> parsedList = parseM3u(is);
                    is.close();
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (parsedList.isEmpty()) {
                                onPlaylistLoadFailed("Empty M3U file or wrong formatting pattern.");
                                return;
                            }
                            
                            mAllChannels.clear();
                            mAllChannels.addAll(parsedList);
                            
                            mCategories.clear();
                            mCategories.add("ALL CHANNELS");
                            
                            java.util.Set<String> uniqueGroups = new java.util.LinkedHashSet<>();
                            for (IptvChannel ch : mAllChannels) {
                                if (ch.group != null && !ch.group.trim().isEmpty()) {
                                    uniqueGroups.add(ch.group);
                                }
                            }
                            mCategories.addAll(uniqueGroups);
                            
                            mSelectedCategory = "ALL CHANNELS";
                            if (mTvActiveStatus != null) {
                                mTvActiveStatus.setText("Loaded: " + mAllChannels.size() + " Streams (Local)");
                            }
                            
                            mCategoryAdapter = new CategoryAdapter(mCategories);
                            mListCategories.setAdapter(mCategoryAdapter);
                            mCategoryAdapter.setSelectedPosition(0);
                            
                            filterAndDisplayChannels();
                            
                            if (mLayoutLoadingOverlay != null) {
                                mLayoutLoadingOverlay.setVisibility(View.GONE);
                            }
                        }
                    });
                } catch (final Exception e) {
                    onPlaylistLoadFailed("File Read Error: " + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri != null) {
                loadLocalM3uFile(uri);
            }
        }
    }

    private void loadM3uPlaylist(final String playlistUrl) {
        if (!isNetworkAvailable()) {
            showOfflineScreen();
            return;
        }

        if (mLayoutLoadingOverlay != null) {
            mLayoutLoadingOverlay.setVisibility(View.VISIBLE);
        }
        
        mTvLoadingText.setText("DOWNLOADING PLAYLIST...");
        mTvLoadingSubtext.setText("Connecting safely to stream directory...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL url = new java.net.URL(playlistUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        java.io.InputStream is = conn.getInputStream();
                        final java.util.List<IptvChannel> parsedList = parseM3u(is);
                        is.close();
                        conn.disconnect();
                        
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (parsedList.isEmpty()) {
                                    onPlaylistLoadFailed("Empty M3U file or wrong formatting pattern.");
                                    return;
                                }
                                
                                mAllChannels.clear();
                                mAllChannels.addAll(parsedList);
                                
                                mCategories.clear();
                                mCategories.add("ALL CHANNELS");
                                
                                java.util.Set<String> uniqueGroups = new java.util.LinkedHashSet<>();
                                for (IptvChannel ch : mAllChannels) {
                                    if (ch.group != null && !ch.group.trim().isEmpty()) {
                                        uniqueGroups.add(ch.group);
                                    }
                                }
                                mCategories.addAll(uniqueGroups);
                                
                                mSelectedCategory = "ALL CHANNELS";
                                if (mTvActiveStatus != null) {
                                    mTvActiveStatus.setText("Loaded: " + mAllChannels.size() + " Streams");
                                }
                                
                                mCategoryAdapter = new CategoryAdapter(mCategories);
                                mListCategories.setAdapter(mCategoryAdapter);
                                mCategoryAdapter.setSelectedPosition(0);
                                
                                filterAndDisplayChannels();
                                
                                if (mLayoutLoadingOverlay != null) {
                                    mLayoutLoadingOverlay.setVisibility(View.GONE);
                                }
                            }
                        });
                    } else {
                        conn.disconnect();
                        onPlaylistLoadFailed("Server rejected search code: " + responseCode);
                    }
                } catch (final Exception e) {
                    onPlaylistLoadFailed("Gateway Timeout: " + e.getMessage());
                }
            }
        }).start();
    }

    private void onPlaylistLoadFailed(final String errorMsg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mLayoutLoadingOverlay != null) {
                    mLayoutLoadingOverlay.setVisibility(View.GONE);
                }
                new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("Playlist Engine Error")
                        .setMessage(errorMsg + "\n\nPlease try selecting another preset or verify the connection.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    private void filterAndDisplayChannels() {
        java.util.List<IptvChannel> filtered = new java.util.ArrayList<>();
        String query = mSearchText.toLowerCase().trim();
        
        for (IptvChannel ch : mAllChannels) {
            boolean matchesCategory = mSelectedCategory.equals("ALL CHANNELS") || ch.group.equals(mSelectedCategory);
            boolean matchesSearch = query.isEmpty() || ch.name.toLowerCase().contains(query) || ch.group.toLowerCase().contains(query);
            
            if (matchesCategory && matchesSearch) {
                filtered.add(ch);
            }
        }
        
        mChannelAdapter = new ChannelAdapter(filtered);
        mListChannels.setAdapter(mChannelAdapter);
    }

    private java.util.List<IptvChannel> parseM3u(java.io.InputStream inputStream) throws java.io.IOException {
        java.util.List<IptvChannel> channels = new java.util.ArrayList<>();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, "UTF-8"));
        String line;
        String lastExtInf = null;
        
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            if (line.startsWith("#EXTINF:")) {
                lastExtInf = line;
            } else if (!line.startsWith("#")) {
                // Address stream line
                if (lastExtInf != null) {
                    String name = "";
                    int commaIndex = lastExtInf.lastIndexOf(',');
                    if (commaIndex != -1) {
                        name = lastExtInf.substring(commaIndex + 1).trim();
                    }
                    if (name.isEmpty()) {
                        name = "Station " + (channels.size() + 1);
                    }
                    
                    String logoUrl = parseAttribute(lastExtInf, "tvg-logo=");
                    if (logoUrl.isEmpty()) {
                        logoUrl = parseAttribute(lastExtInf, "logo=");
                    }
                    
                    String group = parseAttribute(lastExtInf, "group-title=");
                    if (group.isEmpty()) {
                        group = "General";
                    }
                    
                    channels.add(new IptvChannel(name, line, logoUrl, group));
                    lastExtInf = null;
                } else {
                    channels.add(new IptvChannel("Station " + (channels.size() + 1), line, "", "General"));
                }
            }
        }
        reader.close();
        return channels;
    }

    private String parseAttribute(String info, String key) {
        int keyIdx = info.indexOf(key);
        if (keyIdx == -1) return "";
        
        int startIdx = keyIdx + key.length();
        if (startIdx >= info.length()) return "";
        
        char quoteChar = info.charAt(startIdx);
        if (quoteChar == '"' || quoteChar == '\'') {
            startIdx++;
            int endIdx = info.indexOf(quoteChar, startIdx);
            if (endIdx != -1) {
                return info.substring(startIdx, endIdx).trim();
            }
        } else {
            int endIdx = info.indexOf(' ', startIdx);
            if (endIdx != -1) {
                return info.substring(startIdx, endIdx).trim();
            } else {
                return info.substring(startIdx).trim();
            }
        }
        return "";
    }

    // Custom Adapters for high visual feedback & performance

    private class CategoryAdapter extends android.widget.BaseAdapter {
        private java.util.List<String> mItems;
        private int mSelectedPos = 0;

        public CategoryAdapter(java.util.List<String> items) {
            this.mItems = items;
        }

        public void setSelectedPosition(int position) {
            mSelectedPos = position;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return mItems.size(); }
        @Override
        public Object getItem(int position) { return mItems.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            android.widget.TextView textView;
            if (convertView == null) {
                textView = new android.widget.TextView(MainActivity.this);
                textView.setLayoutParams(new android.widget.ListView.LayoutParams(
                        android.widget.ListView.LayoutParams.MATCH_PARENT, 
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                textView.setPadding(24, 20, 24, 20);
                textView.setTextSize(13);
                textView.setFocusable(false);
                textView.setSingleLine(true);
                textView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            } else {
                textView = (android.widget.TextView) convertView;
            }

            String item = mItems.get(position);
            textView.setText(item.toUpperCase());

            if (position == mSelectedPos) {
                textView.setBackgroundColor(0xFF1557B0);
                textView.setTextColor(0xFFFFFFFF);
                textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else {
                textView.setBackgroundColor(0x00000000);
                textView.setTextColor(0xFFAAAAAA);
                textView.setTypeface(android.graphics.Typeface.DEFAULT);
            }
            return textView;
        }
    }

    private class ChannelAdapter extends android.widget.BaseAdapter {
        private java.util.List<IptvChannel> mItems;

        public ChannelAdapter(java.util.List<IptvChannel> items) {
            this.mItems = items;
        }

        @Override
        public int getCount() { return mItems.size(); }
        @Override
        public Object getItem(int position) { return mItems.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            android.widget.LinearLayout layout;
            if (convertView == null) {
                layout = new android.widget.LinearLayout(MainActivity.this);
                layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                layout.setPadding(24, 20, 24, 20);
                layout.setLayoutParams(new android.widget.ListView.LayoutParams(
                        android.widget.ListView.LayoutParams.MATCH_PARENT, 
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                android.widget.TextView nameView = new android.widget.TextView(MainActivity.this);
                nameView.setTextColor(0xFFFFFFFF);
                nameView.setTextSize(14);
                nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);

                android.widget.TextView groupView = new android.widget.TextView(MainActivity.this);
                groupView.setTextColor(0xFF888888);
                groupView.setTextSize(11);
                groupView.setSingleLine(true);
                groupView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                groupView.setPadding(0, 4, 0, 0);

                layout.addView(nameView);
                layout.addView(groupView);
                layout.setTag(new View[] { nameView, groupView });
            } else {
                layout = (android.widget.LinearLayout) convertView;
            }

            View[] views = (View[]) layout.getTag();
            android.widget.TextView nameView = (android.widget.TextView) views[0];
            android.widget.TextView groupView = (android.widget.TextView) views[1];

            IptvChannel channel = mItems.get(position);
            nameView.setText("📺  " + channel.name);
            groupView.setText(channel.group.toUpperCase() + "  •  " + channel.url);

            return layout;
        }
    }
}
