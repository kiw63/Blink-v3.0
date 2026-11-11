package com.blink.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import android.webkit.PermissionRequest;
import android.webkit.GeolocationPermissions;
import android.webkit.WebStorage;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.Browser;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Blink — a lightweight Chromium WebView browser.
 * Uses only Android platform APIs; no proprietary Chrome code or Google sync.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "blink_preferences";
    private static final String HISTORY = "history";
    private static final String BOOKMARKS = "bookmarks";
    private static final String SESSION = "session_tabs";
    private static final int FILE_REQUEST = 201;
    private static final int WEB_PERMISSION_REQUEST = 202;
    private static final int GEO_PERMISSION_REQUEST = 203;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private PermissionRequest pendingPermissionRequest;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private String lastClosedTitle = null;
    private String lastClosedUrl = null;
    private static final int MAX_TABS = 40;
    private static final int MAX_LIVE_WEBVIEWS = 8;
    private static final int MAX_HISTORY = 500;

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private SharedPreferences preferences;
    private int activeIndex = -1;
    private boolean darkMode;
    private boolean desktopMode;
    private boolean compatibilityMode;
    private boolean isolatedPrivate;
    // Prevent private navigation until cookies from the previous private session are cleared.
    private boolean privateProfileReady = true;
    private String waitingPrivateNavigation;
    private LinearLayout root;
    private FrameLayout pageContainer;
    private ProgressBar progressBar;
    private EditText addressBar;
    private LinearLayout addressPill;
    private TextView tabCounter;
    private TextView backButton;
    private TextView forwardButton;
    private TextView reloadButton;
    private TextView bookmarkButton;
    private TextView menuButton;
    private ValueCallback<Uri[]> pendingFileCallback;

    private static final class Tab {
        String title = "Tab baru";
        String url = "about:blank";
        boolean isHome = true;
        boolean privateMode;
        boolean desktop;
        String group = "";
        boolean loaded;
        WebView webView;
        Bundle savedWebState;
        long lastActive;
        Tab(boolean isPrivate) { privateMode = isPrivate; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        isolatedPrivate = this instanceof PrivateActivity;
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (isolatedPrivate) {
            privateProfileReady = false;
            // A different Android process + WebView data suffix separates regular cookies.
            // Do not allow new private navigation until previous private cookies are cleared.
            CookieManager.getInstance().removeAllCookies(success -> {
                WebStorage.getInstance().deleteAllData();
                CookieManager.getInstance().flush();
                privateProfileReady = true;
                String pending = waitingPrivateNavigation;
                waitingPrivateNavigation = null;
                if (pending != null && !isFinishing() && !isDestroyed()) openPage(pending);
            });
        }
        darkMode = preferences.getBoolean("dark_mode", true);
        desktopMode = preferences.getBoolean("desktop_mode", false);
        compatibilityMode = preferences.getBoolean("compatibility_mode", false);
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        buildInterface();
        if (!isolatedPrivate) restoreSession();
        if (tabs.isEmpty()) createTab(isolatedPrivate);
        renderTab();
        handleIncomingIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && UrlResolver.isWebUrl(data.toString())) {
                openPage(data.toString());
            }
        }
    }

    private int bg() { return darkMode ? Color.rgb(18, 18, 18) : Color.WHITE; }
    private int surface() { return darkMode ? Color.rgb(53, 53, 53) : Color.rgb(239, 242, 247); }
    private int primaryText() { return darkMode ? Color.WHITE : Color.rgb(33, 38, 47); }
    private int secondaryText() { return darkMode ? Color.rgb(195, 195, 195) : Color.rgb(100, 108, 120); }
    private int blue() { return Color.rgb(69, 172, 249); }
    private int line() { return darkMode ? Color.rgb(62, 62, 62) : Color.rgb(231, 235, 241); }
    private int dp(float count) { return (int) (getResources().getDisplayMetrics().density * count + 0.5f); }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable outlined(int color, int radius) {
        GradientDrawable drawable = rounded(color, radius);
        drawable.setStroke(dp(1), line());
        return drawable;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String label, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView iconButton(String symbol, String a11y, int width, Runnable action) {
        TextView view = text(symbol, 24, false, primaryText());
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(a11y);
        view.setBackground(rounded(bg(), 18));
        view.setOnClickListener(v -> action.run());
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(width), dp(48)));
        return view;
    }

    private void buildInterface() {
        getWindow().setStatusBarColor(bg());
        getWindow().setNavigationBarColor(bg());
        getWindow().getDecorView().setSystemUiVisibility(darkMode ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = vertical();
        root.setBackgroundColor(bg());
        setContentView(root);

        LinearLayout toolbar = horizontal();
        toolbar.setPadding(dp(10), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(bg());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(62)));

        TextView home = text("⌂", 29, false, primaryText());
        home.setGravity(Gravity.CENTER);
        home.setContentDescription("Beranda Blink");
        home.setOnClickListener(v -> showHome());
        LinearLayout.LayoutParams homeLp = new LinearLayout.LayoutParams(dp(37), dp(43));
        homeLp.rightMargin = dp(9);
        toolbar.addView(home, homeLp);

        addressPill = horizontal();
        addressPill.setPadding(dp(12), 0, dp(8), 0);
        addressPill.setBackground(rounded(surface(), 25));
        toolbar.addView(addressPill, new LinearLayout.LayoutParams(0, dp(44), 1));

        TextView addressIcon = text("⌕", 23, false, secondaryText());
        addressPill.addView(addressIcon, new LinearLayout.LayoutParams(dp(25), -1));
        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(15);
        addressBar.setTextColor(primaryText());
        addressBar.setHintTextColor(secondaryText());
        addressBar.setHint("Telusuri atau ketik alamat");
        addressBar.setBackgroundColor(Color.TRANSPARENT);
        addressBar.setPadding(0, 0, 0, 0);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                openPage(addressBar.getText().toString());
                return true;
            }
            return false;
        });
        addressPill.addView(addressBar, new LinearLayout.LayoutParams(0, -1, 1));

        ImageView brandButton = new ImageView(this);
        brandButton.setImageResource(R.drawable.blink_brand);
        brandButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brandButton.setContentDescription("Setelan Blink");
        brandButton.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        brandLp.leftMargin = dp(6);
        brandLp.rightMargin = dp(5);
        toolbar.addView(brandButton, brandLp);

        tabCounter = iconButton("1", "Buka daftar tab", 41, this::showTabsDialog);
        tabCounter.setTextSize(16);
        tabCounter.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toolbar.addView(tabCounter);
        menuButton = iconButton("⋮", "Menu Blink", 35, this::showMenu);
        toolbar.addView(menuButton);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(blue()));
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(bg()));
        progressBar.setVisibility(View.INVISIBLE);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(2)));

        pageContainer = new FrameLayout(this);
        pageContainer.setBackgroundColor(bg());
        root.addView(pageContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        View divider = new View(this);
        divider.setBackgroundColor(line());
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        divider.setVisibility(View.GONE); // Chrome-style layout: use Android system navigation

        LinearLayout navbar = horizontal();
        navbar.setBackgroundColor(bg());
        navbar.setPadding(dp(8), 0, dp(8), dp(2));
        root.addView(navbar, new LinearLayout.LayoutParams(-1, dp(56)));
        navbar.setVisibility(View.GONE); // navigation actions live in the three-dot menu
        backButton = bottomButton("‹", "Kembali", this::goBack);
        forwardButton = bottomButton("›", "Maju", this::goForward);
        reloadButton = bottomButton("↻", "Muat ulang", this::reloadPage);
        bookmarkButton = bottomButton("☆", "Tambah atau hapus bookmark", this::toggleBookmark);
        TextView homeButton = bottomButton("⌂", "Beranda", this::showHome);
        navbar.addView(backButton);
        navbar.addView(forwardButton);
        navbar.addView(homeButton);
        navbar.addView(bookmarkButton);
        navbar.addView(reloadButton);
    }

    private TextView bottomButton(String symbol, String a11y, Runnable action) {
        TextView button = text(symbol, 29, false, primaryText());
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(a11y);
        button.setOnClickListener(v -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        return button;
    }

    private Tab current() {
        if (activeIndex < 0 || activeIndex >= tabs.size()) return null;
        return tabs.get(activeIndex);
    }

    private Tab createTab(boolean privateMode) {
        privateMode |= isolatedPrivate;
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Maksimum " + MAX_TABS + " tab", Toast.LENGTH_SHORT).show();
            return current();
        }
        Tab tab = new Tab(privateMode);
        tabs.add(tab);
        switchTab(tabs.size() - 1);
        return tab;
    }

    private void newPrivateTab() {
        if (isolatedPrivate) { createTab(true); return; }
        new AlertDialog.Builder(this)
                .setTitle("Mode Samaran Blink")
                .setMessage("Membuka jendela terpisah dengan penyimpanan WebView berbeda. "
                        + "Histori tidak disimpan oleh Blink. Unduhan, penyedia internet, dan situs "
                        + "tetap dapat mencatat aktivitas.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Buka Samaran", (d, w) ->
                        startActivity(new Intent(this, PrivateActivity.class)))
                .show();
    }

    private void switchTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab previous = current();
        if (previous != null && previous.webView != null) previous.webView.onPause();
        activeIndex = index;
        renderTab();
        Tab now = current();
        if (now != null) now.lastActive = android.os.SystemClock.uptimeMillis();
        trimBackgroundTabs();
        if (now != null && now.webView != null && !now.isHome) now.webView.onResume();
        persistSession();
    }

    private void renderTab() {
        if (pageContainer == null) return;
        pageContainer.removeAllViews();
        Tab tab = current();
        if (tab == null) return;
        if (tab.isHome) {
            showStartPage(tab);
        } else {
            WebView webView = ensureWebView(tab);
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            pageContainer.addView(webView, new FrameLayout.LayoutParams(-1, -1));
            if (!tab.loaded && UrlResolver.isWebUrl(tab.url)) {
                tab.loaded = true;
                webView.loadUrl(tab.url);
            }
        }
        updateControls();
    }

    private void updateControls() {
        Tab tab = current();
        if (tab == null || addressBar == null) return;
        if (addressPill != null) addressPill.setVisibility(tab.isHome ? View.INVISIBLE : View.VISIBLE);
        if (!addressBar.hasFocus()) {
            addressBar.setText(tab.isHome ? "" : tab.url);
            addressBar.setHint(tab.privateMode ? "Telusuri secara privat*" : "Telusuri atau ketik alamat");
        }
        tabCounter.setText(String.valueOf(tabs.size()));
        tabCounter.setTextColor(tab.privateMode ? blue() : primaryText());
        boolean back = !tab.isHome && tab.webView != null && tab.webView.canGoBack();
        boolean forward = !tab.isHome && tab.webView != null && tab.webView.canGoForward();
        backButton.setAlpha(back ? 1 : 0.35f);
        forwardButton.setAlpha(forward ? 1 : 0.35f);
        boolean bookmarked = !tab.isHome && hasSavedUrl(BOOKMARKS, tab.url);
        bookmarkButton.setText(bookmarked ? "★" : "☆");
        boolean loading = !tab.isHome && tab.webView != null && tab.webView.getProgress() < 100;
        reloadButton.setText(loading ? "×" : "↻");
        progressBar.setVisibility(loading ? View.VISIBLE : View.INVISIBLE);
        if (loading) progressBar.setProgress(tab.webView.getProgress());
    }

    private void showStartPage(Tab tab) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg());
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(42), dp(16), dp(36));
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(body);

        ImageView animatedLogo = new ImageView(this);
        animatedLogo.setImageResource(R.drawable.blink_brand);
        animatedLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        animatedLogo.setContentDescription("Logo Blink ungu biru");
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(dp(84), dp(84));
        body.addView(animatedLogo, brandParams);
        Animation floatAnim = new TranslateAnimation(0, 0, -dp(3), dp(5));
        floatAnim.setDuration(2200);
        floatAnim.setRepeatCount(Animation.INFINITE);
        floatAnim.setRepeatMode(Animation.REVERSE);
        animatedLogo.startAnimation(floatAnim);

        TextView title = text("Blink", 49, true, primaryText());
        title.setLetterSpacing(-0.045f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, dp(79));
        titleLp.topMargin = dp(3);
        body.addView(title, titleLp);
        Animation fade = new AlphaAnimation(0.65f, 1f);
        fade.setDuration(2600);
        fade.setRepeatCount(Animation.INFINITE);
        fade.setRepeatMode(Animation.REVERSE);
        title.startAnimation(fade);

        LinearLayout searchPill = horizontal();
        searchPill.setPadding(dp(18), 0, dp(12), 0);
        searchPill.setBackground(rounded(surface(), 60));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(68));
        searchLp.topMargin = dp(26);
        body.addView(searchPill, searchLp);
        TextView magnifier = text("⌕", 26, false, blue());
        magnifier.setGravity(Gravity.CENTER);
        searchPill.addView(magnifier, new LinearLayout.LayoutParams(dp(35), -1));
        EditText homeSearch = new EditText(this);
        homeSearch.setSingleLine(true);
        homeSearch.setTextColor(primaryText());
        homeSearch.setHintTextColor(secondaryText());
        homeSearch.setTextSize(15);
        homeSearch.setHint("Telusuri " + preferences.getString("search_engine", "Google") + " atau ketik URL");
        homeSearch.setBackgroundColor(Color.TRANSPARENT);
        homeSearch.setPadding(dp(2), 0, 0, 0);
        homeSearch.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        homeSearch.setImeOptions(EditorInfo.IME_ACTION_GO);
        homeSearch.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                openPage(homeSearch.getText().toString());
                return true;
            }
            return false;
        });
        searchPill.addView(homeSearch, new LinearLayout.LayoutParams(0, -1, 1));
        TextView lens = text("▣", 26, false, primaryText());
        lens.setContentDescription("Buka Google Lens untuk penelusuran gambar");
        lens.setGravity(Gravity.CENTER);
        lens.setOnClickListener(v -> openPage("https://lens.google.com/"));
        searchPill.addView(lens, new LinearLayout.LayoutParams(dp(43), -1));

        LinearLayout actionRow = horizontal();
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, dp(52));
        buttonsLp.topMargin = dp(12);
        body.addView(actionRow, buttonsLp);
        addHomePill(actionRow, "✦  Mode AI", () -> showAiSearch());
        addHomePill(actionRow, "◈  Samaran", this::newPrivateTab);

        LinearLayout quickCard = vertical();
        quickCard.setBackground(rounded(darkMode ? Color.rgb(31,31,31) : surface(), 23));
        quickCard.setPadding(dp(8), dp(18), dp(8), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, dp(128));
        cardLp.topMargin = dp(28);
        body.addView(quickCard, cardLp);
        LinearLayout quickRow = horizontal();
        quickCard.addView(quickRow, new LinearLayout.LayoutParams(-1, -1));
        addShortcut(quickRow, "G", "Google", "https://www.google.com");
        addShortcut(quickRow, "▶", "YouTube", "https://www.youtube.com");
        addShortcut(quickRow, "◈", "GitHub", "https://github.com");
        addShortcut(quickRow, "W", "Wikipedia", "https://id.wikipedia.org");

        LinearLayout securityCard = vertical();
        securityCard.setPadding(dp(18), dp(15), dp(18), dp(18));
        securityCard.setBackground(rounded(darkMode ? Color.rgb(31,31,31) : surface(), 23));
        LinearLayout.LayoutParams securityLp = new LinearLayout.LayoutParams(-1, dp(136));
        securityLp.topMargin = dp(14);
        body.addView(securityCard, securityLp);
        TextView securityTitle = text("Privasi & keamanan", 16, false, secondaryText());
        securityCard.addView(securityTitle);
        TextView securityBody = text("Atur izin situs dan hapus data penjelajahan", 18, false, primaryText());
        LinearLayout.LayoutParams secureTextLp = new LinearLayout.LayoutParams(-1, 0, 1);
        secureTextLp.topMargin = dp(10);
        securityCard.addView(securityBody, secureTextLp);
        TextView inspect = text("Kelola  ›", 15, true, Color.rgb(125, 219, 154));
        inspect.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        inspect.setOnClickListener(v -> showPrivacyDialog());
        securityCard.addView(inspect, new LinearLayout.LayoutParams(-1, dp(34)));

        LinearLayout newsCard = vertical();
        newsCard.setPadding(dp(18), dp(14), dp(18), dp(12));
        newsCard.setBackground(rounded(darkMode ? Color.rgb(31,31,31) : surface(), 23));
        LinearLayout.LayoutParams newsLp = new LinearLayout.LayoutParams(-1, dp(102));
        newsLp.topMargin = dp(14);
        body.addView(newsCard, newsLp);
        TextView newsTitle = text("Berita dari Google News", 17, true, primaryText());
        newsCard.addView(newsTitle);
        TextView newsSubtitle = text("Buka berita terkini langsung dari sumber", 14, false, secondaryText());
        newsCard.addView(newsSubtitle);
        newsCard.setOnClickListener(v -> openPage("https://news.google.com/home?hl=id&gl=ID&ceid=ID:id"));

        if (tab.privateMode) {
            TextView warning = text("Mode privat terbatas: cookie WebView mungkin dibagikan dengan tab biasa.",
                    12, false, secondaryText());
            warning.setText("Mode Samaran • histori Blink tidak disimpan; unduhan tetap terlihat di perangkat.");
            warning.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, dp(62));
            warningLp.topMargin = dp(12);
            body.addView(warning, warningLp);
        }
        pageContainer.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addHomePill(LinearLayout row, String label, Runnable action) {
        TextView pill = text(label, 15, false, primaryText());
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(rounded(darkMode ? Color.rgb(30, 49, 38) : Color.rgb(221, 240, 227), 50));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMargins(dp(5), 0, dp(5), 0);
        row.addView(pill, lp);
        pill.setOnClickListener(v -> action.run());
    }

    private void showAiSearch() {
        EditText question = new EditText(this);
        question.setSingleLine(true);
        question.setHint("Apa yang ingin kamu tanyakan?");
        new AlertDialog.Builder(this).setTitle("Telusuri dengan Mode AI Google")
                .setMessage("Hasil dan ketersediaan fitur ditentukan oleh Google dan wilayah/akunmu.")
                .setView(question).setNegativeButton("Batal", null)
                .setPositiveButton("Cari", (d, w) -> {
                    String q = question.getText().toString().trim();
                    String path = "https://www.google.com/search?udm=50";
                    if (!q.isEmpty()) path += "&q=" + Uri.encode(q);
                    openPage(path);
                }).show();
    }

    private void addShortcut(LinearLayout row, String icon, String label, String url) {
        LinearLayout tile = vertical();
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setOnClickListener(v -> openPage(url));
        row.addView(tile, new LinearLayout.LayoutParams(0, -1, 1));
        TextView symbol = text(icon, 23, true, primaryText());
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(rounded(darkMode ? Color.rgb(48,48,48) : Color.WHITE, 28));
        tile.addView(symbol, new LinearLayout.LayoutParams(dp(55), dp(55)));
        TextView name = text(label, 12, false, primaryText());
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(name, new LinearLayout.LayoutParams(-1, dp(27)));
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        if (addressBar != null) addressBar.clearFocus();
    }

    private void openPage(String input) {
        if (isolatedPrivate && !privateProfileReady) {
            waitingPrivateNavigation = input;
            Toast.makeText(this, "Menyiapkan ruang Samaran…", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = UrlResolver.resolve(input);
        String trimmed = input == null ? "" : input.trim();
        if (!trimmed.isEmpty() && url.startsWith("https://www.google.com/search?q=")) {
            String engine = preferences.getString("search_engine", "Google");
            if ("DuckDuckGo".equals(engine))
                url = "https://duckduckgo.com/?q=" + Uri.encode(trimmed);
            else if ("Bing".equals(engine))
                url = "https://www.bing.com/search?q=" + Uri.encode(trimmed);
        }
        hideKeyboard();
        if ("about:blank".equals(url)) { showHome(); return; }
        Tab tab = current();
        if (tab == null) tab = createTab(false);
        tab.url = url;
        tab.title = url;
        tab.isHome = false;
        tab.loaded = true;
        renderTab();
        tab.webView.loadUrl(url);
        persistSession();
    }

    private void showHome() {
        Tab tab = current();
        if (tab == null) return;
        if (tab.webView != null) {
            tab.webView.stopLoading();
            tab.webView.onPause();
        }
        tab.isHome = true;
        tab.title = "Tab baru";
        tab.url = "about:blank";
        renderTab();
        persistSession();
    }

    private WebView ensureWebView(Tab tab) {
        if (tab.webView != null) return tab.webView;
        WebView webView = new WebView(this);
        webView.setBackgroundColor(bg());
        tab.webView = webView;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSafeBrowsingEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        if (desktopMode) applyDesktopMode(webView, true);
        else if (compatibilityMode) applyCompatibilityMode(webView);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return true;
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                        || "about".equalsIgnoreCase(scheme)) return false;
                if ("intent".equalsIgnoreCase(scheme)) {
                    confirmIntentLink(uri.toString());
                } else if ("file".equalsIgnoreCase(scheme) || "javascript".equalsIgnoreCase(scheme)
                        || "content".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)) {
                    Toast.makeText(MainActivity.this, "Tautan lokal tidak dapat dibuka lewat web", Toast.LENGTH_SHORT).show();
                } else {
                    confirmExternal(uri);
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                tab.url = url;
                tab.isHome = false;
                if (tab == current()) updateControls();
            }
            @Override public void onPageFinished(WebView view, String url) {
                tab.url = url;
                String title = view.getTitle();
                if (title != null && !title.trim().isEmpty()) tab.title = title;
                if (!tab.privateMode && UrlResolver.isWebUrl(url)) remember(HISTORY, tab.title, url, MAX_HISTORY);
                if (tab == current()) updateControls();
                persistSession();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                   WebResourceError error) {
                if (request.isForMainFrame() && tab == current()) {
                    Toast.makeText(MainActivity.this, "Gagal memuat halaman. Periksa koneksi.", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); // Never ignore invalid TLS certificates.
                if (tab == current()) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Koneksi tidak aman")
                            .setMessage("Sertifikat keamanan situs tidak valid. Blink membatalkan koneksi untuk melindungi data Anda.")
                            .setPositiveButton("Tutup", null).show();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    String origin = request.getOrigin() == null ? "situs ini" : request.getOrigin().toString();
                    boolean video = false, audio = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) video = true;
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
                    }
                    if (!video && !audio) { request.deny(); return; }
                    final boolean wantsVideo = video, wantsAudio = audio;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Izin kamera/mikrofon")
                            .setMessage(origin + " ingin menggunakan "
                                    + (wantsVideo && wantsAudio ? "kamera dan mikrofon" : wantsVideo ? "kamera" : "mikrofon") + ".")
                            .setNegativeButton("Tolak", (d, w) -> request.deny())
                            .setPositiveButton("Izinkan", (d, w) -> grantWebPermission(request, wantsVideo, wantsAudio))
                            .setOnCancelListener(d -> request.deny()).show();
                });
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin,
                                                                      GeolocationPermissions.Callback callback) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Izin lokasi")
                        .setMessage("Izinkan " + origin + " mengakses lokasi perangkat?")
                        .setNegativeButton("Tolak", (d, w) -> callback.invoke(origin, false, false))
                        .setPositiveButton("Izinkan", (d, w) -> {
                            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                                callback.invoke(origin, true, false);
                            else {
                                if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
                                pendingGeoCallback = callback;
                                pendingGeoOrigin = origin;
                                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, GEO_PERMISSION_REQUEST);
                            }
                        }).show();
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCallback = callback;
                FrameLayout frame = findViewById(android.R.id.content);
                frame.addView(view, new FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
            @Override public void onHideCustomView() { exitFullscreen(); }
            @Override public void onProgressChanged(WebView view, int value) {
                if (tab == current()) {
                    progressBar.setProgress(value);
                    progressBar.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE);
                    reloadButton.setText(value >= 100 ? "↻" : "×");
                }
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty()) tab.title = title;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                        FileChooserParams fileChooserParams) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = callback;
                try {
                    Intent chooser = fileChooserParams.createIntent();
                    startActivityForResult(chooser, FILE_REQUEST);
                    return true;
                } catch (ActivityNotFoundException | SecurityException exception) {
                    pendingFileCallback = null;
                    Toast.makeText(MainActivity.this, "Pemilih file tidak tersedia", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                                                     boolean isUserGesture, Message resultMsg) {
                if (!isUserGesture || tabs.size() >= MAX_TABS) return false;
                Tab child = createTab(tab.privateMode);
                if (child == null) return false;
                child.isHome = false;
                child.loaded = true;
                renderTab();
                WebView newWebView = ensureWebView(child);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newWebView);
                resultMsg.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window) {
                for (int i = 0; i < tabs.size(); i++) {
                    if (tabs.get(i).webView == window) { closeTab(i); break; }
                }
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(webView, url, userAgent, contentDisposition, mimeType));
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = webView.getHitTestResult();
            if (hit == null || hit.getExtra() == null) return false;
            String link = hit.getExtra();
            if (!UrlResolver.isWebUrl(link) && !link.startsWith("blob:")) return false;
            boolean image = hit.getType() == WebView.HitTestResult.IMAGE_TYPE
                    || hit.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
            boolean anchor = image || hit.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE;
            if (!anchor) return false;
            String[] options = image
                    ? new String[]{"Buka di tab baru", "Download gambar / tautan", "Salin alamat"}
                    : new String[]{"Buka di tab baru", "Download tautan", "Salin alamat"};
            new AlertDialog.Builder(this).setTitle(image ? "Gambar / tautan" : "Tautan")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            if (!UrlResolver.isWebUrl(link)) {
                                Toast.makeText(this, "URL ini hanya berlaku pada halaman saat ini", Toast.LENGTH_LONG).show();
                                return;
                            }
                            createTab(tab.privateMode);
                            openPage(link);
                        } else if (which == 1) {
                            startDownload(webView, link, webView.getSettings().getUserAgentString(), "", null);
                        } else {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(ClipData.newPlainText("URL", link));
                            Toast.makeText(this, "Alamat disalin", Toast.LENGTH_SHORT).show();
                        }
                    }).show();
            return true;
        });
        if (tab.savedWebState != null) {
            // Restore back/forward navigation for offloaded inactive tabs.
            try {
                if (webView.restoreState(tab.savedWebState) != null) tab.loaded = true;
                else tab.loaded = false;
            } catch (RuntimeException ignored) { tab.loaded = false; }
            tab.savedWebState = null;
        }
        return webView;
    }

    /** Keep only a small number of native WebViews alive on low-memory Android devices. */
    private void trimBackgroundTabs() {
        int count = 0;
        for (Tab tab : tabs) if (tab.webView != null) count++;
        while (count > MAX_LIVE_WEBVIEWS) {
            Tab oldest = null;
            for (Tab tab : tabs) {
                if (tab == current() || tab.webView == null) continue;
                if (oldest == null || tab.lastActive < oldest.lastActive) oldest = tab;
            }
            if (oldest == null) break;
            WebView webView = oldest.webView;
            Bundle snapshot = new Bundle();
            try { webView.saveState(snapshot); oldest.savedWebState = snapshot; }
            catch (RuntimeException error) { oldest.savedWebState = null; }
            if (webView.getParent() instanceof ViewGroup)
                ((ViewGroup) webView.getParent()).removeView(webView);
            webView.stopLoading();
            webView.destroy();
            oldest.webView = null;
            oldest.loaded = false;
            count--;
        }
    }

    private void confirmExternal(Uri uri) {
        new AlertDialog.Builder(this).setTitle("Buka aplikasi lain?")
                .setMessage("Blink akan membuka tautan ini di aplikasi lain:\n" + uri)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Buka", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ignored) { Toast.makeText(this, "Tidak ada aplikasi pendukung", Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    private void confirmIntentLink(String raw) {
        try {
            Intent target = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
            target.addCategory(Intent.CATEGORY_BROWSABLE);
            target.setComponent(null);
            target.setSelector(null);
            String fallback = target.getStringExtra("browser_fallback_url");
            new AlertDialog.Builder(this).setTitle("Buka aplikasi eksternal?")
                    .setMessage("Situs meminta membuka aplikasi lain. Lanjutkan hanya jika kamu mempercayai situs tersebut.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Lanjutkan", (d, w) -> {
                        try { startActivity(target); }
                        catch (Exception notInstalled) {
                            if (UrlResolver.isWebUrl(fallback)) openPage(fallback);
                            else Toast.makeText(this, "Aplikasi yang diminta tidak terpasang", Toast.LENGTH_SHORT).show();
                        }
                    }).show();
        } catch (Exception exception) {
            Toast.makeText(this, "Tautan aplikasi tidak valid", Toast.LENGTH_SHORT).show();
        }
    }

    private void grantWebPermission(PermissionRequest request, boolean video, boolean audio) {
        ArrayList<String> permissions = new ArrayList<>();
        if (video && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.CAMERA);
        if (audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.RECORD_AUDIO);
        if (!permissions.isEmpty()) {
            if (pendingPermissionRequest != null) pendingPermissionRequest.deny();
            pendingPermissionRequest = request;
            requestPermissions(permissions.toArray(new String[0]), WEB_PERMISSION_REQUEST);
        } else {
            ArrayList<String> approved = new ArrayList<>();
            if (video) approved.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            if (audio) approved.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
            request.grant(approved.toArray(new String[0]));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == GEO_PERMISSION_REQUEST && pendingGeoCallback != null) {
            boolean allowed = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            pendingGeoCallback.invoke(pendingGeoOrigin, allowed, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
            return;
        }
        if (requestCode != WEB_PERMISSION_REQUEST || pendingPermissionRequest == null) return;
        boolean allApproved = grants.length > 0;
        for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) allApproved = false;
        PermissionRequest request = pendingPermissionRequest;
        pendingPermissionRequest = null;
        if (allApproved) {
            ArrayList<String> approved = new ArrayList<>();
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                        || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) approved.add(resource);
            }
            if (approved.isEmpty()) request.deny();
            else request.grant(approved.toArray(new String[0]));
        } else request.deny();
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        FrameLayout frame = findViewById(android.R.id.content);
        frame.removeView(fullscreenView);
        fullscreenView = null;
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
        getWindow().getDecorView().setSystemUiVisibility(darkMode ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    /** DownloadManager handles HTTP(S) redirects, background transfers and notifications.
     * Blob URLs use bounded chunk streaming because DownloadManager cannot fetch page-local blobs.
     */
    private void startDownload(WebView origin, String url, String userAgent,
                               String disposition, String mimeType) {
        if (url == null) return;
        String filename = BlobDownloader.safeName(URLUtil.guessFileName(url, disposition, mimeType));
        if (url.startsWith("blob:") || (url.startsWith("data:") && url.length() <= 180_000_000)) {
            BlobDownloader.download(this, origin, url, filename, mimeType);
            return;
        }
        if (!UrlResolver.isWebUrl(url)) {
            Toast.makeText(this, "Tipe unduhan belum didukung: "
                    + (url.length() > 32 ? url.substring(0, 32) : url), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(filename);
            request.setDescription("Diunduh oleh Blink Browser");
            if (mimeType != null && !mimeType.trim().isEmpty()) request.setMimeType(mimeType);
            if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) request.addRequestHeader("Cookie", cookies);
            // Some servers require the page's Referer for authenticated downloads.
            String referer = origin == null ? null : origin.getUrl();
            if (UrlResolver.isWebUrl(referer)) request.addRequestHeader("Referer", referer);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager tidak tersedia");
            long id = manager.enqueue(request);
            DownloadCenter.startHttp(this, id, filename, mimeType);
            Toast.makeText(this, "Mulai download: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception exception) {
            Toast.makeText(this, "Download gagal: periksa izin penyimpanan dan URL", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            pendingFileCallback = null;
        }
    }

    private void goBack() {
        Tab tab = current();
        if (tab != null && tab.webView != null && tab.webView.canGoBack()) {
            if (tab.isHome) { tab.isHome = false; renderTab(); }
            tab.webView.goBack();
        } else if (tab != null && !tab.isHome) showHome();
        else Toast.makeText(this, "Sudah di halaman awal", Toast.LENGTH_SHORT).show();
    }

    private void goForward() {
        Tab tab = current();
        if (tab != null && !tab.isHome && tab.webView != null && tab.webView.canGoForward())
            tab.webView.goForward();
    }

    private void reloadPage() {
        Tab tab = current();
        if (tab == null) return;
        if (tab.isHome) { renderTab(); return; }
        if (tab.webView == null) return;
        if (tab.webView.getProgress() < 100) tab.webView.stopLoading();
        else tab.webView.reload();
        updateControls();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.remove(index);
        if (!tab.privateMode && !tab.isHome && UrlResolver.isWebUrl(tab.url)) {
            lastClosedTitle = tab.title;
            lastClosedUrl = tab.url;
            preferences.edit().putString("recent_closed_title", lastClosedTitle)
                    .putString("recent_closed_url", lastClosedUrl).apply();
        }
        if (tab.webView != null) {
            if (tab.webView.getParent() instanceof ViewGroup)
                ((ViewGroup) tab.webView.getParent()).removeView(tab.webView);
            tab.webView.stopLoading();
            tab.webView.destroy();
        }
        if (tabs.isEmpty()) {
            activeIndex = -1;
            createTab(false);
            return;
        }
        if (activeIndex > index) activeIndex--;
        else if (activeIndex >= tabs.size()) activeIndex = tabs.size() - 1;
        renderTab();
        persistSession();
    }

    private void showTabsDialog() {
        LinearLayout list = vertical();
        list.setPadding(dp(15), dp(10), dp(15), dp(15));
        list.setBackgroundColor(bg());
        TextView add = text("＋  Tab baru", 17, true, blue());
        add.setPadding(dp(12), 0, 0, 0);
        list.addView(add, new LinearLayout.LayoutParams(-1, dp(52)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tab terbuka (" + tabs.size() + ")")
                .setView(scroll)
                .setNegativeButton("Selesai", null)
                .create();
        add.setOnClickListener(v -> { dialog.dismiss(); createTab(false); });
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            Tab tab = tabs.get(i);
            LinearLayout item = horizontal();
            item.setPadding(dp(12), dp(8), dp(4), dp(8));
            item.setBackground(outlined(i == activeIndex ? surface() : bg(), 14));
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, dp(68));
            itemLp.bottomMargin = dp(8);
            list.addView(item, itemLp);
            LinearLayout labels = vertical();
            labels.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text((tab.privateMode ? "◈ " : "") +
                    (tab.group.isEmpty() ? "" : "[" + tab.group + "] ") + tab.title,
                    15, true, primaryText());
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name);
            TextView url = text(tab.isHome ? "Beranda Blink" : tab.url, 12, false, secondaryText());
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(url);
            item.addView(labels, new LinearLayout.LayoutParams(0, -1, 1));
            labels.setOnClickListener(v -> { dialog.dismiss(); switchTab(index); });
            TextView x = iconButton("×", "Tutup tab", 45, () -> {
                dialog.dismiss();
                closeTab(index);
                showTabsDialog();
            });
            item.addView(x);
        }
        dialog.show();
    }

    private void showMenu() {
        Dialog dialog = new Dialog(this);
        LinearLayout menu = vertical();
        menu.setPadding(dp(13), dp(12), dp(13), dp(10));
        menu.setBackground(rounded(darkMode ? Color.rgb(54, 54, 54) : Color.rgb(245, 245, 245), 25));

        LinearLayout actions = horizontal();
        menu.addView(actions, new LinearLayout.LayoutParams(-1, dp(55)));
        addMenuCircle(actions, "‹", "Kembali", dialog, this::goBack, hasBack());
        addMenuCircle(actions, "›", "Maju", dialog, this::goForward, hasForward());
        addMenuCircle(actions, "☆", "Bookmark", dialog, this::toggleBookmark, true);
        addMenuCircle(actions, "↓", "Download", dialog, this::openDownloads, true);
        addMenuCircle(actions, "⟳", "Muat ulang", dialog, this::reloadPage, true);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(true);
        LinearLayout items = vertical();
        scroller.addView(items);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, 0, 1);
        listLp.topMargin = dp(7);
        menu.addView(scroller, listLp);

        menuItem(items, "⊞", "Tab baru", dialog, () -> createTab(false));
        menuItem(items, "◈", "Tab Samaran baru", dialog, this::newPrivateTab);
        menuItem(items, "▦", "Tambahkan tab ke grup baru", dialog, this::groupCurrentTab);
        menuDivider(items);
        menuItem(items, "◷", "Histori", dialog, () -> showEntriesDialog(HISTORY, "Histori"));
        menuItem(items, "▤", "Hapus data penjelajahan", dialog, this::clearBrowsingData);
        menuItem(items, "☷", "Kontrol situs", dialog, this::showSiteControls);
        menuDivider(items);
        menuItem(items, "⇩", "Download", dialog, this::openDownloads);
        menuItem(items, "★", "Bookmark", dialog, () -> showEntriesDialog(BOOKMARKS, "Bookmark"));
        menuItem(items, "▣", "Tab terbaru", dialog, this::reopenClosedTab);
        menuDivider(items);
        menuItem(items, "•", "Bagikan...", dialog, this::sharePage);
        menuItem(items, "⌕", "Cari di halaman", dialog, this::findInPage);
        menuItem(items, "文", "Terjemahkan...", dialog, this::translatePage);
        menuItem(items, "▤", "Tampilkan Mode Baca", dialog, this::toggleReaderMode);
        menuItem(items, "▣", "Tambahkan pintasan ke layar utama", dialog, this::addHomeShortcut);
        menuDivider(items);
        menuItem(items, "▢", desktopMode ? "✓ Situs desktop" : "□ Situs desktop", dialog, this::toggleDesktop);
        menuItem(items, "⚙", "Setelan", dialog, this::showSettings);
        menuItem(items, "ⓘ", "Tentang Blink", dialog, this::aboutBlink);

        dialog.setContentView(menu);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.TOP | Gravity.RIGHT;
            params.dimAmount = 0.32f;
            params.y = dp(44);
            params.x = dp(6);
            window.setAttributes(params);
        }
        dialog.show();
        if (window != null) {
            int fullWidth = getResources().getDisplayMetrics().widthPixels;
            int fullHeight = getResources().getDisplayMetrics().heightPixels;
            window.setLayout(Math.min(dp(288), fullWidth - dp(18)),
                    Math.min(dp(826), fullHeight - dp(104)));
        }
    }

    private boolean hasBack() {
        Tab tab = current();
        return tab != null && !tab.isHome && tab.webView != null && tab.webView.canGoBack();
    }

    private boolean hasForward() {
        Tab tab = current();
        return tab != null && !tab.isHome && tab.webView != null && tab.webView.canGoForward();
    }

    private void addMenuCircle(LinearLayout row, String symbol, String description,
                               Dialog dialog, Runnable action, boolean enabled) {
        TextView button = text(symbol, 26, false, primaryText());
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setAlpha(enabled ? 1 : 0.35f);
        button.setBackground(rounded(darkMode ? Color.rgb(31,31,31) : Color.WHITE, 50));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(43), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, lp);
        button.setEnabled(enabled);
        button.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
    }

    private void menuItem(LinearLayout items, String icon, String title,
                          Dialog dialog, Runnable action) {
        LinearLayout row = horizontal();
        row.setPadding(dp(5), 0, dp(6), 0);
        items.addView(row, new LinearLayout.LayoutParams(-1, dp(51)));
        TextView iconView = text(icon, 23, false, secondaryText());
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView, new LinearLayout.LayoutParams(dp(36), -1));
        TextView label = text(title, 15, false, primaryText());
        label.setPadding(dp(8), 0, 0, 0);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
        row.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
        row.setContentDescription(title);
    }

    private void menuDivider(LinearLayout list) {
        View divider = new View(this);
        divider.setBackgroundColor(line());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(dp(6), dp(8), dp(6), dp(8));
        list.addView(divider, lp);
    }

    private void groupCurrentTab() {
        Tab active = current();
        if (active == null) return;
        LinearLayout sheet = vertical();
        sheet.setPadding(dp(18), dp(8), dp(18), dp(8));
        EditText groupName = new EditText(this);
        groupName.setSingleLine(true);
        groupName.setHint("Nama grup");
        groupName.setText(active.group.isEmpty() ? "Grup baru" : active.group);
        sheet.addView(groupName, new LinearLayout.LayoutParams(-1, dp(54)));
        TextView description = text("Pilih tab yang akan dimasukkan:", 14, false, secondaryText());
        sheet.addView(description, new LinearLayout.LayoutParams(-1, dp(36)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout options = vertical();
        scroll.addView(options);
        ArrayList<CheckBox> checkboxes = new ArrayList<>();
        for (Tab tab : tabs) {
            CheckBox check = new CheckBox(this);
            check.setText(tab.title);
            check.setSingleLine(true);
            check.setEllipsize(TextUtils.TruncateAt.END);
            check.setTextColor(primaryText());
            check.setChecked(tab == active);
            options.addView(check, new LinearLayout.LayoutParams(-1, dp(49)));
            checkboxes.add(check);
        }
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, dp(230)));
        new AlertDialog.Builder(this).setTitle("Buat atau ubah grup tab")
                .setView(sheet).setNegativeButton("Batal", null)
                .setPositiveButton("Simpan grup", (d, w) -> {
                    String name = groupName.getText().toString().trim();
                    if (name.isEmpty()) name = "Grup baru";
                    for (int i = 0; i < tabs.size(); i++) {
                        if (checkboxes.get(i).isChecked()) tabs.get(i).group = name;
                    }
                    persistSession();
                    showTabsDialog();
                }).show();
    }

    private void reopenClosedTab() {
        String url = preferences.getString("recent_closed_url", "");
        if (!UrlResolver.isWebUrl(url)) {
            Toast.makeText(this, "Belum ada tab yang baru ditutup", Toast.LENGTH_SHORT).show();
            return;
        }
        Tab tab = createTab(false);
        if (tab != null) openPage(url);
    }

    private void showPrivacyDialog() {
        new AlertDialog.Builder(this).setTitle("Privasi & keamanan")
                .setItems(new String[]{"Kontrol situs", "Hapus data penjelajahan", "Tentang keamanan WebView"},
                        (d, which) -> {
                            if (which == 0) showSiteControls();
                            else if (which == 1) clearBrowsingData();
                            else new AlertDialog.Builder(this)
                                    .setTitle("Keamanan Blink")
                                    .setMessage("Blink menggunakan perlindungan sertifikat TLS dan Safe Browsing Android WebView. "
                                            + "Blink tidak menambahkan filter pencarian sendiri. Batas situs/akun/jaringan tetap berlaku.")
                                    .setPositiveButton("OK", null).show();
                        }).show();
    }

    private void clearBrowsingData() {
        String[] labels = {"Riwayat Blink", "Cookie dan penyimpanan situs (semua tab)", "Cache halaman"};
        boolean[] checked = {true, true, true};
        new AlertDialog.Builder(this).setTitle("Hapus data penjelajahan")
                .setMultiChoiceItems(labels, checked, (d, position, value) -> checked[position] = value)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus data", (d, w) -> {
                    if (checked[0] && !isolatedPrivate) preferences.edit().remove(HISTORY).apply();
                    if (checked[1]) {
                        CookieManager.getInstance().removeAllCookies(success -> CookieManager.getInstance().flush());
                        WebStorage.getInstance().deleteAllData();
                    }
                    if (checked[2]) for (Tab tab : tabs) {
                        if (tab.webView != null) tab.webView.clearCache(true);
                    }
                    Toast.makeText(this, "Penghapusan data diminta", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showSiteControls() {
        Tab tab = current();
        if (tab == null || tab.isHome || tab.webView == null) {
            new AlertDialog.Builder(this).setTitle("Kontrol situs")
                    .setMessage("Buka situs dahulu untuk mengatur izin dan JavaScript situs tersebut.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String host = Uri.parse(tab.url).getHost();
        String[] options = {
                "JavaScript: " + (tab.webView.getSettings().getJavaScriptEnabled() ? "Aktif" : "Nonaktif"),
                "Cookie pihak ketiga: " + (CookieManager.getInstance().acceptThirdPartyCookies(tab.webView) ? "Aktif" : "Nonaktif"),
                "Mode kompatibilitas: " + (compatibilityMode ? "Aktif" : "Nonaktif"),
                "Salin alamat situs", "Pengaturan izin aplikasi Android"};
        new AlertDialog.Builder(this).setTitle("Kontrol situs • " + host)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        WebSettings webSettings = tab.webView.getSettings();
                        webSettings.setJavaScriptEnabled(!webSettings.getJavaScriptEnabled());
                        tab.webView.reload();
                        showSiteControls();
                    } else if (which == 1) {
                        CookieManager cookies = CookieManager.getInstance();
                        cookies.setAcceptThirdPartyCookies(tab.webView, !cookies.acceptThirdPartyCookies(tab.webView));
                        tab.webView.reload();
                        showSiteControls();
                    } else if (which == 2) {
                        compatibilityMode = !compatibilityMode;
                        preferences.edit().putBoolean("compatibility_mode", compatibilityMode).apply();
                        for (Tab t : tabs) if (t.webView != null) applyDesktopMode(t.webView, desktopMode);
                        tab.webView.reload();
                        Toast.makeText(this, "Mode kompatibilitas berlaku untuk semua tab", Toast.LENGTH_LONG).show();
                    } else if (which == 3) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("Alamat situs", tab.url));
                        Toast.makeText(this, "Alamat disalin", Toast.LENGTH_SHORT).show();
                    } else {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                }).setNegativeButton("Selesai", null).show();
    }

    private void translatePage() {
        Tab tab = current();
        if (tab == null || tab.isHome || !UrlResolver.isWebUrl(tab.url)) {
            Toast.makeText(this, "Buka situs dahulu", Toast.LENGTH_SHORT).show(); return;
        }
        String source = tab.url;
        new AlertDialog.Builder(this).setTitle("Terjemahkan halaman")
                .setMessage("Membuka Google Translate untuk menerjemahkan URL ini ke Bahasa Indonesia. Situs tertentu mungkin tidak mengizinkan penerjemahan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Terjemahkan", (d, w) -> {
                    createTab(false);
                    openPage("https://translate.google.com/translate?sl=auto&tl=id&u="
                            + Uri.encode(source));
                }).show();
    }

    private void toggleReaderMode() {
        Tab tab = current();
        if (tab == null || tab.isHome || tab.webView == null) {
            Toast.makeText(this, "Buka artikel dahulu", Toast.LENGTH_SHORT).show(); return;
        }
        // Heuristic local presentation change, not an automatic text-extraction service.
        tab.webView.evaluateJavascript("(function(){var x=document.getElementById('blink-reading');" +
                "if(x){x.remove();return 'off'} x=document.createElement('style');x.id='blink-reading';" +
                "x.textContent='body{max-width:760px!important;margin:auto!important;line-height:1.8!important;" +
                "font-size:19px!important;padding:20px!important}nav,aside,footer,[role=navigation]," +
                ".advertisement,.ads{display:none!important}p{line-height:1.8!important}';" +
                "document.head.appendChild(x);return 'on'})()", result ->
                Toast.makeText(this, result.contains("on") ? "Mode baca diaktifkan bila didukung situs" :
                        result.contains("off") ? "Mode baca dimatikan" : "Mode baca tidak tersedia di halaman ini",
                        Toast.LENGTH_SHORT).show());
    }

    private void addHomeShortcut() {
        Tab tab = current();
        if (tab == null || tab.isHome || !UrlResolver.isWebUrl(tab.url)) {
            Toast.makeText(this, "Buka situs dahulu", Toast.LENGTH_SHORT).show(); return;
        }
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Launcher tidak mendukung pintasan", Toast.LENGTH_SHORT).show(); return;
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setAction(Intent.ACTION_VIEW);
        open.setData(Uri.parse(tab.url));
        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.blink_brand);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "blink_" + System.currentTimeMillis())
                .setShortLabel(tab.title.length() > 18 ? tab.title.substring(0, 18) : tab.title)
                .setLongLabel(tab.title)
                .setIcon(Icon.createWithBitmap(bitmap))
                .setIntent(open)
                .build();
        manager.requestPinShortcut(info, null);
        Toast.makeText(this, "Permintaan pintasan dikirim ke launcher", Toast.LENGTH_SHORT).show();
    }

    private void showSettings() {
        String engine = preferences.getString("search_engine", "Google");
        new AlertDialog.Builder(this).setTitle("Setelan Blink")
                .setItems(new String[]{"Tema: " + (darkMode ? "Gelap" : "Terang"),
                        "Mesin telusur: " + engine,
                        "Situs desktop: " + (desktopMode ? "Aktif" : "Nonaktif"),
                        "Privasi & keamanan", "Tentang Blink"}, (d, which) -> {
                    if (which == 0) toggleDarkMode();
                    else if (which == 1) new AlertDialog.Builder(this).setTitle("Mesin telusur")
                            .setItems(new String[]{"Google", "Bing", "DuckDuckGo"}, (dialog, index) -> {
                                preferences.edit().putString("search_engine",
                                        new String[]{"Google", "Bing", "DuckDuckGo"}[index]).apply();
                            }).show();
                    else if (which == 2) toggleDesktop();
                    else if (which == 3) showPrivacyDialog();
                    else aboutBlink();
                }).show();
    }

    private JSONArray stored(String key) {
        try { return new JSONArray(preferences.getString(key, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private boolean hasSavedUrl(String key, String url) {
        JSONArray list = stored(key);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && url.equals(item.optString("url"))) return true;
        }
        return false;
    }

    private void remember(String key, String title, String url, int limit) {
        if (!UrlResolver.isWebUrl(url)) return;
        JSONArray before = stored(key);
        JSONArray next = new JSONArray();
        try {
            JSONObject latest = new JSONObject();
            latest.put("title", title);
            latest.put("url", url);
            latest.put("visited", System.currentTimeMillis());
            next.put(latest);
            for (int i = 0; i < before.length() && next.length() < limit; i++) {
                JSONObject old = before.optJSONObject(i);
                if (old != null && !url.equals(old.optString("url"))) next.put(old);
            }
            preferences.edit().putString(key, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void removeSaved(String key, String url) {
        JSONArray before = stored(key);
        JSONArray next = new JSONArray();
        for (int i = 0; i < before.length(); i++) {
            JSONObject entry = before.optJSONObject(i);
            if (entry != null && !url.equals(entry.optString("url"))) next.put(entry);
        }
        preferences.edit().putString(key, next.toString()).apply();
    }

    private void toggleBookmark() {
        Tab tab = current();
        if (tab == null || tab.isHome || !UrlResolver.isWebUrl(tab.url)) {
            Toast.makeText(this, "Buka situs dahulu", Toast.LENGTH_SHORT).show();
            return;
        }
        if (hasSavedUrl(BOOKMARKS, tab.url)) {
            removeSaved(BOOKMARKS, tab.url);
            Toast.makeText(this, "Bookmark dihapus", Toast.LENGTH_SHORT).show();
        } else {
            remember(BOOKMARKS, tab.title, tab.url, 300);
            Toast.makeText(this, "Disimpan ke bookmark", Toast.LENGTH_SHORT).show();
        }
        updateControls();
    }

    private void showEntriesDialog(String key, String title) {
        JSONArray records = stored(key);
        LinearLayout rows = vertical();
        rows.setPadding(dp(12), dp(8), dp(12), dp(12));
        rows.setBackgroundColor(bg());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        if (records.length() == 0) {
            TextView empty = text("Belum ada " + title.toLowerCase(Locale.ROOT), 16, false, secondaryText());
            empty.setPadding(dp(12), dp(30), dp(12), dp(30));
            rows.addView(empty);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setNegativeButton("Tutup", null);
        if (records.length() > 0) builder.setNeutralButton("Hapus semua", (d, w) ->
                new AlertDialog.Builder(this).setTitle("Hapus " + title.toLowerCase(Locale.ROOT) + "?")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Hapus", (di, wi) -> {
                            preferences.edit().remove(key).apply();
                            updateControls();
                        }).show());
        AlertDialog dialog = builder.create();
        for (int i = 0; i < records.length(); i++) {
            JSONObject entry = records.optJSONObject(i);
            if (entry == null) continue;
            String url = entry.optString("url");
            String name = entry.optString("title", url);
            LinearLayout row = horizontal();
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(65));
            rows.addView(row, rowLp);
            LinearLayout pair = vertical();
            pair.setPadding(dp(10), dp(8), 0, 0);
            TextView titleView = text(name, 15, true, primaryText());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            pair.addView(titleView);
            TextView urlView = text(url, 12, false, secondaryText());
            urlView.setSingleLine(true);
            urlView.setEllipsize(TextUtils.TruncateAt.END);
            pair.addView(urlView);
            row.addView(pair, new LinearLayout.LayoutParams(0, -1, 1));
            pair.setOnClickListener(v -> { dialog.dismiss(); openPage(url); });
            TextView delete = iconButton("×", "Hapus", 45, () -> {
                removeSaved(key, url);
                dialog.dismiss();
                showEntriesDialog(key, title);
                updateControls();
            });
            row.addView(delete);
            View sep = new View(this);
            sep.setBackgroundColor(line());
            rows.addView(sep, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        dialog.show();
    }

    private void openDownloads() {
        DownloadCenter.show(this);
    }

    private void findInPage() {
        Tab tab = current();
        if (tab == null || tab.isHome || tab.webView == null) {
            Toast.makeText(this, "Buka situs dahulu", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setPadding(dp(20), dp(15), dp(20), dp(15));
        search.setHint("Kata yang dicari");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Cari di halaman")
                .setView(search)
                .setNegativeButton("Selesai", (d, w) -> tab.webView.clearMatches())
                .setNeutralButton("‹ Sebelumnya", null)
                .setPositiveButton("Cari / Berikutnya ›", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String term = search.getText().toString();
            if (term.isEmpty()) return;
            tab.webView.findAllAsync(term);
            tab.webView.findNext(true);
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String term = search.getText().toString();
            if (term.isEmpty()) return;
            tab.webView.findAllAsync(term);
            tab.webView.findNext(false);
        });
    }

    private void applyCompatibilityMode(WebView view) {
        // Keep the installed Chromium version; only remove WebView-specific UA tokens.
        String ua = WebSettings.getDefaultUserAgent(this);
        view.getSettings().setUserAgentString(ua.replace("; wv", "").replace("Version/4.0 ", ""));
    }

    private void applyDesktopMode(WebView view, boolean enabled) {
        WebSettings settings = view.getSettings();
        if (enabled) {
            // Use the installed WebView Chromium version, not an invented/stale Chrome version.
            String base = WebSettings.getDefaultUserAgent(this);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(base);
            String version = m.find() ? m.group(1) : "120.0.0.0";
            settings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36");
        } else if (compatibilityMode) applyCompatibilityMode(view);
        else settings.setUserAgentString(null);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
    }

    private void toggleDesktop() {
        desktopMode = !desktopMode;
        preferences.edit().putBoolean("desktop_mode", desktopMode).apply();
        for (Tab tab : tabs) {
            if (tab.webView != null) applyDesktopMode(tab.webView, desktopMode);
        }
        Tab tab = current();
        if (tab != null && !tab.isHome && tab.webView != null) tab.webView.reload();
        Toast.makeText(this, desktopMode ? "Situs desktop aktif" : "Situs seluler aktif", Toast.LENGTH_SHORT).show();
    }

    private void toggleDarkMode() {
        darkMode = !darkMode;
        preferences.edit().putBoolean("dark_mode", darkMode).apply();
        buildInterface();
        renderTab();
    }

    private void sharePage() {
        Tab tab = current();
        if (tab == null || tab.isHome || !UrlResolver.isWebUrl(tab.url)) {
            Toast.makeText(this, "Buka situs dahulu untuk membagikan halaman", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, tab.title + "\n" + tab.url);
        startActivity(Intent.createChooser(intent, "Bagikan dari Blink"));
    }

    private void aboutBlink() {
        android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
        String engine = provider == null ? "Penyedia WebView tidak terdeteksi"
                : provider.packageName + " (versi " + provider.versionName + ")";
        new AlertDialog.Builder(this).setTitle("Blink Browser v3.0")
                .setMessage("Engine Chromium System WebView: " + engine + "\n\n" +
                        "Fitur: tab, kelompok tab, pencarian, bookmark, riwayat, unduhan, upload, " +
                        "tema gelap, pencarian dalam halaman, izin kamera/mikrofon, HTTP(S) DownloadManager, " +
                        "file blob hingga 128 MB, dan jendela Samaran dengan proses/penyimpanan terpisah.\n\n" +
                        "Batasan: Blink bukan Chrome; Google Sync, ekstensi Chrome, DRM tertentu, " +
                        "dan sistem penerjemah native Chrome tidak disertakan. Akses situs tergantung " +
                        "Android WebView, server situs, jaringan, serta kebijakan mesin pencari.")
                .setPositiveButton("OK", null).show();
    }

    private void persistSession() {
        if (preferences == null || isolatedPrivate) return;
        JSONArray saved = new JSONArray();
        for (Tab tab : tabs) {
            if (tab.privateMode) continue;
            JSONObject record = new JSONObject();
            try {
                record.put("title", tab.title);
                record.put("group", tab.group);
                record.put("url", tab.isHome ? "about:blank" : tab.url);
                saved.put(record);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString(SESSION, saved.toString()).apply();
    }

    private void restoreSession() {
        JSONArray saved = stored(SESSION);
        for (int i = 0; i < saved.length() && tabs.size() < MAX_TABS; i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "about:blank");
            Tab tab = new Tab(false);
            if (UrlResolver.isWebUrl(url)) {
                tab.url = url;
                tab.isHome = false;
                tab.title = item.optString("title", url);
            }
            tab.group = item.optString("group", "");
            tabs.add(tab);
        }
        if (!tabs.isEmpty()) activeIndex = 0;
    }

    @Override public void onBackPressed() {
        if (fullscreenView != null) { exitFullscreen(); return; }
        Tab tab = current();
        if (tab != null && tab.webView != null && tab.webView.canGoBack()) goBack();
        else if (tab != null && !tab.isHome) showHome();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause();
        Tab tab = current();
        if (tab != null && tab.webView != null) tab.webView.onPause();
        persistSession();
    }

    @Override protected void onResume() {
        super.onResume();
        Tab tab = current();
        if (tab != null && tab.webView != null && !tab.isHome) tab.webView.onResume();
    }

    @Override protected void onDestroy() {
        BlobDownloader.cancelAllFor(this);
        if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
        if (pendingPermissionRequest != null) pendingPermissionRequest.deny();
        if (fullscreenView != null) exitFullscreen();
        persistSession();
        if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
        for (Tab tab : tabs) {
            if (tab.webView != null) {
                if (tab.webView.getParent() instanceof ViewGroup)
                    ((ViewGroup) tab.webView.getParent()).removeView(tab.webView);
                tab.webView.stopLoading();
                if (isolatedPrivate) tab.webView.clearCache(true);
                tab.webView.destroy();
            }
        }
        if (isolatedPrivate) {
            CookieManager.getInstance().removeAllCookies(value -> CookieManager.getInstance().flush());
            WebStorage.getInstance().deleteAllData();
        }
        super.onDestroy();
    }
}
