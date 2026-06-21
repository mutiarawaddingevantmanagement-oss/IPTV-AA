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
        public boolean isFavorite = false;

        public IptvChannel(String name, String url, String logoUrl, String group) {
            this.name = name != null ? name.trim() : "Unknown Channel";
            this.url = url != null ? url.trim() : "";
            this.logoUrl = logoUrl != null ? logoUrl.trim() : "";
            this.group = (group != null && !group.trim().isEmpty()) ? group.trim() : "General";
        }
    }

    private IptvDatabaseHelper mDbHelper;

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
    private String mSelectedChannelName = "";

    private LinearLayout mLayoutLanding;
    private RelativeLayout mLayoutLoadingOverlay;
    private LinearLayout mLayoutOffline;
    private WebView mWebView;
    private Button mBtnUploadFile;
    private Button mBtnRetry;

    // Settings UI elements
    private Button mBtnOpenSettings;
    private LinearLayout mLayoutSettings;
    private android.widget.EditText mSettingsPlaylistUrl;
    private Button mBtnSettingsSavePlaylist;
    private android.widget.RadioGroup mSettingsBufferGroup;
    private android.widget.RadioButton mRadioBufferLow;
    private android.widget.RadioButton mRadioBufferStd;
    private android.widget.RadioButton mRadioBufferSafe;
    private Button mBtnSettingsClearCache;
    private Button mBtnSettingsResetDb;
    private android.widget.TextView mSettingsAppVersion;
    private Button mBtnSettingsClose;

    // Connectivity monitoring
    private BroadcastReceiver mNetworkReceiver;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private boolean mIsConnected = true;

    // Background thread control
    private volatile boolean mDestroyed = false;
    private boolean mDirectoriesCreated = false;
    private AsyncImageLoader mImageLoader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Pre-create WebView/Chromium Code Cache directories as early as possible with global permissions
        ensureWebViewCacheDirectoriesExist();
        ensureWebViewCacheDirectoriesExistWithDelay(1500);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mImageLoader = new AsyncImageLoader();
        
        // High-performance compatibility & hardware check
        checkHardwareVideoCapabilities();
        
        // Prevent Smart TV dimming/sleeping during video streams
        if (getWindow() != null) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        
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
                if (mListChannels != null) {
                    mListChannels.setSelection(0);
                }
            }
        });

        // Channel list selection listener -> opens web player or HlsJS embedded container
        mListChannels.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                IptvChannel ch = (IptvChannel) mChannelAdapter.getItem(position);
                if (ch != null && ch.url != null && !ch.url.isEmpty()) {
                    mSelectedChannelUrl = ch.url;
                    mSelectedChannelName = ch.name != null ? ch.name : "";
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

        // Set focus on the search bar initially for superior TV remote and mobile touch startup flow
        if (mEditSearchChannel != null) {
            mEditSearchChannel.requestFocus();
        }

        // Setup connectivity monitoring broadcast receiver
        setupNetworkReceiver();

        mEditM3uUrl.setText("");

        // Initialize SQLite database helper
        mDbHelper = new IptvDatabaseHelper(this);

        // Setup premium remote controller friendly keyboard grid
        setupRemoteKeyboard();

        // Initialize high-fidelity Settings Screen panel and preference cache
        initSettingsScreen();

        // Connect long-press listener to toggle favorite station
        mListChannels.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                IptvChannel ch = (IptvChannel) mChannelAdapter.getItem(position);
                if (ch != null) {
                    toggleFavorite(ch);
                    return true;
                }
                return false;
            }
        });

        // Load cached channels from local database
        loadChannelsFromDb();
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
        mWebView.addJavascriptInterface(new WebAppInterface(), "Android");
    }

    private void openIptvStream() {
        if (!isNetworkAvailable()) {
            showOfflineScreen();
            return;
        }

        // Halts any ongoing load and blanks the media player completely to release codec/network resources
        mWebView.stopLoading();
        mWebView.loadUrl("about:blank");

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

        // Load the stream after a very brief delay to allow the blanking of previous resources to complete
        mWebView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isDirectVideoStream(mSelectedChannelUrl)) {
                    playDirectStream(mSelectedChannelUrl);
                } else {
                    mWebView.loadUrl(mSelectedChannelUrl);
                }
                mWebView.requestFocus();
            }
        }, 50);
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
        if (mImageLoader != null) {
            mImageLoader.shutdown();
        }
        if (mWebView != null) {
            try {
                android.view.ViewGroup parent = (android.view.ViewGroup) mWebView.getParent();
                if (parent != null) {
                    parent.removeView(mWebView);
                }
                mWebView.removeAllViews();
                mWebView.destroy();
                mWebView = null;
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                onBackPressed();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (mLayoutSettings != null && mLayoutSettings.getVisibility() == View.VISIBLE) {
            mLayoutSettings.setVisibility(View.GONE);
            if (mBtnOpenSettings != null) {
                mBtnOpenSettings.requestFocus();
            }
        } else if (mWebView.getVisibility() == View.VISIBLE && mWebView.canGoBack()) {
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
        if (mDirectoriesCreated) {
            return;
        }
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
                
                mDirectoriesCreated = true;
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

    public class WebAppInterface {
        @android.webkit.JavascriptInterface
        public void closePlayer() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    onBackPressed();
                }
            });
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
        String channelNameEscaped = mSelectedChannelName.replace("\"", "\\\"");
        String streamUrlEscaped = streamUrl.replace("\"", "\\\"");

        // Detect old Android 5.0/5.1 systems for custom video rendering parameters
        boolean isLegacyLollipop = Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1;
        long maxBufferSize = isLegacyLollipop ? 4194304 : 15728640; // Safe 4MB for Lollipop vs 15MB ordinarily
        int maxMaxBufferLen = isLegacyLollipop ? Math.min(6, getBufferSecondsSetting()) : getBufferSecondsSetting();
        int maxBufferLen = isLegacyLollipop ? Math.min(5, maxMaxBufferLen) : Math.min(15, maxMaxBufferLen);
        String enableWorkerJsValue = isLegacyLollipop ? "false" : "true";

        String htmlContent = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">\n" +
                "    <style>\n" +
                "        body, html { margin:0; padding:0; width:100%; height:100%; background-color:#121212; overflow:hidden; font-family: 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }\n" +
                "        video { width:100%; height:100%; object-fit: contain; position: absolute; top:0; left:0; z-index: 1; }\n" +
                "        \n" +
                "        /* Premium Loader */\n" +
                "        #loader-container {\n" +
                "            position: absolute;\n" +
                "            top: 0; left: 0; width: 100%; height: 100%;\n" +
                "            display: flex; flex-direction: column; justify-content: center; align-items: center;\n" +
                "            background-color: #121212;\n" +
                "            z-index: 10;\n" +
                "            transition: opacity 0.5s ease;\n" +
                "        }\n" +
                "        .spinner {\n" +
                "            width: 50px; height: 50px;\n" +
                "            border: 5px solid rgba(0, 229, 255, 0.1);\n" +
                "            border-top: 5px solid #00E5FF;\n" +
                "            border-radius: 50%;\n" +
                "            animation: spin 1s linear infinite;\n" +
                "        }\n" +
                "        @keyframes spin {\n" +
                "            0% { transform: rotate(0deg); }\n" +
                "            100% { transform: rotate(360deg); }\n" +
                "        }\n" +
                "        #loading-text {\n" +
                "            margin-top: 15px;\n" +
                "            color: #E0E0E0;\n" +
                "            font-size: 16px;\n" +
                "            font-weight: 500;\n" +
                "            letter-spacing: 1.2px;\n" +
                "            text-shadow: 0 2px 4px rgba(0,0,0,0.5);\n" +
                "        }\n" +
                "        \n" +
                "        /* Modern Smart TV HUD */\n" +
                "        #hud-container {\n" +
                "            position: absolute;\n" +
                "            top: 0; left: 0; width: 100%; height: 100%;\n" +
                "            z-index: 5;\n" +
                "            pointer-events: none;\n" +
                "            transition: opacity 0.4s cubic-bezier(0.25, 0.8, 0.25, 1);\n" +
                "            opacity: 1;\n" +
                "        }\n" +
                "        .hud-header {\n" +
                "            position: absolute;\n" +
                "            top: 0; left: 0; right: 0;\n" +
                "            background: linear-gradient(to bottom, rgba(0,0,0,0.85) 0%, rgba(0,0,0,0.4) 60%, rgba(0,0,0,0) 100%);\n" +
                "            padding: 30px 40px;\n" +
                "            display: flex;\n" +
                "            align-items: center;\n" +
                "            justify-content: space-between;\n" +
                "        }\n" +
                "        .channel-info {\n" +
                "            display: flex;\n" +
                "            align-items: center;\n" +
                "            gap: 15px;\n" +
                "        }\n" +
                "        .live-dot {\n" +
                "            width: 10px; height: 10px;\n" +
                "            background-color: #FF1744;\n" +
                "            border-radius: 50%;\n" +
                "            box-shadow: 0 0 8px #FF1744;\n" +
                "            animation: pulse 1.5s infinite alternate;\n" +
                "        }\n" +
                "        @keyframes pulse {\n" +
                "            0% { transform: scale(0.9); opacity: 0.6; }\n" +
                "            100% { transform: scale(1.1); opacity: 1; }\n" +
                "        }\n" +
                "        .channel-title {\n" +
                "            color: #FFFFFF;\n" +
                "            font-size: 24px;\n" +
                "            font-weight: 600;\n" +
                "            text-shadow: 0 2px 4px rgba(0,0,0,0.8);\n" +
                "        }\n" +
                "        .live-badge {\n" +
                "            background-color: #FF1744;\n" +
                "            color: white;\n" +
                "            font-size: 12px;\n" +
                "            font-weight: bold;\n" +
                "            padding: 2px 8px;\n" +
                "            border-radius: 4px;\n" +
                "            text-transform: uppercase;\n" +
                "            letter-spacing: 1px;\n" +
                "        }\n" +
                "        .hud-instructions {\n" +
                "            position: absolute;\n" +
                "            bottom: 40px; left: 40px; right: 40px;\n" +
                "            background: linear-gradient(to top, rgba(0,0,0,0.85) 0%, rgba(0,0,0,0.4) 60%, rgba(0,0,0,0) 100%);\n" +
                "            padding: 20px 40px;\n" +
                "            display: flex;\n" +
                "            justify-content: space-between;\n" +
                "            align-items: center;\n" +
                "            border-top: 1px solid rgba(255,255,255,0.1);\n" +
                "        }\n" +
                "        .instructions-text {\n" +
                "            color: #B0BEC5;\n" +
                "            font-size: 14px;\n" +
                "            letter-spacing: 0.5px;\n" +
                "        }\n" +
                "        \n" +
                "        /* Action Play/Pause Pulse HUD */\n" +
                "        #action-overlay {\n" +
                "            position: absolute;\n" +
                "            display: flex;\n" +
                "            justify-content: center;\n" +
                "            align-items: center;\n" +
                "            background: rgba(0,0,0,0.6);\n" +
                "            border-radius: 50%;\n" +
                "            width: 80px; height: 80px;\n" +
                "            top: 50%; left: 50%;\n" +
                "            transform: translate(-50%, -50%);\n" +
                "            z-index: 6;\n" +
                "            opacity: 0;\n" +
                "            pointer-events: none;\n" +
                "            transition: opacity 0.3s ease, transform 0.3s ease;\n" +
                "        }\n" +
                "        .action-icon {\n" +
                "            color: #00E5FF;\n" +
                "            font-size: 36px;\n" +
                "        }\n" +
                "    </style>\n" +
                "    <script src=\"https://cdn.jsdelivr.net/npm/hls.js@latest\"></script>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <!-- Premium Loader -->\n" +
                "    <div id=\"loader-container\">\n" +
                "        <div class=\"spinner\"></div>\n" +
                "        <div id=\"loading-text\">BUFFERING STREAM...</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- Video Element -->\n" +
                "    <video id=\"video\" autoplay playsinline></video>\n" +
                "\n" +
                "    <!-- TV HUD -->\n" +
                "    <div id=\"hud-container\">\n" +
                "        <div class=\"hud-header\">\n" +
                "            <div class=\"channel-info\">\n" +
                "                <div class=\"live-dot\"></div>\n" +
                "                <div class=\"channel-title\" id=\"hud-channel-title\">Channel</div>\n" +
                "                <div class=\"live-badge\">Live</div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "        <div class=\"hud-instructions\">\n" +
                "            <div class=\"instructions-text\">Press [OK/ENTER] to Play/Pause &nbsp;&bull;&nbsp; Press [BACK/ESC] to return to Channels</div>\n" +
                "            <div class=\"instructions-text\" style=\"color: #00E5FF; font-weight: 500;\">Android TV Web Player</div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- Click/Action HUD visual feedback -->\n" +
                "    <div id=\"action-overlay\">\n" +
                "        <span class=\"action-icon\" id=\"action-text\">▶</span>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        var video = document.getElementById('video');\n" +
                "        var loader = document.getElementById('loader-container');\n" +
                "        var hud = document.getElementById('hud-container');\n" +
                "        var actionOverlay = document.getElementById('action-overlay');\n" +
                "        var actionText = document.getElementById('action-text');\n" +
                "        var titleEl = document.getElementById('hud-channel-title');\n" +
                "        \n" +
                "        var streamUrl = \"" + streamUrlEscaped + "\";\n" +
                "        var channelName = \"" + channelNameEscaped + "\";\n" +
                "        \n" +
                "        if (channelName) {\n" +
                "            titleEl.textContent = channelName;\n" +
                "        }\n" +
                "\n" +
                "        var hudTimeout;\n" +
                "        function showHUD() {\n" +
                "            hud.style.opacity = '1';\n" +
                "            clearTimeout(hudTimeout);\n" +
                "            if (!video.paused) {\n" +
                "                hudTimeout = setTimeout(hideHUD, 3500);\n" +
                "            }\n" +
                "        }\n" +
                "        function hideHUD() {\n" +
                "            if (!video.paused) {\n" +
                "                hud.style.opacity = '0';\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        // Initially show HUD\n" +
                "        showHUD();\n" +
                "        \n" +
                "        // Listen to events to hide/show loading indicator and HUD\n" +
                "        video.addEventListener('playing', function() {\n" +
                "            loader.style.opacity = '0';\n" +
                "            setTimeout(function() { loader.style.display = 'none'; }, 500);\n" +
                "            showHUD();\n" +
                "        });\n" +
                "        \n" +
                "        video.addEventListener('waiting', function() {\n" +
                "            loader.style.display = 'flex';\n" +
                "            loader.style.opacity = '1';\n" +
                "        });\n" +
                "\n" +
                "        video.addEventListener('play', function() {\n" +
                "            flashAction('▶');\n" +
                "            showHUD();\n" +
                "        });\n" +
                "\n" +
                "        video.addEventListener('pause', function() {\n" +
                "            flashAction('❚❚');\n" +
                "            showHUD();\n" +
                "        });\n" +
                "\n" +
                "        function flashAction(symbol) {\n" +
                "            actionText.textContent = symbol;\n" +
                "            actionOverlay.style.opacity = '1';\n" +
                "            actionOverlay.style.transform = 'translate(-50%, -50%) scale(1.1)';\n" +
                "            setTimeout(function() {\n" +
                "                actionOverlay.style.opacity = '0';\n" +
                "                actionOverlay.style.transform = 'translate(-50%, -50%) scale(1.0)';\n" +
                "            }, 800);\n" +
                "        }\n" +
                "\n" +
                "        // Toggle play/pause function\n" +
                "        function togglePlay() {\n" +
                "            if (video.paused) {\n" +
                "                video.play().catch(function(e){ console.error(\"Playback failed: \", e); });\n" +
                "            } else {\n" +
                "                video.pause();\n" +
                "            }\n" +
                "            showHUD();\n" +
                "        }\n" +
                "\n" +
                "        // Intercept user gestures to toggle play/pause & show/hide controls\n" +
                "        document.body.addEventListener('click', function() {\n" +
                "            togglePlay();\n" +
                "        });\n" +
                "\n" +
                "        // Keydown processing mapped elegantly to remote controls\n" +
                "        document.addEventListener('keydown', function(event) {\n" +
                "            showHUD();\n" +
                "            switch (event.keyCode) {\n" +
                "                case 13: // Enter/OK\n" +
                "                case 32: // Space\n" +
                "                    togglePlay();\n" +
                "                    event.preventDefault();\n" +
                "                    break;\n" +
                "                case 8:  // Backspace (Android TV Back Remote button fallback)\n" +
                "                case 27: // Escape\n" +
                "                case 10009: // Tizen Back\n" +
                "                    event.preventDefault();\n" +
                "                    if (window.Android && typeof window.Android.closePlayer === 'function') {\n" +
                "                        window.Android.closePlayer();\n" +
                "                    } else {\n" +
                "                        history.back();\n" +
                "                    }\n" +
                "                    break;\n" +
                "                case 37: // ArrowLeft\n" +
                "                    video.currentTime = Math.max(0, video.currentTime - 10);\n" +
                "                    flashAction('◀◀');\n" +
                "                    event.preventDefault();\n" +
                "                    break;\n" +
                "                case 39: // ArrowRight\n" +
                "                    video.currentTime = Math.min(video.duration || 999999, video.currentTime + 10);\n" +
                "                    flashAction('▶▶');\n" +
                "                    event.preventDefault();\n" +
                "                    break;\n" +
                "                case 38: // ArrowUp\n" +
                "                    video.volume = Math.min(1, video.volume + 0.1);\n" +
                "                    event.preventDefault();\n" +
                "                    break;\n" +
                "                case 40: // ArrowDown\n" +
                "                    video.volume = Math.max(0, video.volume - 0.1);\n" +
                "                    event.preventDefault();\n" +
                "                    break;\n" +
                "            } \n" +
                "        });\n" +
                "\n" +
                "        // Playback controller safe-guard targeting both TV browser engines and legacy mobile webviews\n" +
                "        function startPlayback() {\n" +
                "            video.muted = false;\n" +
                "            var playPromise = video.play();\n" +
                "            if (playPromise !== undefined && typeof playPromise.then === 'function') {\n" +
                "                playPromise.then(function() {\n" +
                "                    // Successfully started playing unmuted\n" +
                "                }).catch(function(error) {\n" +
                "                    console.log('Playback unmuted failed, trying muted:', error);\n" +
                "                    video.muted = true;\n" +
                "                    var retryPromise = video.play();\n" +
                "                    if (retryPromise !== undefined && typeof retryPromise.then === 'function') {\n" +
                "                        retryPromise.catch(function() {\n" +
                "                            showPlayBtnFallback();\n" +
                "                        });\n" +
                "                    }\n" +
                "                });\n" +
                "            } else {\n" +
                "                // Legacy webviews with no play promise returned, unmute automatically\n" +
                "                video.muted = false;\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        function showPlayBtnFallback() {\n" +
                "            if (document.getElementById('manual-play-btn')) return;\n" +
                "            var playBtn = document.createElement('button');\n" +
                "            playBtn.id = 'manual-play-btn';\n" +
                "            playBtn.innerHTML = 'TAP TO PLAY';\n" +
                "            playBtn.style = 'position:absolute; bottom:120px; left:50%; transform:translateX(-50%); padding:15px 30px; font-size:16px; color:white; background:#00E5FF; border:none; border-radius:30px; cursor:pointer; font-weight:bold; letter-spacing:1px; z-index:100; box-shadow:0 0 15px rgba(0,229,255,0.4);';\n" +
                "            playBtn.onclick = function(e) {\n" +
                "                e.stopPropagation();\n" +
                "                video.muted = false;\n" +
                "                video.play();\n" +
                "                playBtn.remove();\n" +
                "            };\n" +
                "            document.body.appendChild(playBtn);\n" +
                "        }\n" +
                "\n" +
                "        // HlsJS loading routine\n" +
                "        if (Hls.isSupported()) {\n" +
                "            var hls = new Hls({\n" +
                "                maxMaxBufferLength: " + maxMaxBufferLen + ",\n" +
                "                maxBufferLength: " + maxBufferLen + ",\n" +
                "                backBufferLength: 15,\n" +
                "                maxBufferSize: " + maxBufferSize + ",\n" +
                "                maxBufferHole: 2,\n" +
                "                enableWorker: " + enableWorkerJsValue + ",\n" +
                "                lowLatencyMode: true,\n" +
                "                liveDurationInfinite: true\n" +
                "            });\n" +
                "            hls.loadSource(streamUrl);\n" +
                "            hls.attachMedia(video);\n" +
                "            hls.on(Hls.Events.MANIFEST_PARSED, function() {\n" +
                "                startPlayback();\n" +
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
                "                startPlayback();\n" +
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
                            mCategories.add("★ FAVORITES");
                            
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

                            // Cache the uploaded local channel list inside SQL database
                            saveChannelsToDb(mAllChannels, true);
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
                                mCategories.add("★ FAVORITES");
                                
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

                                // Cache downloaded channels into SQLite
                                saveChannelsToDb(mAllChannels, true);
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
                if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                    return; // Prevent window / BadTokenException crashes on legacy TV boxes
                }
                if (mLayoutLoadingOverlay != null) {
                    mLayoutLoadingOverlay.setVisibility(View.GONE);
                }
                try {
                    new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                            .setTitle("Playlist Engine Error")
                            .setMessage(errorMsg + "\n\nPlease try selecting another preset or verify the connection.")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {}
            }
        });
    }

    private void setupRemoteKeyboard() {
        android.widget.LinearLayout keyboardContainer = findViewById(R.id.layout_tv_keyboard);
        if (keyboardContainer == null) return;

        String[][] keys = {
            {"A", "B", "C", "D", "E", "F"},
            {"G", "H", "I", "J", "K", "L"},
            {"M", "N", "O", "P", "Q", "R"},
            {"S", "T", "U", "V", "W", "X"},
            {"Y", "Z", "1", "2", "3", "4"},
            {"5", "6", "7", "8", "9", "0"},
            {"-", ".", "_", "SPC", "DEL", "CLR"}
        };

        keyboardContainer.removeAllViews();
        
        // Add a title
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("REMOTE SEARCH KEYPAD");
        title.setTextColor(0xFF00E5FF);
        title.setTextSize(10);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams titleParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 4, 0, 8);
        keyboardContainer.addView(title, titleParams);

        for (int r = 0; r < keys.length; r++) {
            android.widget.LinearLayout rowLayout = new android.widget.LinearLayout(this);
            rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.LinearLayout.LayoutParams rowParams = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
            rowParams.setMargins(0, 1, 0, 1);
            
            String[] rowKeys = keys[r];
            for (int c = 0; c < rowKeys.length; c++) {
                final String keyValue = rowKeys[c];
                android.widget.Button btn = new android.widget.Button(this);
                btn.setText(keyValue);
                btn.setTextColor(0xFFFFFFFF);
                btn.setTextSize(11);
                btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                btn.setFocusable(true);
                btn.setClickable(true);
                btn.setBackgroundResource(R.drawable.btn_selector);
                btn.setPadding(0, 0, 0, 0);
                
                android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
                btnParams.setMargins(1, 1, 1, 1);
                
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String currentText = mEditSearchChannel.getText().toString();
                        if (keyValue.equals("SPC")) {
                            mEditSearchChannel.setText(currentText + " ");
                        } else if (keyValue.equals("DEL")) {
                            if (currentText.length() > 0) {
                                mEditSearchChannel.setText(currentText.substring(0, currentText.length() - 1));
                            }
                        } else if (keyValue.equals("CLR")) {
                            mEditSearchChannel.setText("");
                        } else {
                            mEditSearchChannel.setText(currentText + keyValue);
                        }
                        mEditSearchChannel.setSelection(mEditSearchChannel.getText().length());
                    }
                });
                
                rowLayout.addView(btn, btnParams);
            }
            keyboardContainer.addView(rowLayout, rowParams);
        }
    }

    private java.util.List<IptvChannel> getDefaultPresetChannels() {
        java.util.List<IptvChannel> presets = new java.util.ArrayList<>();
        presets.add(new IptvChannel("NASA TV HD", "https://ntv1.nasatv.net/hls/ntv1_hq.m3u8", "", "Science & Space"));
        presets.add(new IptvChannel("Deutsche Welle English", "https://dwstream4-lh.akamaihd.net/i/dwen_01@318779/master.m3u8", "", "News"));
        presets.add(new IptvChannel("France 24 English", "https://static.france24.com/live/F24_EN_LO_HLS/live_tv.m3u8", "", "News"));
        presets.add(new IptvChannel("NHK World Japan News", "https://nhkworld.akamaized.net/hls/live/2007432/live_wa/index.m3u8", "", "News"));
        presets.add(new IptvChannel("Al Jazeera English", "https://live-amg-el.akamaized.net/groupc/live/v2/aljazeera/en/unm/master.m3u8", "", "News"));
        presets.add(new IptvChannel("CNA Singapore News", "https://cna-hls.akamaized.net/hls/live/2007421/cnaen/master.m3u8", "", "News"));
        presets.add(new IptvChannel("Red Bull TV Live", "https://rbmn-live.akamaized.net/hls/live/2008342/redbulltv/master.m3u8", "", "Sports & Lifestyle"));
        presets.add(new IptvChannel("NASA Media Channel", "https://ntv2.nasatv.net/hls/ntv2_hq.m3u8", "", "Science & Space"));
        presets.add(new IptvChannel("PBS America PBS", "https://pbslm-lh.akamaihd.net/i/pbsprem_1@391942/master.m3u8", "", "Documentary"));
        presets.add(new IptvChannel("Arirang TV Korea", "http://amdlive.gscdn.com/arirang_1ch/smil:arirang_1ch.smil/playlist.m3u8", "", "Culture"));
        return presets;
    }

    private String getPresetChannelsSignature() {
        java.util.List<IptvChannel> presets = getDefaultPresetChannels();
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (IptvChannel ch : presets) {
            sb.append(ch.name).append("|").append(ch.url).append("|").append(ch.group).append(";");
        }
        return sb.toString();
    }

    private void loadDefaultPresetChannels() {
        mAllChannels.clear();
        mAllChannels.addAll(getDefaultPresetChannels());

        mCategories.clear();
        mCategories.add("ALL CHANNELS");
        mCategories.add("★ FAVORITES");
        
        java.util.Set<String> uniqueGroups = new java.util.LinkedHashSet<>();
        for (IptvChannel ch : mAllChannels) {
            if (ch.group != null && !ch.group.trim().isEmpty()) {
                uniqueGroups.add(ch.group);
            }
        }
        mCategories.addAll(uniqueGroups);
        
        mSelectedCategory = "ALL CHANNELS";
        if (mTvActiveStatus != null) {
            mTvActiveStatus.setText("Preset Active: " + mAllChannels.size() + " Streams Ready");
        }
        
        mCategoryAdapter = new CategoryAdapter(mCategories);
        mListCategories.setAdapter(mCategoryAdapter);
        mCategoryAdapter.setSelectedPosition(0);
        
        filterAndDisplayChannels();

        // Cache the default preset channels into local SQL database
        saveChannelsToDb(mAllChannels, true);

        // Save current presets signature to avoid unnecessary resets later
        android.content.SharedPreferences prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE);
        prefs.edit().putString("key_preset_signature", getPresetChannelsSignature()).apply();
    }

    private void filterAndDisplayChannels() {
        java.util.List<IptvChannel> filtered = new java.util.ArrayList<>();
        String query = mSearchText.toLowerCase().trim();
        
        for (IptvChannel ch : mAllChannels) {
            boolean matchesCategory;
            if (mSelectedCategory.equals("ALL CHANNELS")) {
                matchesCategory = true;
            } else if (mSelectedCategory.equals("★ FAVORITES")) {
                matchesCategory = ch.isFavorite;
            } else {
                matchesCategory = ch.group != null && ch.group.equals(mSelectedCategory);
            }
            if (!matchesCategory) continue;

            boolean matchesSearch = query.isEmpty() 
                    || (ch.name != null && ch.name.toLowerCase().contains(query)) 
                    || (ch.group != null && ch.group.toLowerCase().contains(query));
            
            if (matchesSearch) {
                filtered.add(ch);
                // Cap matched dynamic search results to 600 items so we don't freeze the main TV thread!
                if (filtered.size() >= 600) {
                    break;
                }
            }
        }
        
        if (mChannelAdapter == null) {
            mChannelAdapter = new ChannelAdapter(filtered);
            mListChannels.setAdapter(mChannelAdapter);
        } else {
            mChannelAdapter.updateItems(filtered);
        }
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

    // --- Local Database Persistence & Favorite Streams Management ---

    public static class IptvDatabaseHelper extends android.database.sqlite.SQLiteOpenHelper {
        private static final String DATABASE_NAME = "iptv_enterprise.db";
        private static final int DATABASE_VERSION = 1;

        public IptvDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(android.database.sqlite.SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS channels (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, " +
                    "url TEXT UNIQUE, " +
                    "logo_url TEXT, " +
                    "group_name TEXT, " +
                    "is_favorite INTEGER DEFAULT 0)");
            
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (" +
                    "key TEXT PRIMARY KEY, " +
                    "value TEXT)");
        }

        @Override
        public void onUpgrade(android.database.sqlite.SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS channels");
            db.execSQL("DROP TABLE IF EXISTS meta");
            onCreate(db);
        }
    }

    private void saveChannelsToDb(final java.util.List<IptvChannel> channels, final boolean clearOld) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.database.sqlite.SQLiteDatabase db = mDbHelper.getWritableDatabase();
                    db.beginTransaction();
                    try {
                        if (clearOld) {
                            // Delete non-favorites to keep database tidy and load quick
                            db.execSQL("DELETE FROM channels WHERE is_favorite = 0");
                        }
                        
                        android.database.sqlite.SQLiteStatement stmt = db.compileStatement(
                            "INSERT OR IGNORE INTO channels (name, url, logo_url, group_name, is_favorite) VALUES (?, ?, ?, ?, ?)"
                        );
                        
                        for (IptvChannel ch : channels) {
                            stmt.clearBindings();
                            stmt.bindString(1, ch.name);
                            stmt.bindString(2, ch.url);
                            stmt.bindString(3, ch.logoUrl);
                            stmt.bindString(4, ch.group);
                            stmt.bindLong(5, ch.isFavorite ? 1 : 0);
                            stmt.executeInsert();
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                } catch (Exception e) {
                    android.util.Log.e("IPTV_DB", "Failed to cache channels: " + e.getMessage());
                }
            }
        }).start();
    }

    private void loadChannelsFromDb() {
        // Run database restoration on background thread to keep startup frame rendering perfectly fluid
        // We do NOT block the UI with a full-screen loading screen because reading from local SQLite is extremely fast (10-30ms)
        new Thread(new Runnable() {
            @Override
            public void run() {
                final android.content.SharedPreferences prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE);
                final String currentSignature = getPresetChannelsSignature();
                final String savedSignature = prefs.getString("key_preset_signature", "");
                
                // If preset signature changed (meaning presets were changed inside Google AI Studio code "here"),
                // we automatically rebuild database with the new default presets, while keeping favorites intact.
                if (!currentSignature.equals(savedSignature)) {
                    android.util.Log.d("IPTV_AUTO_UPDATE", "Presets configuration changed in code. Auto-rebuilding presets database...");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loadDefaultPresetChannels();
                            // Saved signature is updated inside loadDefaultPresetChannels()
                            // Also check background updates to custom playlist URL if configured
                            autoUpdatePlaylistInBackground();
                        }
                    });
                    return;
                }

                final java.util.List<IptvChannel> list = new java.util.ArrayList<>();
                final java.util.Set<String> uniqueGroups = new java.util.LinkedHashSet<>();
                
                try {
                    android.database.sqlite.SQLiteDatabase db = mDbHelper.getReadableDatabase();
                    android.database.Cursor cursor = db.rawQuery("SELECT name, url, logo_url, group_name, is_favorite FROM channels", null);
                    
                    if (cursor != null) {
                        int nameCol = cursor.getColumnIndex("name");
                        int urlCol = cursor.getColumnIndex("url");
                        int logoCol = cursor.getColumnIndex("logo_url");
                        int groupCol = cursor.getColumnIndex("group_name");
                        int favCol = cursor.getColumnIndex("is_favorite");
                        
                        while (cursor.moveToNext()) {
                            String name = cursor.getString(nameCol);
                            String url = cursor.getString(urlCol);
                            String logoUrl = cursor.getString(logoCol);
                            String group = cursor.getString(groupCol);
                            int isFav = cursor.getInt(favCol);
                            
                            IptvChannel ch = new IptvChannel(name, url, logoUrl, group);
                            ch.isFavorite = (isFav == 1);
                            list.add(ch);
                            
                            if (group != null && !group.trim().isEmpty()) {
                                uniqueGroups.add(group);
                            }
                        }
                        cursor.close();
                    }
                } catch (Exception e) {
                    android.util.Log.e("IPTV_DB", "Failed to load cached channels: " + e.getMessage());
                }
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (list.isEmpty()) {
                            // Empty DB, load preset fallback
                            loadDefaultPresetChannels();
                            // Also check background updates to custom playlist URL if configured
                            autoUpdatePlaylistInBackground();
                        } else {
                            mAllChannels.clear();
                            mAllChannels.addAll(list);
                            
                            mCategories.clear();
                            mCategories.add("ALL CHANNELS");
                            
                            // Always add Star Favorites tab
                            mCategories.add("★ FAVORITES");
                            
                            mCategories.addAll(uniqueGroups);
                            
                            mSelectedCategory = "ALL CHANNELS";
                            if (mTvActiveStatus != null) {
                                mTvActiveStatus.setText("Database Active: " + mAllChannels.size() + " Streams");
                            }
                            
                            mCategoryAdapter = new CategoryAdapter(mCategories);
                            mListCategories.setAdapter(mCategoryAdapter);
                            mCategoryAdapter.setSelectedPosition(0);
                            
                            filterAndDisplayChannels();
                            
                            if (mLayoutLoadingOverlay != null) {
                                mLayoutLoadingOverlay.setVisibility(View.GONE);
                            }

                            // Trigger background automatic remote sync if a playlist URL was saved in settings
                            autoUpdatePlaylistInBackground();
                        }
                    }
                });
            }
        }).start();
    }

    private void toggleFavorite(final IptvChannel channel) {
        if (channel == null) return;
        channel.isFavorite = !channel.isFavorite;
        
        final boolean isFav = channel.isFavorite;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.database.sqlite.SQLiteDatabase db = mDbHelper.getWritableDatabase();
                    android.database.sqlite.SQLiteStatement stmt = db.compileStatement(
                        "UPDATE channels SET is_favorite = ? WHERE url = ?"
                    );
                    stmt.bindLong(1, isFav ? 1 : 0);
                    stmt.bindString(2, channel.url);
                    stmt.executeUpdateDelete();
                } catch (Exception e) {
                    android.util.Log.e("IPTV_DB", "Failed to update favorite status: " + e.getMessage());
                }
            }
        }).start();
        
        // Instantly force-refresh display filters
        filterAndDisplayChannels();
        
        android.widget.Toast.makeText(this, 
            isFav ? "★ Added to Favorites: " + channel.name : "☆ Removed from Favorites: " + channel.name, 
            android.widget.Toast.LENGTH_SHORT).show();
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

    private static class ChannelViewHolder {
        android.widget.ImageView logoImage;
        android.widget.TextView fallbackText;
        android.widget.FrameLayout logoContainer;
        android.widget.TextView nameText;
        android.widget.TextView groupText;
        String currentLogoUrl;
    }

    // Unified Input Manager supporting both TV D-pad key events and touchscreen motion events
    private class UnifiedInputManager implements View.OnClickListener, View.OnTouchListener, View.OnKeyListener {
        private final IptvChannel channel;
        private final int position;
        private final android.view.GestureDetector gestureDetector;

        public UnifiedInputManager(IptvChannel channel, final int position) {
            this.channel = channel;
            this.position = position;
            this.gestureDetector = new android.view.GestureDetector(MainActivity.this, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(android.view.MotionEvent e) {
                    if (mListChannels != null) {
                        mListChannels.setSelection(position);
                    }
                    triggerPlayback();
                    return true;
                }
            });
        }

        @Override
        public void onClick(View v) {
            triggerPlayback();
        }

        @Override
        public boolean onTouch(View v, android.view.MotionEvent event) {
            gestureDetector.onTouchEvent(event);
            return false; // Very important: returns false so ListView can handle standard touch scrolling
        }

        @Override
        public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    if (mListChannels != null) {
                        mListChannels.setSelection(position);
                    }
                    triggerPlayback();
                    return true;
                }
            }
            return false;
        }

        private void triggerPlayback() {
            if (channel != null && channel.url != null && !channel.url.isEmpty()) {
                mSelectedChannelUrl = channel.url;
                mSelectedChannelName = channel.name != null ? channel.name : "";
                openIptvStream();
            }
        }
    }

    private class ChannelAdapter extends android.widget.BaseAdapter {
        private java.util.List<IptvChannel> mItems;

        public ChannelAdapter(java.util.List<IptvChannel> items) {
            this.mItems = items;
        }

        public void updateItems(java.util.List<IptvChannel> newItems) {
            this.mItems = newItems;
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
            android.widget.LinearLayout rowLayout;
            final ChannelViewHolder holder;

            if (convertView == null) {
                rowLayout = new android.widget.LinearLayout(MainActivity.this);
                rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                rowLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
                rowLayout.setPadding(24, 14, 24, 14);
                rowLayout.setLayoutParams(new android.widget.ListView.LayoutParams(
                        android.widget.ListView.LayoutParams.MATCH_PARENT, 
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                rowLayout.setFocusable(true);
                rowLayout.setFocusableInTouchMode(false);

                // Premium focus animation
                rowLayout.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (hasFocus) {
                            v.setBackgroundColor(0xFF1557B0); // High contrast energetic focused blue
                            if (Build.VERSION.SDK_INT >= 11) {
                                v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start();
                            }
                        } else {
                            v.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                            if (Build.VERSION.SDK_INT >= 11) {
                                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                            }
                        }
                    }
                });

                // FrameLayout Container for Logo + Circular Initial Fallback Badge
                android.widget.FrameLayout logoContainer = new android.widget.FrameLayout(MainActivity.this);
                android.widget.LinearLayout.LayoutParams logoContainerParams = new android.widget.LinearLayout.LayoutParams(
                        (int) (42 * getResources().getDisplayMetrics().density),
                        (int) (42 * getResources().getDisplayMetrics().density));
                logoContainer.setLayoutParams(logoContainerParams);

                // Circular gradient background for the logo container
                android.graphics.drawable.GradientDrawable circleBg = new android.graphics.drawable.GradientDrawable();
                circleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                circleBg.setColor(0xFF222222);
                circleBg.setStroke(2, 0xFF3E3E3E);
                if (Build.VERSION.SDK_INT >= 16) {
                    logoContainer.setBackground(circleBg);
                } else {
                    logoContainer.setBackgroundDrawable(circleBg);
                }

                // ImageView for remote channel logos
                android.widget.ImageView logoImage = new android.widget.ImageView(MainActivity.this);
                logoImage.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                logoImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                logoImage.setPadding(4, 4, 4, 4);

                // Fallback text view styled nicely with single bold initials
                android.widget.TextView fallbackTex = new android.widget.TextView(MainActivity.this);
                android.widget.FrameLayout.LayoutParams textParams = new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                fallbackTex.setLayoutParams(textParams);
                fallbackTex.setGravity(android.view.Gravity.CENTER);
                fallbackTex.setTextColor(0xFF00E5FF); // Vibrant neon cyan fallback letter color
                fallbackTex.setTextSize(14);
                fallbackTex.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

                logoContainer.addView(fallbackTex);
                logoContainer.addView(logoImage);

                // Vertical layout for text details (Name & metadata)
                android.widget.LinearLayout textLayout = new android.widget.LinearLayout(MainActivity.this);
                textLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.widget.LinearLayout.LayoutParams textLayoutParams = new android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                textLayoutParams.setMargins((int) (14 * getResources().getDisplayMetrics().density), 0, 0, 0);
                textLayout.setLayoutParams(textLayoutParams);

                android.widget.TextView nameView = new android.widget.TextView(MainActivity.this);
                nameView.setTextColor(0xFFFFFFFF);
                nameView.setTextSize(14);
                nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);

                android.widget.TextView groupView = new android.widget.TextView(MainActivity.this);
                groupView.setTextColor(0xFF999999);
                groupView.setTextSize(11);
                groupView.setSingleLine(true);
                groupView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                groupView.setPadding(0, 4, 0, 0);

                textLayout.addView(nameView);
                textLayout.addView(groupView);

                rowLayout.addView(logoContainer);
                rowLayout.addView(textLayout);

                holder = new ChannelViewHolder();
                holder.logoImage = logoImage;
                holder.fallbackText = fallbackTex;
                holder.logoContainer = logoContainer;
                holder.nameText = nameView;
                holder.groupText = groupView;

                rowLayout.setTag(holder);
            } else {
                rowLayout = (android.widget.LinearLayout) convertView;
                holder = (ChannelViewHolder) rowLayout.getTag();
            }

            final IptvChannel channel = mItems.get(position);

            // Render Channel Title and metadata details
            if (channel.isFavorite) {
                android.text.SpannableString ss = new android.text.SpannableString(channel.name + " ★");
                ss.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFD700), ss.length() - 1, ss.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                holder.nameText.setText(ss);
            } else {
                holder.nameText.setText(channel.name);
            }
            holder.groupText.setText(channel.group.toUpperCase() + "  •  " + channel.url);

            // Modern, smooth, asynchronous image loader triggers
            final String logoUrl = channel.logoUrl;
            holder.currentLogoUrl = logoUrl;
            
            // Set default initial fallback text
            String firstLetter = "?";
            if (channel.name != null && !channel.name.trim().isEmpty()) {
                firstLetter = channel.name.trim().substring(0, 1).toUpperCase();
            }
            holder.fallbackText.setText(firstLetter);
            holder.logoImage.setVisibility(View.GONE);
            holder.fallbackText.setVisibility(View.VISIBLE);

            if (logoUrl != null && !logoUrl.isEmpty()) {
                final int logoSizePx = (int) (42 * getResources().getDisplayMetrics().density);
                if (mImageLoader != null) {
                    mImageLoader.loadLogo(logoUrl, logoSizePx, new AsyncImageLoader.ImageCallback() {
                        @Override
                        public void onLoaded(android.graphics.Bitmap bitmap) {
                            // Check first if the view has been recycled or re-used for another stream element
                            if (logoUrl.equals(holder.currentLogoUrl)) {
                                holder.logoImage.setImageBitmap(bitmap);
                                holder.logoImage.setVisibility(View.VISIBLE);
                                holder.fallbackText.setVisibility(View.GONE);
                            }
                        }

                        @Override
                        public void onError() {
                            // Fallback holds its letter state gracefully
                        }
                    });
                }
            }

            UnifiedInputManager inputMgr = new UnifiedInputManager(channel, position);
            rowLayout.setOnClickListener(inputMgr);
            rowLayout.setOnTouchListener(inputMgr);
            rowLayout.setOnKeyListener(inputMgr);

            return rowLayout;
        }
    }

    // --- Dynamic TV Settings Panel & Hardware Buffering Optimization ---

    private void initSettingsScreen() {
        mBtnOpenSettings = findViewById(R.id.btn_open_settings);
        mLayoutSettings = findViewById(R.id.layout_settings);
        mSettingsPlaylistUrl = findViewById(R.id.settings_playlist_url);
        mBtnSettingsSavePlaylist = findViewById(R.id.btn_settings_save_playlist);
        mSettingsBufferGroup = findViewById(R.id.settings_buffer_group);
        mRadioBufferLow = findViewById(R.id.radio_buffer_low);
        mRadioBufferStd = findViewById(R.id.radio_buffer_std);
        mRadioBufferSafe = findViewById(R.id.radio_buffer_safe);
        mBtnSettingsClearCache = findViewById(R.id.btn_settings_clear_cache);
        mBtnSettingsResetDb = findViewById(R.id.btn_settings_reset_db);
        mSettingsAppVersion = findViewById(R.id.settings_app_version);
        mBtnSettingsClose = findViewById(R.id.btn_settings_close);

        final android.content.SharedPreferences prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE);

        // 1. App Version / About layout strings
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;
            mSettingsAppVersion.setText("PAVEL IPTV v" + version);
        } catch (Exception e) {
            mSettingsAppVersion.setText("PAVEL IPTV v3.0.0");
        }

        // 2. Pre-populate default or last saved remote playlist
        String storedUrl = prefs.getString("settings_playlist_url", "https://is.gd/yQuS1g.m3u");
        mSettingsPlaylistUrl.setText(storedUrl);
        if (!storedUrl.isEmpty() && mEditM3uUrl != null && mEditM3uUrl.getText().toString().isEmpty()) {
            mEditM3uUrl.setText(storedUrl);
        }

        // 3. Populate default Buffer Seconds RadioGroup checked state
        int bufferSec = prefs.getInt("settings_buffer_seconds", 10);
        if (bufferSec == 5) {
            mRadioBufferLow.setChecked(true);
        } else if (bufferSec == 25) {
            mRadioBufferSafe.setChecked(true);
        } else {
            mRadioBufferStd.setChecked(true);
        }

        // 4. Configure click listeners for hardware management & exit parameters
        mBtnOpenSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = prefs.getString("settings_playlist_url", "https://is.gd/yQuS1g.m3u");
                mSettingsPlaylistUrl.setText(url);
                mLayoutSettings.setVisibility(View.VISIBLE);
                mSettingsPlaylistUrl.requestFocus();
            }
        });

        mBtnSettingsSavePlaylist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = mSettingsPlaylistUrl.getText().toString().trim();
                prefs.edit().putString("settings_playlist_url", url).apply();
                if (mEditM3uUrl != null) {
                    mEditM3uUrl.setText(url);
                }
                mLayoutSettings.setVisibility(View.GONE);
                if (!url.isEmpty()) {
                    loadM3uPlaylist(url);
                } else {
                    android.widget.Toast.makeText(MainActivity.this, "Playlist URL cleared. Restoring default fallback stations.", android.widget.Toast.LENGTH_SHORT).show();
                    loadDefaultPresetChannels();
                }
            }
        });

        mSettingsBufferGroup.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.RadioGroup group, int checkedId) {
                int secs = 10;
                if (checkedId == R.id.radio_buffer_low) {
                    secs = 5;
                } else if (checkedId == R.id.radio_buffer_safe) {
                    secs = 25;
                }
                prefs.edit().putInt("settings_buffer_seconds", secs).apply();
                android.widget.Toast.makeText(MainActivity.this, "Active Channel buffer changed to " + secs + "s segment processing", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        mBtnSettingsClearCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearWebViewCache();
            }
        });

        mBtnSettingsResetDb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("System Reset Confirmation")
                        .setMessage("Do you want to re-initialize the cache database? This will clear all user-customized list properties and reload default enterprise public stations.")
                        .setPositiveButton("YES, RE-LEGACY", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                resetChannelsDatabase();
                            }
                        })
                        .setNegativeButton("CANCEL", null)
                        .show();
                } catch (Exception ignored) {}
            }
        });

        mBtnSettingsClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLayoutSettings.setVisibility(View.GONE);
                mBtnOpenSettings.requestFocus();
            }
        });
    }

    private int getBufferSecondsSetting() {
        android.content.SharedPreferences prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE);
        return prefs.getInt("settings_buffer_seconds", 10);
    }

    private void clearWebViewCache() {
        if (mWebView != null) {
            mWebView.clearCache(true);
        }
        try {
            deleteDatabase("webview.db");
            deleteDatabase("webviewCache.db");
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(this, "⚡ Web Player cache system fully recycled!", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void resetChannelsDatabase() {
        try {
            android.database.sqlite.SQLiteDatabase db = mDbHelper.getWritableDatabase();
            mDbHelper.onUpgrade(db, 1, 1);
            mAllChannels.clear();
            mCategories.clear();
            loadDefaultPresetChannels();
            android.widget.Toast.makeText(this, "🔄 Station list transaction table re-initialized successfully!", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Failed to reset: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void checkHardwareVideoCapabilities() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.media.MediaCodecList codecList = new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
                android.media.MediaCodecInfo[] infos = codecList.getCodecInfos();
                boolean supportsH264 = false;
                boolean supportsH265Hevc = false;
                
                for (android.media.MediaCodecInfo info : infos) {
                    if (info.isEncoder()) continue;
                    String[] types = info.getSupportedTypes();
                    for (String type : types) {
                        if (type.equalsIgnoreCase("video/avc")) {
                            supportsH264 = true;
                        } else if (type.equalsIgnoreCase("video/hevc")) {
                            supportsH265Hevc = true;
                        }
                    }
                }
                android.util.Log.d("PavelIptvHardware", "AVC/H.264 supported: " + supportsH264 + ", HEVC/H.265 supported: " + supportsH265Hevc);
                
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(MainActivity.this, 
                                "📺 Settings optimized for Legacy Android 5.0 Device hardware acceleration.", 
                                android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        } catch (Exception e) {
            android.util.Log.e("PavelIptvHardware", "Error checking codec capabilities", e);
        }
    }

    private void autoUpdatePlaylistInBackground() {
        android.content.SharedPreferences prefs = getSharedPreferences("iptv_settings", Context.MODE_PRIVATE);
        final String playlistUrl = prefs.getString("settings_playlist_url", "https://is.gd/yQuS1g.m3u");
        
        // If there's no custom playlist URL set yet, or if offline, we don't start a background sync.
        if (playlistUrl.isEmpty() || !isNetworkAvailable()) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL url = new java.net.URL(playlistUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6500);
                    conn.setReadTimeout(10000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        java.io.InputStream is = conn.getInputStream();
                        final java.util.List<IptvChannel> parsedList = parseM3u(is);
                        is.close();
                        conn.disconnect();
                        
                        if (parsedList != null && !parsedList.isEmpty()) {
                            // Preserve user's local favorites before overwriting/saving new dynamic ones
                            final java.util.Set<String> favoriteUrls = new java.util.HashSet<>();
                            try {
                                android.database.sqlite.SQLiteDatabase db = mDbHelper.getReadableDatabase();
                                android.database.Cursor cursor = db.rawQuery("SELECT url FROM channels WHERE is_favorite = 1", null);
                                if (cursor != null) {
                                    int urlCol = cursor.getColumnIndex("url");
                                    while (cursor.moveToNext()) {
                                        favoriteUrls.add(cursor.getString(urlCol));
                                    }
                                    cursor.close();
                                }
                            } catch (Exception dbEx) {
                                android.util.Log.e("IPTV_AUTO_UPDATE", "Failed to preserve favorites", dbEx);
                            }

                            // Match favorites in newly parsed list
                            for (IptvChannel ch : parsedList) {
                                if (favoriteUrls.contains(ch.url)) {
                                    ch.isFavorite = true;
                                }
                            }

                            // Sync new channels in SQLite DB and replace old ones safely
                            saveChannelsToDb(parsedList, true);

                            // Refresh list and UI on main thread silently (smooth & seamless playback UX)
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mAllChannels.clear();
                                    mAllChannels.addAll(parsedList);
                                    
                                    mCategories.clear();
                                    mCategories.add("ALL CHANNELS");
                                    mCategories.add("★ FAVORITES");
                                    
                                    java.util.Set<String> uniqueGroups = new java.util.LinkedHashSet<>();
                                    for (IptvChannel ch : mAllChannels) {
                                        if (ch.group != null && !ch.group.trim().isEmpty()) {
                                            uniqueGroups.add(ch.group);
                                        }
                                    }
                                    mCategories.addAll(uniqueGroups);
                                    
                                    mSelectedCategory = "ALL CHANNELS";
                                    if (mTvActiveStatus != null) {
                                        mTvActiveStatus.setText("Auto-Synced: " + mAllChannels.size() + " Streams");
                                    }
                                    
                                    mCategoryAdapter = new CategoryAdapter(mCategories);
                                    mListCategories.setAdapter(mCategoryAdapter);
                                    mCategoryAdapter.setSelectedPosition(0);
                                    
                                    filterAndDisplayChannels();
                                    
                                    android.widget.Toast.makeText(MainActivity.this, 
                                        "⚡ Pavel IPTV auto-updated successfully!", 
                                        android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.e("IPTV_AUTO_UPDATE", "Background auto-sync failed: " + e.getMessage());
                }
            }
        }).start();
    }

    // High-performance, lightweight, self-contained Image Loader with LRU memory caching
    public static class AsyncImageLoader {
        private final android.util.LruCache<String, android.graphics.Bitmap> mMemoryCache;
        private final java.util.concurrent.ExecutorService mExecutorService;
        private final android.os.Handler mMainHandler;

        public AsyncImageLoader() {
            // Use 15% of available runtime memory for cache
            final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
            final int cacheSize = maxMemory / 8; // 12.5% of max RAM

            mMemoryCache = new android.util.LruCache<String, android.graphics.Bitmap>(cacheSize) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap bitmap) {
                    return bitmap.getByteCount() / 1024;
                }
            };
            // Limit to max 3 background image download threads so we don't starve CPU on slow TV Boxes
            mExecutorService = java.util.concurrent.Executors.newFixedThreadPool(3);
            mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }

        public interface ImageCallback {
            void onLoaded(android.graphics.Bitmap bitmap);
            void onError();
        }

        public void loadLogo(final String url, final int targetSizePx, final ImageCallback callback) {
            if (url == null || url.trim().isEmpty()) {
                callback.onError();
                return;
            }

            final String cacheKey = url + "_" + targetSizePx;
            final android.graphics.Bitmap cachedBitmap = mMemoryCache.get(cacheKey);
            if (cachedBitmap != null) {
                callback.onLoaded(cachedBitmap);
                return;
            }

            mExecutorService.submit(new Runnable() {
                @Override
                public void run() {
                    java.net.HttpURLConnection conn = null;
                    java.io.InputStream is = null;
                    try {
                        java.net.URL imageUrl = new java.net.URL(url);
                        conn = (java.net.HttpURLConnection) imageUrl.openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        conn.setInstanceFollowRedirects(true);
                        conn.connect();
                        
                        if (conn.getResponseCode() == java.net.HttpURLConnection.HTTP_OK) {
                            is = conn.getInputStream();
                            
                            // Highly optimized: Use inSampleSize to decode and downscale immediately to prevent OutOfMemory on TV!
                            byte[] data = readFully(is);
                            if (data != null && data.length > 0) {
                                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                                options.inJustDecodeBounds = true;
                                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, options);
                                
                                options.inSampleSize = calculateInSampleSize(options, targetSizePx, targetSizePx);
                                options.inJustDecodeBounds = false;
                                
                                final android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, options);
                                if (decoded != null) {
                                    // Resize to exact target size under scaled filtering to optimize screen visual rendering
                                    final android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(decoded, targetSizePx, targetSizePx, true);
                                    if (scaled != decoded) {
                                        decoded.recycle();
                                    }
                                    
                                    mMemoryCache.put(cacheKey, scaled);
                                    mMainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            callback.onLoaded(scaled);
                                        }
                                    });
                                    return;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (is != null) {
                            try { is.close(); } catch (Exception ignored) {}
                        }
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                    
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError();
                        }
                    });
                }
            });
        }

        private byte[] readFully(java.io.InputStream is) throws java.io.IOException {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }

        private int calculateInSampleSize(android.graphics.BitmapFactory.Options options, int reqWidth, int reqHeight) {
            final int height = options.outHeight;
            final int width = options.outWidth;
            int inSampleSize = 1;

            if (height > reqHeight || width > reqWidth) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }
            return inSampleSize;
        }

        public void shutdown() {
            mExecutorService.shutdownNow();
            mMemoryCache.evictAll();
        }
    }
}
