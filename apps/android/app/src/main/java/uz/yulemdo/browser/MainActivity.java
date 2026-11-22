package uz.yulemdo.browser;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kirish nuqtasi: onCreate, onNewIntent, onDestroy, orqaga tugmasi. */
public class MainActivity extends MenuActivity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (runtime == null) runtime = GeckoRuntime.create(getApplicationContext());
        prefs = getSharedPreferences("yulemdo", MODE_PRIVATE);
        if (prefs.contains("dark")) {
            dark = prefs.getBoolean("dark", false);
            themeKnown = true;
        } else {
            dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }
        lang = prefs.getString("lang", "uz");
        assignPalette();

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9102);
            }
        }

        host = new FrameLayout(this);
        host.setBackgroundColor(cBg);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(cBar);
        bar.setPadding(dp(6), dp(6), dp(2), dp(6));

        btnHome = iconBtn(R.drawable.ic_home, ORANGE_DEEP, v -> goHome());
        btnHome.setContentDescription(tr("home"));

        pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(fieldBg(false));

        favView = new ImageView(this);
        favView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(20), dp(20));
        fp.setMargins(dp(12), 0, dp(4), 0);
        pill.addView(favView, fp);

        addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setHint(tr("hint"));
        addr.setHintTextColor(cMuted);
        addr.setTextColor(cText);
        addr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        addr.setBackground(null);
        addr.setPadding(dp(6), 0, dp(4), 0);
        addr.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addr.setImeOptions(EditorInfo.IME_ACTION_GO);
        addr.setSelectAllOnFocus(true);
        addr.setOnEditorActionListener((v, actionId, e) -> {
            go(addr.getText().toString());
            addr.clearFocus();
            hideKb();
            return true;
        });
        addr.setOnFocusChangeListener((v, has) -> {
            pill.setBackground(fieldBg(has));
            pill.animate().scaleX(has ? 1.01f : 1f).scaleY(has ? 1.04f : 1f).setDuration(160).start();
            if (!has) refreshUi();
        });
        pill.addView(addr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Yangilash tugmasi o'rniga — minimal "User agent" tugmasi.
        // (Yangilash/To'xtatish menyudagi yuqori qatorda saqlanib qoldi.)
        btnUa = iconBtn(0, cText, v -> showUaMenu());
        uaIcon = new UaIcon(currentUa().desktop);
        btnUa.setImageDrawable(uaIcon);
        btnUa.setContentDescription(tr("uaTitle"));
        btnUa.setPadding(dp(10), dp(10), dp(10), dp(10));
        pill.addView(btnUa, new LinearLayout.LayoutParams(dp(40), dp(40)));

        tabCount = new TextView(this);
        tabCount.setGravity(Gravity.CENTER);
        tabCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tabCount.setTypeface(null, Typeface.BOLD);
        tabCount.setTextColor(cText);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(dp(6));
        tg.setStroke(dp(2), ORANGE_DEEP);
        tabCount.setBackground(tg);
        FrameLayout tabBtn = new FrameLayout(this);
        tabBtn.addView(tabCount, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        tabBtn.setContentDescription(tr("tabs"));
        pressAnim(tabBtn);
        tabBtn.setOnClickListener(v -> openSwitcher());
        tabBtn.setBackgroundResource(ripple());

        btnMenu = iconBtn(R.drawable.ic_menu, cText, this::showMenu);
        btnMenu.setContentDescription(tr("settings"));
        btnMenu.setPadding(dp(7), dp(7), dp(7), dp(7));
        menuHolder = new FrameLayout(this);
        menuHolder.addView(btnMenu, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));

        bar.addView(btnHome, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        pp.setMargins(dp(2), 0, dp(2), 0);
        bar.addView(pill, pp);
        bar.addView(tabBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        bar.addView(menuHolder, new LinearLayout.LayoutParams(dp(40), dp(42)));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ORANGE_DEEP));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        FrameLayout content = new FrameLayout(this);
        geckoView = new GeckoView(this);
        content.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        host.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildSwitcher();
        initExtension();

        if (!splashDone) {
            getWindow().setStatusBarColor(SplashView.BG);
            getWindow().setNavigationBarColor(SplashView.BG);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            splash = new SplashView(this);
            host.addView(splash, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            splash.start(this::endSplash);
            host.postDelayed(this::endSplash, SplashView.TOTAL_MS + 700);
        } else {
            applyBars();
        }

        setContentView(host);
        Uri data = getIntent() != null ? getIntent().getData() : null;
        newTab(data != null ? data.toString() : HOME);
        updateMenuAlert();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) newTab(intent.getData().toString());
    }

    @Override
    protected void onDestroy() {
        if (spinAnim != null) spinAnim.cancel();
        if (menuPulse != null) menuPulse.cancel();
        if (host != null && hideVeil != null) host.removeCallbacks(hideVeil);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (browserPromptOverlay != null) { closeDefaultBrowserDialog(); return; }
        if (menuOpen()) { closeMenu(); return; }
        if (switcherOpen()) { closeSwitcher(); return; }
        if (addr != null && addr.hasFocus()) { addr.clearFocus(); refreshUi(); return; }
        if (aliveNav() && active.canBack) { allowSameTab = true; active.session.goBack(); }
        else if (tabs.size() > 1) closeTab(active);
        else finish();
    }
}
