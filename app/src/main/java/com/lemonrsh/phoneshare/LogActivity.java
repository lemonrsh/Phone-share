package com.lemonrsh.phoneshare;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Log viewer with three sections:
 * <ul>
 *   <li><b>Connection</b>: every step of the connection attempts, the shell
 *       commands and the raw output of the target's shell and scrcpy server.</li>
 *   <li><b>App</b>: this app's own logcat (no permission needed for it).</li>
 *   <li><b>Target</b>: fetched on demand over ADB from the other device
 *       (device info, server self-test, relevant logcat). No root needed.</li>
 * </ul>
 * Each section can be copied or shared.
 */
public class LogActivity extends Activity {
    public static final String EXTRA_HOST = "com.lemonrsh.phoneshare.extra.LOG_HOST";
    public static final String EXTRA_SECTION = "com.lemonrsh.phoneshare.extra.LOG_SECTION";
    public static final int SECTION_CONNECTION_LOG = 0;
    public static final int SECTION_APP_LOG = 1;

    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_DARK_MODE = "dark_mode";
    private static final String PREFERENCE_HOST = "Server Address";
    private static final int MAX_DISPLAY_CHARS = 300_000;
    private static final int MAX_SHARE_CHARS = 200_000;

    private static final int SECTION_CONNECTION = 0;
    private static final int SECTION_APP = 1;
    private static final int SECTION_TARGET = 2;

    private static final String CRASH_FILE = "last_crash.txt";
    private static boolean crashRecorderInstalled;

    /**
     * Saves the stack trace of an app crash to a file so that it can be shown
     * in the App log after the restart. Safe to call more than once.
     */
    public static synchronized void installCrashRecorder(Context context) {
        if (crashRecorderInstalled) {
            return;
        }
        crashRecorderInstalled = true;
        final File file = new File(context.getApplicationContext().getFilesDir(), CRASH_FILE);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try (FileOutputStream output = new FileOutputStream(file)) {
                String text = new java.util.Date() + " – crash on thread " + thread.getName() + "\n"
                        + DiagnosticLog.stackTrace(throwable) + "\n\nConnection log before the crash:\n"
                        + DiagnosticLog.snapshot();
                output.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                // Never make the crash worse.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static String readCrashFile(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 256 * 1024)];
            int length = 0;
            int read;
            while (length < data.length && (read = input.read(data, length, data.length - length)) > 0) {
                length += read;
            }
            return new String(data, 0, length, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Last target log, kept while the process lives so that it survives leaving the screen. */
    private static String targetLog;

    private int section = SECTION_CONNECTION;
    private String currentText = "";
    private boolean loading;
    private int requestGeneration;

    private TextView logText;
    private ScrollView scrollView;
    private TextView hintText;
    private Button[] tabs;
    private Button fetchButton;
    private Button clearButton;
    private int accentColor;
    private int textPrimary;
    private int textSecondary;
    private int onPage;
    private int onPageSecondary;
    private int cardColor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean darkMode = getSharedPreferences(PREFERENCE_KEY, MODE_PRIVATE).getBoolean(PREFERENCE_DARK_MODE, false);
        setTheme(darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        installCrashRecorder(this);
        accentColor = resolveColor(R.attr.colorAccentScrcpy);
        textPrimary = resolveColor(R.attr.colorTextPrimary);
        textSecondary = resolveColor(R.attr.colorTextSecondary);
        onPage = resolveColor(R.attr.colorOnPage);
        onPageSecondary = resolveColor(R.attr.colorOnPageSecondary);
        cardColor = resolveColor(R.attr.colorCardBackground);
        section = getIntent().getIntExtra(EXTRA_SECTION, SECTION_CONNECTION);
        if (savedInstanceState != null) {
            section = savedInstanceState.getInt("section", section);
        }
        if (section < SECTION_CONNECTION || section > SECTION_TARGET) {
            section = SECTION_CONNECTION;
        }
        setContentView(buildLayout());
        // onResume() fills in the selected section.
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("section", section);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSection(section);
    }

    // ---------------------------------------------------------------- layout

    @SuppressWarnings("deprecation")
    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(resolveColor(R.attr.colorPageBackground));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);
        // targetSdk 35+ draws edge-to-edge: keep content clear of the system bars.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        TextView title = new TextView(this);
        title.setText(R.string.logs_title);
        title.setTextColor(onPage);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, dp(10), 0, dp(6));
        String[] labels = {getString(R.string.logs_tab_connection), getString(R.string.logs_tab_app),
                getString(R.string.logs_tab_target)};
        tabs = new Button[labels.length];
        for (int i = 0; i < labels.length; ++i) {
            final int index = i;
            Button tab = new Button(this);
            tab.setText(labels[i]);
            tab.setAllCaps(false);
            tab.setTextSize(13);
            tab.setMinHeight(dp(40));
            tab.setMinimumHeight(dp(40));
            tab.setOnClickListener(view -> showSection(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(i == 0 ? 0 : dp(4), 0, 0, 0);
            tabRow.addView(tab, params);
            tabs[i] = tab;
        }
        root.addView(tabRow);

        hintText = new TextView(this);
        hintText.setTextColor(onPageSecondary);
        hintText.setTextSize(12);
        hintText.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(hintText);

        scrollView = new ScrollView(this);
        GradientDrawable card = new GradientDrawable();
        card.setColor(cardColor);
        card.setCornerRadius(dp(12));
        scrollView.setBackground(card);
        scrollView.setFillViewport(true);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        logText = new TextView(this);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(11);
        logText.setTextColor(textPrimary);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        horizontal.addView(logText, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollView.addView(horizontal, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);
        fetchButton = addAction(actions, getString(R.string.logs_refresh), view -> refresh());
        addAction(actions, getString(R.string.logs_copy), view -> copy());
        addAction(actions, getString(R.string.logs_share), view -> share());
        clearButton = addAction(actions, getString(R.string.logs_clear), view -> {
            if (section == SECTION_APP) {
                // The system log cannot be cleared by apps; forget the saved crash instead.
                new File(getFilesDir(), CRASH_FILE).delete();
            } else {
                DiagnosticLog.clear();
            }
            showSection(section);
        });
        root.addView(actions);
        return root;
    }

    private Button addAction(LinearLayout row, String label, View.OnClickListener listener) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(onPage); // on the green page
        button.setTextSize(13);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }

    // ---------------------------------------------------------------- sections

    private void showSection(int newSection) {
        section = newSection;
        requestGeneration++;
        loading = false;
        for (int i = 0; i < tabs.length; ++i) {
            boolean selected = i == section;
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(20));
            background.setColor(selected ? accentColor : cardColor);
            tabs[i].setBackground(background);
            tabs[i].setTextColor(selected ? 0xFFFFFFFF : textPrimary);
        }
        clearButton.setVisibility(section == SECTION_TARGET ? View.INVISIBLE : View.VISIBLE);
        fetchButton.setText(section == SECTION_TARGET ? R.string.logs_fetch_target : R.string.logs_refresh);
        switch (section) {
            case SECTION_APP:
                hintText.setText(R.string.logs_hint_app);
                loadAppLog();
                break;
            case SECTION_TARGET:
                hintText.setText(getString(R.string.logs_hint_target, targetHostText()));
                setLogText(targetLog != null ? targetLog : getString(R.string.logs_target_empty), false);
                break;
            case SECTION_CONNECTION:
            default:
                hintText.setText(R.string.logs_hint_connection);
                setLogText(DiagnosticLog.isEmpty() ? getString(R.string.logs_connection_empty)
                        : DiagnosticLog.snapshot(), true);
                break;
        }
    }

    private void refresh() {
        if (section == SECTION_TARGET) {
            fetchTargetLog();
        } else {
            showSection(section);
        }
    }

    private void setLogText(String text, boolean scrollToEnd) {
        currentText = text;
        String shown = text.length() > MAX_DISPLAY_CHARS
                ? "[… older lines hidden, use Share for more …]\n" + text.substring(text.length() - MAX_DISPLAY_CHARS)
                : text;
        logText.setText(shown);
        scrollView.post(() -> scrollView.scrollTo(0, scrollToEnd ? logText.getHeight() : 0));
    }

    private void loadAppLog() {
        setLogText(getString(R.string.logs_loading), false);
        final int generation = requestGeneration;
        final String header = deviceHeader();
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            String crash = readCrashFile(appContext);
            String text = header
                    + (crash == null ? "" : "===== LAST APP CRASH =====\n" + crash + "\n===== END OF CRASH =====\n\n")
                    + "===== APP LOGCAT =====\n" + readOwnLogcat();
            runOnUiThread(() -> {
                if (generation == requestGeneration && !isFinishing()) {
                    setLogText(text, true);
                }
            });
        }, "scrcpy-app-log").start();
    }

    /** Apps can always read their own log lines; no READ_LOGS permission is required for that. */
    private static String readOwnLogcat() {
        StringBuilder builder = new StringBuilder();
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", "3000")
                    .redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
                if (builder.length() > 1_000_000) {
                    builder.append("[truncated]\n");
                    break;
                }
            }
            reader.close();
        } catch (Exception e) {
            builder.append("Could not read the app log: ").append(e).append('\n');
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        if (builder.length() == 0) {
            builder.append("(The app log is empty.)\n");
        }
        return builder.toString();
    }

    private String deviceHeader() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // Unknown version.
        }
        return "scrcpy-android " + version + " on " + Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")\n\n";
    }

    private void fetchTargetLog() {
        if (loading) {
            return;
        }
        final HostAddress address = HostAddress.parse(targetHostText());
        if (address == null) {
            Toast.makeText(this, R.string.logs_no_host, Toast.LENGTH_LONG).show();
            return;
        }
        loading = true;
        final int generation = ++requestGeneration;
        setLogText(getString(R.string.logs_target_loading, address.toString()), false);
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            String text;
            try {
                text = "Target log from " + address + "\n\n" + AdbLauncher.collectTargetDiagnostics(appContext, address);
            } catch (AdbLauncher.LaunchException e) {
                text = "Could not fetch the target log from " + address + ":\n" + e.getMessage()
                        + (e.getCause() == null ? "" : "\n\n" + DiagnosticLog.stackTrace(e.getCause()));
            }
            final String result = text;
            targetLog = result;
            runOnUiThread(() -> {
                if (generation == requestGeneration && !isFinishing()) {
                    loading = false;
                    setLogText(result, false);
                }
            });
        }, "scrcpy-target-log").start();
    }

    private String targetHostText() {
        String host = getIntent().getStringExtra(EXTRA_HOST);
        if (host == null || host.trim().isEmpty()) {
            SharedPreferences preferences = getSharedPreferences(PREFERENCE_KEY, MODE_PRIVATE);
            host = preferences.getString(PREFERENCE_HOST, "");
        }
        return host == null ? "" : host.trim();
    }

    // ---------------------------------------------------------------- copy / share

    private String sectionName() {
        return tabs[section].getText().toString();
    }

    private String exportText() {
        String text = currentText;
        if (text.length() > MAX_SHARE_CHARS) {
            text = "[… older lines removed …]\n" + text.substring(text.length() - MAX_SHARE_CHARS);
        }
        return "scrcpy-android – " + sectionName() + " log\n\n" + text;
    }

    private void copy() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("scrcpy log", exportText()));
            Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, getString(R.string.logs_too_large, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "scrcpy-android " + sectionName() + " log");
        intent.putExtra(Intent.EXTRA_TEXT, exportText());
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.logs_share)));
        } catch (RuntimeException e) {
            Toast.makeText(this, getString(R.string.logs_too_large, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- helpers

    private int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
