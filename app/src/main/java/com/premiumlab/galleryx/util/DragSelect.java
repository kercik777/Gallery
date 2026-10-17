package com.premiumlab.galleryx.util;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Выделение протягиванием (как в GorillaGallery / Google Фото): после долгого
 * нажатия на ячейку палец не отпускают, а ведут по сетке — все файлы между
 * начальной ячейкой и текущей выделяются; если вести обратно — снимаются.
 * У верхнего и нижнего края список сам прокручивается.
 *
 * Работает поверх обычного выделения: ранее выделенные вручную файлы жест
 * не трогает, снимает только то, что выделил сам. Заголовки дат и блоки
 * папок внутри диапазона пропускаются.
 */
public final class DragSelect implements RecyclerView.OnItemTouchListener {

    public interface Host {
        /** Путь файла на позиции адаптера или null (заголовок, папки, вне списка). */
        @Nullable
        String pathAt(int position);

        boolean isSelected(String path);

        /** Пакетно выделяет/снимает выделение; одно обновление списка. */
        void setSelected(Collection<String> paths, boolean selected);

        /** Выделение изменилось — обновить панель счётчика и т. п. */
        void onSelectionChanged();
    }

    private static final float EDGE_FRACTION = 0.14f;
    private static final float MIN_EDGE_DP = 56f;
    private static final float MAX_SCROLL_DP_PER_FRAME = 14f;

    private final RecyclerView rv;
    private final Host host;
    private final float density;

    private boolean active;
    private int anchor = RecyclerView.NO_POSITION;
    private int last = RecyclerView.NO_POSITION;
    private float lastX, lastY;

    /** Пути, выделенные именно этим жестом (их можно снять, ведя назад). */
    private final Set<String> addedByDrag = new HashSet<>();
    /** Пути, попадающие в текущий диапазон жеста. */
    private final Set<String> inRange = new HashSet<>();

    private int scrollDir;        // -1 вверх, 1 вниз, 0 — нет
    private float scrollSpeed;    // 0..1
    private boolean scrolling;

    private DragSelect(RecyclerView rv, Host host) {
        this.rv = rv;
        this.host = host;
        this.density = rv.getResources().getDisplayMetrics().density;
    }

    public static DragSelect attach(@NonNull RecyclerView rv, @NonNull Host host) {
        DragSelect ds = new DragSelect(rv, host);
        rv.addOnItemTouchListener(ds);
        return ds;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Начать жест от ячейки position — вызывается из обработчика долгого нажатия,
     * пока палец ещё на экране. Сама ячейка должна быть уже выделена.
     */
    public void start(int position) {
        if (position == RecyclerView.NO_POSITION) return;
        active = true;
        anchor = position;
        last = position;
        addedByDrag.clear();
        inRange.clear();
        String p = host.pathAt(position);
        if (p != null) inRange.add(p);
        scrollDir = 0;
        ViewParent parent = rv.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
    }

    public void stop() {
        active = false;
        scrollDir = 0;
        scrollSpeed = 0f;
        anchor = RecyclerView.NO_POSITION;
        last = RecyclerView.NO_POSITION;
        addedByDrag.clear();
        inRange.clear();
    }

    // ---------- OnItemTouchListener ----------

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView r, @NonNull MotionEvent e) {
        if (!active) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            stop();
            return false;
        }
        return true;
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView r, @NonNull MotionEvent e) {
        if (!active) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                lastX = e.getX();
                lastY = e.getY();
                updateAt(lastX, lastY);
                updateAutoScroll(lastY);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                // Второй палец (щипок) или отпускание — жест закончен
                stop();
                break;
            default:
                break;
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Ячейки уже получили ACTION_CANCEL — жест полностью наш, ничего не делаем
    }

    // ---------- Внутреннее ----------

    /** Определяет ячейку под пальцем (с учётом зазоров между ячейками) и обновляет диапазон. */
    private void updateAt(float x, float y) {
        View child = rv.findChildViewUnder(x, y);
        if (child == null) child = nearestChild(x, y);
        if (child == null) return;
        int pos = rv.getChildAdapterPosition(child);
        if (pos == RecyclerView.NO_POSITION || pos == last) return;
        last = pos;
        applyRange();
    }

    /** Ближайшая по горизонтали ячейка в строке, куда попадает y (палец в зазоре). */
    @Nullable
    private View nearestChild(float x, float y) {
        View best = null;
        float bestDx = Float.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View c = rv.getChildAt(i);
            if (y < c.getTop() || y > c.getBottom()) continue;
            float dx = x < c.getLeft() ? c.getLeft() - x : (x > c.getRight() ? x - c.getRight() : 0f);
            if (dx < bestDx) {
                bestDx = dx;
                best = c;
            }
        }
        return best;
    }

    private void applyRange() {
        if (anchor == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;
        int lo = Math.min(anchor, last);
        int hi = Math.max(anchor, last);
        Set<String> now = new HashSet<>();
        for (int i = lo; i <= hi; i++) {
            String p = host.pathAt(i);
            if (p != null) now.add(p);
        }
        Set<String> toSelect = new HashSet<>();
        for (String p : now) {
            if (inRange.contains(p)) continue;
            if (host.isSelected(p)) continue;    // было выделено вручную — не наше
            toSelect.add(p);
            addedByDrag.add(p);
        }
        Set<String> toDeselect = new HashSet<>();
        for (String p : inRange) {
            if (now.contains(p)) continue;
            if (addedByDrag.remove(p)) toDeselect.add(p);
        }
        inRange.clear();
        inRange.addAll(now);
        if (toSelect.isEmpty() && toDeselect.isEmpty()) return;
        if (!toSelect.isEmpty()) host.setSelected(toSelect, true);
        if (!toDeselect.isEmpty()) host.setSelected(toDeselect, false);
        host.onSelectionChanged();
        rv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void updateAutoScroll(float y) {
        float h = rv.getHeight();
        float edge = Math.max(MIN_EDGE_DP * density, h * EDGE_FRACTION);
        if (y < edge) {
            scrollDir = -1;
            scrollSpeed = Math.min(1f, (edge - y) / edge);
        } else if (y > h - edge) {
            scrollDir = 1;
            scrollSpeed = Math.min(1f, (y - (h - edge)) / edge);
        } else {
            scrollDir = 0;
            scrollSpeed = 0f;
        }
        if (scrollDir != 0 && !scrolling) {
            scrolling = true;
            rv.postOnAnimation(scroller);
        }
    }

    private final Runnable scroller = new Runnable() {
        @Override
        public void run() {
            if (!active || scrollDir == 0) {
                scrolling = false;
                return;
            }
            // Ускорение у самого края: от 25% до 100% максимальной скорости
            float speed = 0.25f + 0.75f * scrollSpeed * scrollSpeed;
            int dy = Math.round(scrollDir * MAX_SCROLL_DP_PER_FRAME * density * speed);
            if (dy != 0) rv.scrollBy(0, dy);
            // Под пальцем теперь другая ячейка — обновляем диапазон
            updateAt(lastX, lastY);
            rv.postOnAnimation(this);
        }
    };
}
