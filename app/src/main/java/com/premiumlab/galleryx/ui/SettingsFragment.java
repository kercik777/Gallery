package com.premiumlab.galleryx.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.premiumlab.galleryx.MaskingSettingsActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.RootPickerActivity;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.dialog.AboutDialog;
import com.premiumlab.galleryx.ui.sheet.GridSheet;
import com.premiumlab.galleryx.ui.sheet.ThemeSheet;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Theming;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Настройки в духе GorillaGallery: поиск по настройкам, группы «стеклянных» карточек,
 * у каждой строки — иконка, заголовок, подзаголовок и переключатель/значение.
 * Наши разделы (маскировка, корневая папка, PIN) остаются; при «закрытой» маскировке
 * всё, что выдаёт секрет, скрыто.
 */
public class SettingsFragment extends Fragment {

    private static final int REQ_ROOT = 503;

    private LinearLayout container;
    private EditText editSearch;
    private View btnClear;
    private String query = "";
    private final List<Group> groups = new ArrayList<>();

    /** Группа настроек: заголовок + карточка со строками. */
    private static final class Group {
        final View root;
        final LinearLayout rows;
        final List<Row> items = new ArrayList<>();

        Group(View root, LinearLayout rows) {
            this.root = root;
            this.rows = rows;
        }
    }

    /** Строка настроек. */
    private static final class Row {
        final View root;
        final View divider;
        final TextView title, sub, value;
        final MaterialSwitch sw;
        final ImageView chevron;
        String searchText = "";
        boolean visible = true;
        Runnable refresher;
        /** Дополнительное условие показа (null — всегда). */
        java.util.function.BooleanSupplier shownIf;

        Row(View root) {
            this.root = root;
            divider = root.findViewById(R.id.viewRowDivider);
            title = root.findViewById(R.id.txtRowTitle);
            sub = root.findViewById(R.id.txtRowSub);
            value = root.findViewById(R.id.txtRowValue);
            sw = root.findViewById(R.id.switchRow);
            chevron = root.findViewById(R.id.imgRowChevron);
        }
    }

    private interface BoolGet {
        boolean get();
    }

    private interface BoolSet {
        void set(boolean v);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(v.findViewById(R.id.scrollSettings));
        container = v.findViewById(R.id.layoutSettings);
        editSearch = v.findViewById(R.id.editSettingsSearch);
        btnClear = v.findViewById(R.id.btnSettingsSearchClear);
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                btnClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter();
            }
        });
        btnClear.setOnClickListener(x -> editSearch.setText(""));
        build();
    }

    // ---------- Построение ----------

    private void build() {
        container.removeAllViews();
        groups.clear();
        maskSensitive.clear();
        FragmentActivity act = requireActivity();

        // ----- Оформление (как у Gorilla: тема, сетка, группировка, HQ, ползунок) -----
        Group look = group(R.string.settings_appearance);
        Row theme = row(look, R.drawable.ic_palette, getString(R.string.settings_theme), null,
                () -> {
                    ThemeSheet sheet = new ThemeSheet();
                    sheet.setListener(() -> requireActivity().recreate());
                    sheet.show(getParentFragmentManager(), "theme");
                });
        theme.refresher = () -> {
            int t = Prefs.theme();
            String mode = getString(t == Prefs.THEME_LIGHT ? R.string.settings_theme_light
                    : t == Prefs.THEME_DARK ? R.string.settings_theme_dark
                    : t == Prefs.THEME_AMOLED ? R.string.settings_theme_amoled
                    : R.string.settings_theme_system);
            theme.sub.setText(mode + " • " + getString(Theming.current().label));
        };
        Row language = row(look, R.drawable.ic_language, getString(R.string.settings_language),
                null, this::pickLanguage);
        language.refresher = () -> language.sub.setText(languageLabel(currentLanguage()));
        Row slider = row(look, R.drawable.ic_linear_scale, getString(R.string.settings_slider_style),
                null, this::pickSliderStyle);
        slider.refresher = () -> slider.sub.setText(sliderLabel(Prefs.sliderStyle()));
        Row grid = row(look, R.drawable.ic_grid_view, getString(R.string.settings_grid),
                getString(R.string.grid_hint), () -> {
                    GridSheet sheet = new GridSheet();
                    sheet.setListener(c -> refresh());
                    sheet.show(getParentFragmentManager(), "grid");
                });
        grid.refresher = () -> grid.value.setText(String.valueOf(Prefs.columns()));
        grid.value.setVisibility(View.VISIBLE);
        Row gridAlbums = row(look, R.drawable.ic_folder_open,
                getString(R.string.settings_grid_albums), null, () -> {
                    GridSheet sheet = GridSheet.forAlbums();
                    sheet.setListener(c -> refresh());
                    sheet.show(getParentFragmentManager(), "grid_albums");
                });
        gridAlbums.refresher = () -> gridAlbums.value.setText(String.valueOf(Prefs.albumColumns()));
        gridAlbums.value.setVisibility(View.VISIBLE);
        Row groupBy = row(look, R.drawable.ic_date_range, getString(R.string.settings_group_by),
                getString(R.string.settings_group_by_sub), () -> pickGroupBy());
        groupBy.refresher = () -> groupBy.value.setText(groupByLabel(Prefs.groupBy()));
        groupBy.value.setVisibility(View.VISIBLE);
        toggle(look, R.drawable.ic_image, getString(R.string.settings_hq_thumbs),
                getString(R.string.settings_hq_thumbs_sub), Prefs::hqThumbs, Prefs::setHqThumbs);

        // ----- Общие -----
        Group general = group(R.string.settings_general);
        toggle(general, R.drawable.ic_loop, getString(R.string.settings_loop),
                getString(R.string.settings_loop_sub), Prefs::loopVideos, Prefs::setLoopVideos);
        toggle(general, R.drawable.ic_volume_on, getString(R.string.settings_video_sound),
                getString(R.string.settings_video_sound_sub), Prefs::videoSoundDefault,
                Prefs::setVideoSoundDefault);
        toggle(general, R.drawable.ic_notifications_off, getString(R.string.settings_quiet),
                getString(R.string.settings_quiet_sub), Prefs::quiet, Prefs::setQuiet);
        toggle(general, R.drawable.ic_delete, getString(R.string.settings_use_trash),
                getString(R.string.settings_use_trash_sub), Prefs::useTrash, v -> {
                    if (!v) {
                        new MaterialAlertDialogBuilder(act)
                                .setTitle(R.string.settings_use_trash)
                                .setMessage(R.string.settings_use_trash_warn)
                                .setPositiveButton(R.string.ok, (d, w) -> {
                                    Prefs.setUseTrash(false);
                                    refresh();
                                })
                                .setNegativeButton(R.string.cancel, (d, w) -> refresh())
                                .setOnCancelListener(d -> refresh())
                                .show();
                    } else {
                        Prefs.setUseTrash(true);
                    }
                });
        toggle(general, R.drawable.ic_pip, getString(R.string.settings_pip),
                getString(R.string.settings_pip_sub), Prefs::pip, Prefs::setPip);
        toggle(general, R.drawable.ic_unfold_more, getString(R.string.settings_fast_scroll),
                getString(R.string.settings_fast_scroll_sub), Prefs::fastScroll, Prefs::setFastScroll);
        // Поиск дубликатов: сначала выбор «фото или видео», дальше сканирование
        row(general, R.drawable.ic_copy, getString(R.string.settings_duplicates),
                getString(R.string.settings_duplicates_sub),
                () -> startActivity(new Intent(act,
                        com.premiumlab.galleryx.DuplicatesHubActivity.class)));

        // ----- Приватность (наше: маскировка с PIN/сейфом внутри, корневая папка) -----
        Group privacy = group(R.string.settings_privacy);
        Row masking = row(privacy, R.drawable.ic_lock, getString(R.string.masking_title),
                null, () -> startActivity(new Intent(act, MaskingSettingsActivity.class)));
        masking.refresher = () -> masking.sub.setText(Prefs.masking()
                ? R.string.masking_status_on : R.string.masking_status_off);
        Row root = row(privacy, R.drawable.ic_folder, getString(R.string.settings_root),
                null, () -> startActivityForResult(new Intent(act, RootPickerActivity.class), REQ_ROOT));
        root.refresher = () -> {
            String p = Prefs.rootPath();
            root.sub.setText(p == null ? getString(R.string.root_not_set) : p);
        };
        // «Вход по отпечатку или лицу» переехал в настройки маскировки:
        // это часть защиты маскировки и сейфа
        // Эти строки выдают «секрет» — при закрытой маскировке прячем
        maskSensitive.add(masking);
        maskSensitive.add(root);

        // ----- О приложении (при закрытой маскировке скрыт целиком) -----
        Group about = group(R.string.settings_about_section);
        Row ver = row(about, R.drawable.ic_info, getString(R.string.app_name), null,
                () -> AboutDialog.show(act));
        ver.refresher = () -> {
            try {
                String version = act.getPackageManager()
                        .getPackageInfo(act.getPackageName(), 0).versionName;
                ver.sub.setText(getString(R.string.settings_version, version));
            } catch (Exception e) {
                ver.sub.setText(getString(R.string.settings_version, "—"));
            }
        };
        // Связь с разработчиком: Telegram-канал и письмо на почту
        Row tg = row(about, R.drawable.ic_telegram, getString(R.string.about_telegram_title),
                getString(R.string.about_telegram_sub),
                () -> com.premiumlab.galleryx.util.Contact.openTelegram(act));
        Row mail = row(about, R.drawable.ic_mail, getString(R.string.about_contact_title),
                getString(R.string.about_contact_sub),
                () -> com.premiumlab.galleryx.util.Contact.emailDeveloper(act));
        Row priv = row(about, R.drawable.ic_shield_check, getString(R.string.about_privacy_title),
                getString(R.string.about_privacy), null);
        // Журнал ошибок: появляется только если что-то было записано
        Row crash = row(about, R.drawable.ic_history, getString(R.string.crash_log_title), null,
                () -> com.premiumlab.galleryx.ui.dialog.CrashLogDialog.show(act, this::refresh));
        crash.refresher = () -> crash.sub.setText(com.premiumlab.galleryx.util.CrashLog.exists()
                ? getString(R.string.crash_log_sub, com.premiumlab.galleryx.util.Fmt.dateTimeShort(
                        com.premiumlab.galleryx.util.CrashLog.lastModified()))
                : "");
        crash.shownIf = com.premiumlab.galleryx.util.CrashLog::exists;
        maskSensitive.add(ver);
        maskSensitive.add(tg);
        maskSensitive.add(mail);
        maskSensitive.add(priv);
        maskSensitive.add(crash);

        refresh();
    }

    // ---------- Язык ----------

    private static final String[] LANG_CODES = {"", "ru", "en"};

    /** Текущий язык приложения: "" — как в системе, иначе код языка. */
    private String currentLanguage() {
        androidx.core.os.LocaleListCompat l = AppCompatDelegate.getApplicationLocales();
        if (l.isEmpty() || l.get(0) == null) return "";
        String lang = l.get(0).getLanguage();
        return "ru".equals(lang) ? "ru" : "en".equals(lang) ? "en" : "";
    }

    private String languageLabel(String code) {
        switch (code) {
            case "ru":
                return getString(R.string.language_ru);
            case "en":
                return getString(R.string.language_en);
            default:
                return getString(R.string.language_system);
        }
    }

    private void pickLanguage() {
        String[] labels = {languageLabel(""), languageLabel("ru"), languageLabel("en")};
        String cur = currentLanguage();
        int checked = cur.equals("ru") ? 1 : cur.equals("en") ? 2 : 0;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    String code = LANG_CODES[which];
                    // AppCompat применяет язык ко всем экранам и сам пересоздаёт их
                    AppCompatDelegate.setApplicationLocales(code.isEmpty()
                            ? androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                            : androidx.core.os.LocaleListCompat.forLanguageTags(code));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickSliderStyle() {
        String[] labels = {sliderLabel(0), sliderLabel(1), sliderLabel(2)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_slider_style)
                .setSingleChoiceItems(labels, Math.max(0, Math.min(2, Prefs.sliderStyle())), (d, which) -> {
                    Prefs.setSliderStyle(which);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String sliderLabel(int style) {
        switch (style) {
            case 1:
                return getString(R.string.settings_slider_thick);
            case 2:
                return getString(R.string.settings_slider_slim);
            default:
                return getString(R.string.settings_slider_thin);
        }
    }

    private final List<Row> maskSensitive = new ArrayList<>();

    private Group group(int titleRes) {
        View v = LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_group,
                container, false);
        ((TextView) v.findViewById(R.id.txtGroupTitle)).setText(titleRes);
        Group g = new Group(v, v.findViewById(R.id.layoutGroupRows));
        groups.add(g);
        container.addView(v);
        return g;
    }

    private Row row(Group g, @DrawableRes int icon, String title, @Nullable String sub,
                    @Nullable Runnable click) {
        View v = LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_row, g.rows, false);
        Row r = new Row(v);
        ImageView img = v.findViewById(R.id.imgRowIcon);
        img.setImageResource(icon);
        // Как у Gorilla: квадрат 36dp с акцентом 15% и иконкой акцентного цвета
        int accent = Theming.accent(requireContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((accent & 0x00FFFFFF) | 0x26000000);
        bg.setCornerRadius(10f * getResources().getDisplayMetrics().density);
        img.setBackground(bg);
        img.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
        r.title.setText(title);
        if (sub == null || sub.isEmpty()) r.sub.setVisibility(View.GONE);
        else r.sub.setText(sub);
        r.searchText = (title + " " + (sub == null ? "" : sub)).toLowerCase(Locale.getDefault());
        View clickable = v.findViewById(R.id.rowSetting);
        if (click != null) {
            clickable.setOnClickListener(x -> click.run());
            Press.attach(clickable, 0.985f);
        } else {
            r.chevron.setVisibility(View.GONE);
            clickable.setBackground(null);
        }
        g.items.add(r);
        g.rows.addView(v);
        return r;
    }

    private Row toggle(Group g, @DrawableRes int icon, String title, @Nullable String sub,
                       BoolGet get, BoolSet set) {
        Row r = row(g, icon, title, sub, null);
        View clickable = r.root.findViewById(R.id.rowSetting);
        clickable.setBackgroundResource(android.R.color.transparent);
        r.chevron.setVisibility(View.GONE);
        r.sw.setVisibility(View.VISIBLE);
        r.sw.setChecked(get.get());
        clickable.setOnClickListener(x -> {
            boolean v = !r.sw.isChecked();
            r.sw.setChecked(v);
            set.set(v);
        });
        r.refresher = () -> r.sw.setChecked(get.get());
        return r;
    }

    // ---------- Обновление / фильтр ----------

    public void refresh() {
        if (!isAdded() || container == null) return;
        boolean hidden = MaskGuard.hidden();
        for (Group g : groups) {
            for (Row r : g.items) {
                if (r.refresher != null) {
                    r.refresher.run();
                    // Подзаголовок, заполненный обновлением, должен быть виден
                    r.sub.setVisibility(r.sub.getText().length() == 0 ? View.GONE : View.VISIBLE);
                }
                r.visible = !(hidden && maskSensitive.contains(r));
                if (r.shownIf != null && !r.shownIf.getAsBoolean()) r.visible = false;
            }
        }
        applyFilter();
    }

    private void applyFilter() {
        for (Group g : groups) {
            boolean any = false;
            boolean first = true;
            for (Row r : g.items) {
                boolean show = r.visible && (query.isEmpty() || r.searchText.contains(query));
                r.root.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    r.divider.setVisibility(first ? View.GONE : View.VISIBLE);
                    first = false;
                    any = true;
                }
            }
            g.root.setVisibility(any ? View.VISIBLE : View.GONE);
        }
    }

    private void pickGroupBy() {
        String[] labels = {groupByLabel(Prefs.GROUP_NONE), groupByLabel(Prefs.GROUP_DAY),
                groupByLabel(Prefs.GROUP_MONTH), groupByLabel(Prefs.GROUP_YEAR)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_group_by)
                .setSingleChoiceItems(labels, Prefs.groupBy(), (d, which) -> {
                    Prefs.setGroupBy(which);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String groupByLabel(int mode) {
        switch (mode) {
            case Prefs.GROUP_NONE:
                return getString(R.string.group_none);
            case Prefs.GROUP_MONTH:
                return getString(R.string.group_month);
            case Prefs.GROUP_YEAR:
                return getString(R.string.group_year);
            default:
                return getString(R.string.group_day);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROOT) refresh();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isAdded()) refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }
}
