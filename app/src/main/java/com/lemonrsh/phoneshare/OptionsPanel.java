package com.lemonrsh.phoneshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the additional scrcpy options ({@link AdvancedOptions}) as collapsible
 * groups inside the options box. Every change is saved immediately, so the
 * preferences are always the source of truth when a session starts.
 */
final class OptionsPanel {
    private static final String PREF_EXPANDED_PREFIX = "adv_group_open_";

    private final Context context;
    private final SharedPreferences preferences;
    private final LinearLayout container;
    private final int textPrimary;
    private final int textSecondary;
    private final int accent;
    private final Map<String, TextView> headers = new HashMap<>();
    private final List<Runnable> resetters = new ArrayList<>();

    OptionsPanel(Context context, SharedPreferences preferences, LinearLayout container) {
        this.context = context;
        this.preferences = preferences;
        this.container = container;
        this.textPrimary = themeColor(R.attr.colorTextPrimary);
        this.textSecondary = themeColor(R.attr.colorTextSecondary);
        this.accent = themeColor(R.attr.colorAdvancedAccent);
    }

    /** Stored values (option key to value) for {@link AdvancedOptions#apply}. */
    static Map<String, String> storedValues(SharedPreferences preferences) {
        Map<String, String> values = new HashMap<>();
        for (AdvancedOptions.Spec spec : AdvancedOptions.SPECS) {
            String value = preferences.getString(spec.prefKey(), null);
            if (value != null) {
                values.put(spec.key, value);
            }
        }
        return values;
    }

    void build() {
        container.removeAllViews();
        headers.clear();
        resetters.clear();
        for (String group : AdvancedOptions.GROUPS) {
            buildGroup(group);
        }
        Button reset = new Button(context, null, android.R.attr.borderlessButtonStyle);
        reset.setText(R.string.options_reset);
        reset.setAllCaps(false);
        reset.setTextColor(accent);
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.END;
        params.topMargin = dp(4);
        container.addView(reset, params);
        reset.setOnClickListener(view -> {
            SharedPreferences.Editor editor = preferences.edit();
            for (AdvancedOptions.Spec spec : AdvancedOptions.SPECS) {
                editor.remove(spec.prefKey());
            }
            editor.apply();
            for (Runnable resetter : resetters) {
                resetter.run();
            }
            for (String group : AdvancedOptions.GROUPS) {
                updateHeader(group);
            }
        });
    }

    private void buildGroup(String group) {
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(4), 0, 0, dp(6));

        TextView header = new TextView(context);
        header.setTextColor(textPrimary);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        header.setMinHeight(dp(48));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(selectableBackground());
        header.setTag(group);
        container.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        headers.put(group, header);

        for (AdvancedOptions.Spec spec : AdvancedOptions.inGroup(group)) {
            addOption(body, spec);
        }

        final String expandedKey = PREF_EXPANDED_PREFIX + group;
        boolean expanded = preferences.getBoolean(expandedKey, false);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        updateHeader(group);
        header.setOnClickListener(view -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            preferences.edit().putBoolean(expandedKey, open).apply();
            updateHeader(group);
        });
    }

    private void updateHeader(String group) {
        TextView header = headers.get(group);
        if (header == null) {
            return;
        }
        int changed = 0;
        for (AdvancedOptions.Spec spec : AdvancedOptions.inGroup(group)) {
            String value = preferences.getString(spec.prefKey(), spec.defaultValue);
            if (value != null && !value.trim().equals(spec.defaultValue)) {
                ++changed;
            }
        }
        boolean open = preferences.getBoolean(PREF_EXPANDED_PREFIX + group, false);
        String arrow = open ? "▾  " : "▸  ";
        header.setText(arrow + group + (changed > 0 ? "  ·  " + context.getResources()
                .getQuantityString(R.plurals.options_changed, changed, changed) : ""));
    }

    private void addOption(LinearLayout body, final AdvancedOptions.Spec spec) {
        final String key = spec.prefKey();
        String stored = preferences.getString(key, spec.defaultValue);
        switch (spec.type) {
            case SWITCH: {
                final Switch view = new Switch(context);
                view.setText(spec.label);
                view.setTextColor(textPrimary);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                view.setMinHeight(dp(48));
                view.setChecked(Boolean.parseBoolean(stored));
                view.setOnCheckedChangeListener((button, checked) -> save(spec, Boolean.toString(checked)));
                body.addView(view, matchWidth());
                resetters.add(() -> view.setChecked(Boolean.parseBoolean(spec.defaultValue)));
                break;
            }
            case CHOICE: {
                addLabel(body, spec.label);
                final Spinner spinner = new Spinner(context);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                        spec.choiceLabels);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
                spinner.setMinimumHeight(dp(48));
                int index = spec.indexOfValue(stored);
                spinner.setSelection(index < 0 ? Math.max(0, spec.indexOfValue(spec.defaultValue)) : index, false);
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        if (position >= 0 && position < spec.choiceValues.length) {
                            save(spec, spec.choiceValues[position]);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // Keep the stored value.
                    }
                });
                body.addView(spinner, matchWidth());
                resetters.add(() -> spinner.setSelection(Math.max(0, spec.indexOfValue(spec.defaultValue))));
                break;
            }
            case NUMBER:
            case TEXT:
            default: {
                addLabel(body, spec.label);
                final EditText edit = new EditText(context);
                edit.setSingleLine(true);
                edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                edit.setMinHeight(dp(48));
                edit.setInputType(spec.type == AdvancedOptions.Type.NUMBER
                        ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                if (spec.hint != null) {
                    edit.setHint(context.getString(R.string.options_example, spec.hint));
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
                }
                edit.setText(stored);
                edit.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        save(spec, s.toString().trim());
                    }
                });
                body.addView(edit, matchWidth());
                resetters.add(() -> edit.setText(spec.defaultValue));
                break;
            }
        }
        if (spec.help != null) {
            TextView help = new TextView(context);
            help.setText(spec.help);
            help.setTextColor(textSecondary);
            help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams params = matchWidth();
            params.bottomMargin = dp(6);
            body.addView(help, params);
        }
    }

    private void save(AdvancedOptions.Spec spec, String value) {
        String current = preferences.getString(spec.prefKey(), spec.defaultValue);
        if (value.equals(current)) {
            return;
        }
        if (value.equals(spec.defaultValue)) {
            preferences.edit().remove(spec.prefKey()).apply();
        } else {
            preferences.edit().putString(spec.prefKey(), value).apply();
        }
        updateHeader(spec.group);
    }

    private void addLabel(LinearLayout body, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(textPrimary);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(8);
        body.addView(label, params);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int selectableBackground() {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private int themeColor(int attr) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return context.getResources().getColor(value.resourceId);
                } catch (Resources.NotFoundException ignored) {
                    // Fall through to the raw value.
                }
            }
            return value.data;
        }
        return 0xFF000000;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
