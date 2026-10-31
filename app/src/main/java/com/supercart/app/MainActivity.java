package com.supercart.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // Website that this app wraps.
    private static final String START_URL = "https://supercart-iota.vercel.app/";
    private static final String ALLOWED_HOST = "supercart-iota.vercel.app";

    // Google login happens in a real browser tab (Google blocks login inside a WebView).
    // When it finishes, the browser returns to the app through this link: supercartapp://auth?...
    private static final String AUTH_SCHEME = "supercartapp";
    private static final String AUTH_REDIRECT = "supercartapp://auth";

    private static final int REQ_LOCATION = 1001;
    private static final int REQ_FILE = 1002;

    private WebView webView;
    private ProgressBar progressBar;

    // Pending geolocation request from the website, answered after the user taps Allow / Deny.
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        // The website calls window.SupercartApp.openLogin(url) / redirectUri() to sign in with Google.
        webView.addJavascriptInterface(new AuthBridge(), "SupercartApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (("https".equals(scheme) || "http".equals(scheme))
                        && ALLOWED_HOST.equalsIgnoreCase(uri.getHost())) {
                    return false; // stay inside the app
                }
                // Everything else (tel:, mailto:, WhatsApp, UPI, other sites) opens outside.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(MainActivity.this, "Link open nahi ho paya", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadData(
                            "<html><body style='background:#000;color:#fff;font-family:sans-serif;"
                                    + "text-align:center;padding-top:30vh'>"
                                    + "<h3>Internet connection nahi hai</h3>"
                                    + "<p>Connection check karke dobara try karein.</p>"
                                    + "<button style='padding:12px 24px;font-size:16px' "
                                    + "onclick='location.href=\"" + START_URL + "\"'>Retry</button>"
                                    + "</body></html>",
                            "text/html", "UTF-8");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }

            // The website asked for the user's location (navigator.geolocation).
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                    return;
                }
                // Ask the user with the standard Android "Allow / Don't allow" permission dialog.
                pendingGeoCallback = callback;
                pendingGeoOrigin = origin;
                ActivityCompat.requestPermissions(
                        MainActivity.this,
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        REQ_LOCATION);
            }

            @Override
            public void onGeolocationPermissionsHidePrompt() {
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }

            // File upload (documents, photos) from the signup form.
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (!handleAuthIntent(getIntent())) {
            webView.loadUrl(START_URL); // if offline, the error page handler shows the retry screen
        }
    }

    // ---- Google login through a browser tab ----

    private class AuthBridge {
        @JavascriptInterface
        public String redirectUri() {
            return AUTH_REDIRECT;
        }

        @JavascriptInterface
        public void openLogin(final String url) {
            runOnUiThread(() -> openLoginTab(url));
        }
    }

    private void openLoginTab(String url) {
        Uri uri = Uri.parse(url);
        if (!"https".equals(uri.getScheme())) return;
        try {
            new CustomTabsIntent.Builder().build().launchUrl(this, uri);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Login page open nahi ho paya", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthIntent(intent);
    }

    // Returns true when this intent was the browser coming back after Google login.
    private boolean handleAuthIntent(Intent intent) {
        if (intent == null) return false;
        Uri data = intent.getData();
        if (data == null || !AUTH_SCHEME.equals(data.getScheme())) return false;
        intent.setData(null); // use it only once

        // Open the site's home page with the same ?code=... that Supabase sent back.
        // The website already finishes the login from that code (PKCE), exactly like
        // it does after a normal browser login.
        String query = data.getEncodedQuery();
        String fragment = data.getEncodedFragment();
        webView.loadUrl(START_URL
                + (query != null ? "?" + query : "")
                + (fragment != null ? "#" + fragment : ""));
        return true;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean granted = false;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            if (pendingGeoCallback != null) {
                pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
            if (!granted) {
                Toast.makeText(this, "Location permission ke bina delivery features kaam nahi karenge",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
