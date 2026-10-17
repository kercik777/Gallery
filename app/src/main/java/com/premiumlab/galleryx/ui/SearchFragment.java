package com.premiumlab.galleryx.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.SmartActivity;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SelfieIndex;
import com.premiumlab.galleryx.data.SmartAlbum;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Theming;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Полноэкранный поиск в стиле GorillaGallery. Пустой запрос — недавние запросы,
 * плитки категорий и подборки; ввод — сетка результатов с задержкой 150 мс.
 * Ищет по имени файла, папке, датам («март 2024», «5 мая 2024», «2023») и
 * ключевым словам (видео, фото, скриншот, избранное, селфи, отредактированные, крупные).
 */
public class SearchFragment extends BaseMediaFragment {

    private static final long DEBOUNCE_MS = 150L;
    private static final long LARGE_FILE = 50L * 1024 * 1024;

    private View discoverScroll;
    private LinearLayout discoverRoot;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable applyNow = super::applyFilter;
    /** Индекс «строка для поиска» по пути файла — строится один раз в фоне. */
    private final Map<String, String> haystack = new HashMap<>();
    private HashSet<String> favs = new HashSet<>();

    @Override
    protected int layoutRes() {
        return R.layout.fragment_search;
    }

    @Override
    protected boolean allowSort() {
        return false;
    }

    @Override
    protected void onViewsReady(@NonNull View root) {
        discoverScroll = root.findViewById(R.id.discoverScroll);
        discoverRoot = root.findViewById(R.id.discoverRoot);
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(discoverScroll);
        if (editSearch != null) {
            editSearch.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    Prefs.addRecentSearch(editSearch.getText().toString());
                    hideKeyboard();
                    return true;
                }
                return false;
            });
            editSearch.post(() -> {
                editSearch.requestFocus();
                InputMethodManager imm = (InputMethodManager) requireContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT);
            });
        }
        if (btnSearchClose != null) {
            btnSearchClose.setOnClickListener(v -> {
                if (editSearch != null) editSearch.setText("");
                if (editSearch != null) editSearch.requestFocus();
            });
        }
        buildDiscover();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && editSearch != null) {
            imm.hideSoftInputFromWindow(editSearch.getWindowToken(), 0);
        }
    }

    // ---------- Данные ----------

    @Override
    protected void loadMedia() {
        if (!MediaEngine.hasFreshCache()
                && !MediaEngine.hasDiskCache(requireContext())) {
            showLoading(true);
        }
        MediaEngine.loadAll(requireContext(), items -> {
            if (!isAdded()) return;
            Context app = requireContext().getApplicationContext();
            new Thread(() -> {
                Map<String, String> idx = buildIndex(app, items);
                HashSet<String> f = new HashSet<>(Prefs.favorites());
                handler.post(() -> {
                    if (!isAdded()) return;
                    haystack.clear();
                    haystack.putAll(idx);
                    favs = f;
                    showLoading(false);
                    setData(items);
                });
            }, "search-index").start();
        });
    }

    private static Map<String, String> buildIndex(Context ctx, List<MediaItem> items) {
        // Индекс на двух языках: даты и по-русски, и по-английски — поиск работает
        // при любом языке приложения
        Locale ru = new Locale("ru");
        Locale en = Locale.ENGLISH;
        SimpleDateFormat fMonth = new SimpleDateFormat("LLLL yyyy", ru);
        SimpleDateFormat fDay = new SimpleDateFormat("d MMMM yyyy", ru);
        SimpleDateFormat fMonthEn = new SimpleDateFormat("LLLL yyyy", en);
        SimpleDateFormat fDayEn = new SimpleDateFormat("MMMM d yyyy", en);
        SimpleDateFormat fNum = new SimpleDateFormat("dd.MM.yyyy", ru);
        Map<String, String> out = new HashMap<>(items.size() * 2);
        Date d = new Date();
        StringBuilder sb = new StringBuilder(160);
        for (MediaItem it : items) {
            sb.setLength(0);
            sb.append(it.name).append(' ');
            // Все сегменты пути папки: поиск «Masgalam» находит и файлы
            // во вложенной …/Masgalam/Oglum, а не только в самой папке
            sb.append(relativeFolder(it.folderPath)).append(' ');
            d.setTime(it.dateModified);
            sb.append(fMonth.format(d)).append(' ')
                    .append(fDay.format(d)).append(' ')
                    .append(fMonthEn.format(d)).append(' ')
                    .append(fDayEn.format(d)).append(' ')
                    .append(fNum.format(d)).append(' ');
            sb.append(it.isVideo ? "видео video " : "фото photo ");
            if (SmartAlbum.isScreenshot(it)) sb.append("скриншот screenshot ");
            if (SmartAlbum.isEdited(it)) sb.append("отредактированные edited изменённые ");
            if (it.size >= LARGE_FILE) sb.append("крупные large большие ");
            out.put(it.path, sb.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static final String STORAGE_PREFIX = android.os.Environment
            .getExternalStorageDirectory().getAbsolutePath() + "/";

    /** Путь папки без «/storage/emulated/0/», слэши заменены пробелами. */
    private static String relativeFolder(String folder) {
        if (folder == null) return "";
        String rel = folder.startsWith(STORAGE_PREFIX) ? folder.substring(STORAGE_PREFIX.length()) : folder;
        return rel.replace('/', ' ');
    }

    @Override
    protected boolean matchesQuery(MediaItem it, String q) {
        return true; // сопоставление уже сделано в extraFilter по индексу
    }

    // ---------- Найденные папки (карточки над результатами) ----------

    private final List<com.premiumlab.galleryx.data.Album> foundFolders = new ArrayList<>();

    /** Папки, чьё имя содержит запрос целиком; считаются по видимым файлам. */
    private void collectFolders(List<MediaItem> visible, String q) {
        foundFolders.clear();
        if (q.isEmpty()) return;
        java.util.LinkedHashMap<String, int[]> counts = new java.util.LinkedHashMap<>();
        java.util.HashMap<String, String> covers = new java.util.HashMap<>();
        boolean maskHidden = com.premiumlab.galleryx.data.MaskGuard.hidden();
        for (MediaItem it : visible) {
            String folder = it.folderPath;
            if (folder == null || !folder.startsWith(STORAGE_PREFIX)) continue;
            // Поднимаемся по пути: каждая папка, чьё имя содержит запрос
            String cur = folder;
            while (cur != null && cur.length() > STORAGE_PREFIX.length()) {
                int slash = cur.lastIndexOf('/');
                String name = slash >= 0 ? cur.substring(slash + 1) : cur;
                if (name.toLowerCase(Locale.ROOT).contains(q)
                        && !(maskHidden && com.premiumlab.galleryx.data.MaskGuard.isHiddenPath(cur))
                        && !com.premiumlab.galleryx.data.LockStore.isUnderLocked(cur + "/x")) {
                    int[] c = counts.get(cur);
                    if (c == null) {
                        counts.put(cur, new int[]{1});
                        covers.put(cur, it.path);
                    } else {
                        c[0]++;
                    }
                }
                cur = slash > 0 ? cur.substring(0, slash) : null;
            }
        }
        String root = Prefs.rootPath();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String path = e.getKey();
            if (root != null && path.equals(root)) continue; // сам корень — не альбом
            boolean user = root != null && MediaEngine.isUnder(path, root);
            foundFolders.add(new com.premiumlab.galleryx.data.Album(
                    new java.io.File(path).getName(), path, covers.get(path), e.getValue()[0], user));
            if (foundFolders.size() >= 12) break;
        }
    }

    @Override
    protected List<com.premiumlab.galleryx.ui.adapter.MediaAdapter.Row> topRows() {
        if (foundFolders.isEmpty()) return new ArrayList<>();
        List<com.premiumlab.galleryx.ui.adapter.MediaAdapter.Row> rows = new ArrayList<>();
        rows.add(com.premiumlab.galleryx.ui.adapter.MediaAdapter.Row.folders(
                getString(R.string.search_folders_title), new ArrayList<>(foundFolders)));
        return rows;
    }

    @Override
    public void onFolderOpen(com.premiumlab.galleryx.data.Album folder) {
        hideKeyboard();
        if (editSearch != null) Prefs.addRecentSearch(editSearch.getText().toString());
        Intent intent = new Intent(requireContext(), com.premiumlab.galleryx.FolderActivity.class);
        intent.putExtra("path", folder.path);
        intent.putExtra("name", folder.name);
        startActivity(intent);
    }

    @Override
    public void onFolderLongClick(com.premiumlab.galleryx.data.Album folder) {
        // В поиске папки не выделяются — только открываются
    }

    @Override
    protected void applyFilter() {
        handler.removeCallbacks(applyNow);
        handler.postDelayed(applyNow, DEBOUNCE_MS);
    }

    @Override
    protected List<MediaItem> extraFilter(List<MediaItem> input) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            foundFolders.clear();
            return new ArrayList<>();
        }
        String[] tokens = q.split("\\s+");
        boolean selfieLoaded = SelfieIndex.isLoaded();
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem it : input) {
            String hay = haystack.get(it.path);
            if (hay == null) hay = it.name.toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String t : tokens) {
                if (t.isEmpty()) continue;
                boolean hit = hay.contains(t);
                if (!hit && (t.startsWith("избран") || t.startsWith("favorite") || t.startsWith("favourite"))) {
                    hit = favs.contains(it.path);
                }
                if (!hit && (t.startsWith("селфи") || t.startsWith("selfie"))) {
                    hit = selfieLoaded ? SelfieIndex.isSelfie(it.path) : SmartAlbum.isSelfie(it);
                }
                if (!hit) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(it);
        }
        collectFolders(out, q);
        return out;
    }

    @Override
    protected void updateEmptyState() {
        boolean empty = query == null || query.trim().isEmpty();
        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
        if (btnSearchClose != null) btnSearchClose.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
            if (recycler != null) recycler.setVisibility(View.GONE);
            if (discoverScroll != null) {
                discoverScroll.setVisibility(View.VISIBLE);
                buildDiscover();
            }
            return;
        }
        if (discoverScroll != null) discoverScroll.setVisibility(View.GONE);
        if (recycler != null) recycler.setVisibility(View.VISIBLE);
        if (layoutEmpty != null) {
            boolean none = shownItems.isEmpty() && foundFolders.isEmpty();
            layoutEmpty.setVisibility(none ? View.VISIBLE : View.GONE);
            if (none && txtEmptyTitle != null) {
                txtEmptyTitle.setText(R.string.search_no_results_title);
                txtEmptySub.setText(getString(R.string.search_nothing_for, query.trim()));
            }
        }
    }

    @Override
    protected void onItemOpen(MediaItem item) {
        if (editSearch != null) Prefs.addRecentSearch(editSearch.getText().toString());
        super.onItemOpen(item);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(applyNow);
        super.onDestroyView();
    }

    // ---------- Панель «что искать» ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildDiscover() {
        if (discoverRoot == null || !isAdded()) return;
        discoverRoot.removeAllViews();
        Context ctx = requireContext();
        int accent = Theming.accent(ctx);

        // --- Недавние запросы ---
        List<String> recent = Prefs.recentSearches();
        if (!recent.isEmpty()) {
            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(20), 0, dp(20), dp(10));
            TextView eyebrow = eyebrow(ctx, getString(R.string.search_recent), 11f);
            eyebrow.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(eyebrow);
            TextView clear = new TextView(ctx);
            clear.setText(R.string.search_clear);
            clear.setTextColor(accent);
            clear.setTextSize(13f);
            clear.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            clear.setPadding(dp(8), dp(4), 0, dp(4));
            Press.attach(clear);
            clear.setOnClickListener(v -> {
                Prefs.clearRecentSearches();
                buildDiscover();
            });
            head.addView(clear);
            discoverRoot.addView(head);

            LinearLayout chips = new LinearLayout(ctx);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipToPadding(false);
            hs.setPadding(dp(20), 0, dp(20), 0);
            for (String r : recent) {
                chips.addView(recentChip(ctx, r));
            }
            hs.addView(chips);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.bottomMargin = dp(24);
            discoverRoot.addView(hs, hp);
        }

        // --- Категории: типы медиа ---
        discoverRoot.addView(sectionHeader(ctx, R.string.search_categories_eyebrow,
                R.string.search_categories_title));
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout row1 = tileRow(ctx);
        row1.addView(tile(ctx, R.drawable.ic_video, R.string.smart_videos, 0xFF38BDF8,
                v -> openSmart(SmartAlbum.VIDEOS)));
        row1.addView(tile(ctx, R.drawable.ic_heart_outline, R.string.smart_favorites, 0xFFFB7185,
                v -> openSmart(SmartAlbum.FAVORITES)));
        LinearLayout row2 = tileRow(ctx);
        row2.addView(tile(ctx, R.drawable.ic_screenshot, R.string.smart_screenshots, 0xFF34D399,
                v -> openSmart(SmartAlbum.SCREENSHOTS)));
        row2.addView(tile(ctx, R.drawable.ic_face, R.string.smart_selfies, 0xFFF0ABFC,
                v -> openSmart(SmartAlbum.SELFIES)));
        grid.addView(row1);
        grid.addView(row2);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.bottomMargin = dp(24);
        discoverRoot.addView(grid, gp);

        // --- Подборки ---
        discoverRoot.addView(sectionHeader(ctx, R.string.search_subjects_eyebrow,
                R.string.search_subjects_title));
        LinearLayout grid2 = new LinearLayout(ctx);
        grid2.setOrientation(LinearLayout.VERTICAL);
        grid2.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout row3 = tileRow(ctx);
        row3.addView(tile(ctx, R.drawable.ic_edit, R.string.smart_edited, 0xFFFBBF24,
                v -> openSmart(SmartAlbum.EDITED)));
        String month = new SimpleDateFormat("LLLL yyyy", Fmt.locale()).format(new Date());
        row3.addView(tile(ctx, R.drawable.ic_date_range, R.string.search_recent_month, 0xFF60A5FA,
                v -> setQuery(month)));
        LinearLayout row4 = tileRow(ctx);
        row4.addView(tile(ctx, R.drawable.ic_sd, R.string.search_large_files, 0xFFA78BFA,
                v -> setQuery(getString(R.string.search_query_large))));
        row4.addView(tile(ctx, R.drawable.ic_photo, R.string.smart_photos, 0xFF4ADE80,
                v -> openSmart(SmartAlbum.PHOTOS)));
        grid2.addView(row3);
        grid2.addView(row4);
        discoverRoot.addView(grid2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Подсказка ---
        TextView tip = new TextView(ctx);
        tip.setText(R.string.search_tip);
        tip.setTextSize(12.5f);
        tip.setTextColor(ContextCompat.getColor(ctx, R.color.textTertiary));
        tip.setLineSpacing(dp(2), 1f);
        tip.setPadding(dp(22), dp(24), dp(22), 0);
        discoverRoot.addView(tip);
    }

    private void setQuery(String q) {
        if (editSearch == null) return;
        editSearch.setText(q);
        editSearch.setSelection(editSearch.getText().length());
        Prefs.addRecentSearch(q);
        hideKeyboard();
    }

    private void openSmart(int kind) {
        hideKeyboard();
        startActivity(new Intent(requireContext(), SmartActivity.class).putExtra("kind", kind));
    }

    private TextView eyebrow(Context ctx, String text, float sp) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setLetterSpacing(0.1f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.textTertiary));
        return t;
    }

    private View sectionHeader(Context ctx, int eyebrowRes, int titleRes) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), dp(12));
        box.addView(eyebrow(ctx, getString(eyebrowRes), 10f));
        TextView title = new TextView(ctx);
        title.setText(titleRes);
        title.setTextSize(18.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(-0.02f);
        title.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        title.setPadding(0, dp(2), 0, 0);
        box.addView(title);
        return box;
    }

    private View recentChip(Context ctx, String text) {
        LinearLayout chip = new LinearLayout(ctx);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setPadding(dp(12), dp(8), dp(8), dp(8));
        ImageView ic = new ImageView(ctx);
        ic.setImageResource(R.drawable.ic_history);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.textSecondary)));
        chip.addView(ic, new LinearLayout.LayoutParams(dp(14), dp(14)));
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14f);
        t.setMaxLines(1);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        t.setPadding(dp(7), 0, dp(6), 0);
        chip.addView(t);
        ImageView x = new ImageView(ctx);
        x.setImageResource(R.drawable.ic_close);
        x.setImageTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.textTertiary)));
        x.setPadding(dp(4), dp(4), dp(4), dp(4));
        chip.addView(x, new LinearLayout.LayoutParams(dp(20), dp(20)));
        x.setOnClickListener(v -> {
            List<String> list = Prefs.recentSearches();
            list.remove(text);
            Prefs.clearRecentSearches();
            for (int i = list.size() - 1; i >= 0; i--) Prefs.addRecentSearch(list.get(i));
            buildDiscover();
        });
        Press.attach(chip);
        chip.setOnClickListener(v -> setQuery(text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        return chip;
    }

    private LinearLayout tileRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private View tile(Context ctx, int iconRes, int labelRes, int tint,
                      View.OnClickListener click) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackgroundResource(R.drawable.bg_search_tile);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setMinimumHeight(dp(56));
        // Квадрат под иконку: тон категории 14%
        FrameLayout box = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor((tint & 0x00FFFFFF) | 0x24000000);
        box.setBackground(bg);
        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconRes);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(16), dp(16));
        ip.gravity = Gravity.CENTER;
        box.addView(ic, ip);
        t.addView(box, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView label = new TextView(ctx);
        label.setText(labelRes);
        label.setTextSize(13f);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        label.setPadding(dp(10), 0, 0, 0);
        t.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Press.attachCard(t);
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.rightMargin = dp(5);
        lp.leftMargin = dp(5);
        t.setLayoutParams(lp);
        return t;
    }
}
