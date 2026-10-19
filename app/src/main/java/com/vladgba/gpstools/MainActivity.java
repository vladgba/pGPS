package com.vladgba.gpstools;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.gnss.IntegrityReport;
import com.vladgba.gpstools.gnss.NmeaFix;
import com.vladgba.gpstools.ui.Format;
import com.vladgba.gpstools.ui.IntegrityPage;
import com.vladgba.gpstools.record.RecorderService;
import com.vladgba.gpstools.ui.Page;
import com.vladgba.gpstools.ui.SatellitesPage;
import com.vladgba.gpstools.ui.SkyPage;
import com.vladgba.gpstools.ui.StatusPage;
import com.vladgba.gpstools.ui.ToolsPage;

import java.util.Locale;

public final class MainActivity extends Activity implements GnssMonitor.Listener {

    private static final int REQUEST_LOCATION = 1;
    private static final int TOOLS_TAB = 4;
    private static final String STATE_TAB = "tab";
    private static final String PREFS = "settings";
    private static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";

    private GnssMonitor monitor;
    private Page[] pages;
    private TextView[] tabs;
    private TextView integrityTab;
    private TextView toolsTab;
    private int currentTab;

    private FrameLayout content;
    private TextView fixChip;
    private GradientDrawable fixChipBackground;
    private View banner;
    private TextView bannerText;
    private Button bannerAction;

    /** Set once the user refused with "don't ask again": we can only send them to settings. */
    private boolean permissionPermanentlyDenied;
    /** Avoids re-prompting in a loop: the dialog itself triggers onStop/onStart. */
    private boolean permissionRequestInFlight;
    private boolean askedThisSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enableEdgeToEdge();

        monitor = GnssMonitor.get(this);
        content = findViewById(R.id.content);
        fixChip = findViewById(R.id.fix_chip);
        fixChipBackground = new GradientDrawable();
        fixChipBackground.setCornerRadius(getResources().getDisplayMetrics().density * 12);
        fixChip.setBackground(fixChipBackground);
        banner = findViewById(R.id.banner);
        bannerText = findViewById(R.id.banner_text);
        bannerAction = findViewById(R.id.banner_action);
        findViewById(R.id.menu_button).setOnClickListener(this::showMenu);

        pages = new Page[]{
                new StatusPage(this),
                new SatellitesPage(this),
                new SkyPage(this),
                new IntegrityPage(this),
                new ToolsPage(this, this::updateBackHandling)};
        tabs = new TextView[]{
                findViewById(R.id.tab_status),
                findViewById(R.id.tab_satellites),
                findViewById(R.id.tab_sky),
                findViewById(R.id.tab_integrity),
                findViewById(R.id.tab_tools)};
        integrityTab = tabs[3];
        toolsTab = tabs[4];
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(v -> selectTab(index));
        }

        applyKeepScreenOn(prefs().getBoolean(PREF_KEEP_SCREEN_ON, false));
        int tab = savedInstanceState != null ? savedInstanceState.getInt(STATE_TAB, 0) : 0;
        selectTab(Math.max(0, Math.min(pages.length - 1, tab)));
        if (savedInstanceState == null) handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    /** Text shared to the app (voice code, coordinates) or a geo: link opens the decoder. */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        String text = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null
                && "geo".equalsIgnoreCase(intent.getData().getScheme())) {
            text = intent.getData().toString();
        }
        if (text == null || text.trim().isEmpty()) return;
        selectTab(TOOLS_TAB);
        ((ToolsPage) pages[TOOLS_TAB]).openVoiceCodeWithInput(text);
        updateBackHandling();
    }

    @Override
    protected void onStart() {
        super.onStart();
        monitor.addListener(this);
        startOrRequestPermission();
        refreshChrome();
    }

    @Override
    protected void onStop() {
        monitor.removeListener(this);
        // The recorder service may keep GNSS running; the monitor stops when nobody holds it.
        monitor.release(this);
        super.onStop();
    }

    // ---- Back: leaves Tools sub-pages before leaving the app ----------------------------------

    private Object backCallback;

    /** Registers a back handler only while a page has somewhere to go back to (predictive back). */
    private void updateBackHandling() {
        boolean wanted = pages[currentTab].canGoBack();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (wanted && backCallback == null) {
                android.window.OnBackInvokedCallback cb = () -> {
                    pages[currentTab].goBack();
                    updateBackHandling();
                };
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
                backCallback = cb;
            } else if (!wanted && backCallback != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
                backCallback = null;
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("GestureBackNavigation") // API 33+ uses the OnBackInvokedCallback above
    public void onBackPressed() {
        // Only reached below API 33 (or with predictive back disabled).
        if (pages[currentTab].canGoBack()) {
            pages[currentTab].goBack();
            updateBackHandling();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Declared in the manifest so rotation keeps the GNSS session; layouts are fluid.
        super.onConfigurationChanged(newConfig);
    }

    // ---- Permission -------------------------------------------------------------------------

    private void startOrRequestPermission() {
        if (monitor.hasPermission()) {
            monitor.acquire(this);
        } else if (!askedThisSession && !permissionRequestInFlight) {
            requestLocationPermission();
        }
    }

    private void requestLocationPermission() {
        askedThisSession = true;
        permissionRequestInFlight = true;
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) return;
        permissionRequestInFlight = false;
        if (monitor.hasPermission()) {
            permissionPermanentlyDenied = false;
            monitor.acquire(this);
        } else {
            // false right after a refusal means "don't ask again" (or a policy block).
            permissionPermanentlyDenied =
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        refreshChrome();
    }

    private void openAppSettings() {
        startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    // ---- GNSS updates -----------------------------------------------------------------------

    @Override
    public void onGnssStateChanged(GnssMonitor m) {
        refreshChrome();
        pages[currentTab].update(m);
    }

    @Override
    public void onNmeaMessage(GnssMonitor m, String sentence) {
        pages[currentTab].onNmeaMessage(m, sentence);
    }

    /** Top bar chip and the warning banner. */
    private void refreshChrome() {
        int chipText;
        int chipColor;
        boolean fresh = monitor.hasFreshFix();
        if (!monitor.hasPermission()) {
            chipText = R.string.chip_no_access;
            chipColor = R.color.state_off;
        } else if (!monitor.isProviderEnabled()) {
            chipText = R.string.chip_gps_off;
            chipColor = R.color.state_bad;
        } else if (!monitor.isRunning()) {
            chipText = R.string.chip_stopped;
            chipColor = R.color.state_off;
        } else if (fresh) {
            int fixType = monitor.isNmeaFresh() ? monitor.getNmeaFix().getFixType() : NmeaFix.FIX_TYPE_UNKNOWN;
            chipText = fixType == NmeaFix.FIX_TYPE_3D ? R.string.chip_fix_3d
                    : fixType == NmeaFix.FIX_TYPE_2D ? R.string.chip_fix_2d : R.string.chip_fix;
            chipColor = fixType == NmeaFix.FIX_TYPE_2D ? R.color.state_warn : R.color.state_good;
        } else {
            chipText = R.string.chip_searching;
            chipColor = R.color.state_warn;
        }
        fixChip.setText(chipText);
        fixChipBackground.setColor(getColor(chipColor));

        // Jamming/spoofing must be noticed from any tab, not only on the Integrity page.
        IntegrityReport.Level threat = monitor.isRunning()
                ? monitor.getIntegrityReport().worst() : IntegrityReport.Level.UNKNOWN;
        boolean flagged = threat == IntegrityReport.Level.WARN || threat == IntegrityReport.Level.ALERT;
        integrityTab.setText(flagged ? R.string.tab_integrity_alert : R.string.tab_integrity);
        if (flagged) {
            integrityTab.setTextColor(getColor(threat == IntegrityReport.Level.ALERT ? R.color.state_bad : R.color.state_warn));
        } else {
            integrityTab.setTextColor(getColorStateList(R.color.tab_text));
        }
        boolean recording = RecorderService.isRecording()
                || com.vladgba.gpstools.nav.BacktrackService.getMode() != com.vladgba.gpstools.nav.BacktrackService.Mode.OFF;
        toolsTab.setText(recording ? R.string.tab_tools_recording : R.string.tab_tools);
        if (recording) toolsTab.setTextColor(getColor(R.color.state_bad));
        else toolsTab.setTextColor(getColorStateList(R.color.tab_text));

        if (!monitor.hasPermission()) {
            showBanner(permissionPermanentlyDenied ? R.string.banner_permission_denied : R.string.banner_permission,
                    permissionPermanentlyDenied ? R.string.banner_open_settings : R.string.banner_grant,
                    permissionPermanentlyDenied ? v -> openAppSettings() : v -> requestLocationPermission());
        } else if (!monitor.isProviderEnabled()) {
            showBanner(R.string.banner_gps_off, R.string.banner_open_settings,
                    v -> startSafely(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    private void showBanner(int textRes, int actionRes, View.OnClickListener action) {
        bannerText.setText(textRes);
        bannerAction.setText(actionRes);
        bannerAction.setOnClickListener(action);
        banner.setVisibility(View.VISIBLE);
    }

    // ---- Tabs -------------------------------------------------------------------------------

    private void selectTab(int index) {
        if (pages[currentTab] != null && currentTab != index) pages[currentTab].onHidden();
        currentTab = index;
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == index);
        content.removeAllViews();
        content.addView(pages[index].getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages[index].update(monitor);
        updateBackHandling();
    }

    // ---- Menu -------------------------------------------------------------------------------

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
        popup.getMenu().findItem(R.id.action_keep_screen_on)
                .setChecked(prefs().getBoolean(PREF_KEEP_SCREEN_ON, false));
        popup.setOnMenuItemClickListener(this::onMenuItem);
        popup.show();
    }

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_keep_screen_on) {
            boolean value = !item.isChecked();
            prefs().edit().putBoolean(PREF_KEEP_SCREEN_ON, value).apply();
            applyKeepScreenOn(value);
            return true;
        }
        if (id == R.id.action_clear_aiding) {
            confirmClearAidingData();
            return true;
        }
        Location loc = monitor.getLocation();
        if (loc == null) {
            toast(R.string.toast_no_position);
            return true;
        }
        String coordinates = Format.coordinates(loc.getLatitude(), loc.getLongitude());
        if (id == R.id.action_copy) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), coordinates));
            // Android 13+ shows its own clipboard confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.toast_copied);
        } else if (id == R.id.action_open_map) {
            String geo = String.format(Locale.ROOT, "geo:%1$.7f,%2$.7f?q=%1$.7f,%2$.7f",
                    loc.getLatitude(), loc.getLongitude());
            startSafely(new Intent(Intent.ACTION_VIEW, Uri.parse(geo)));
        } else if (id == R.id.action_share) {
            String osm = String.format(Locale.ROOT,
                    "https://www.openstreetmap.org/?mlat=%1$.7f&mlon=%2$.7f#map=17/%1$.7f/%2$.7f",
                    loc.getLatitude(), loc.getLongitude());
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                    .putExtra(Intent.EXTRA_TEXT, coordinates + "\n" + osm);
            startSafely(Intent.createChooser(send, getString(R.string.menu_share)));
        } else {
            return false;
        }
        return true;
    }

    private void confirmClearAidingData() {
        if (!monitor.hasPermission()) {
            toast(R.string.toast_need_permission);
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.dialog_clear_title)
                .setMessage(R.string.dialog_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dialog_clear_confirm, (d, w) ->
                        toast(monitor.clearAidingData() ? R.string.toast_aiding_cleared : R.string.toast_aiding_failed))
                .show();
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private void applyKeepScreenOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void startSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.toast_no_app);
        }
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    /**
     * Draws behind the system bars on every API level (mandatory from targetSdk 35) and pads the
     * root by the bar and cutout insets so nothing ends up under them.
     */
    @SuppressWarnings("deprecation")
    private void enableEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }
}
