package com.premiumlab.galleryx.ui.sheet;

import com.premiumlab.galleryx.util.Toasts;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.KeyListener;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.NoteStore;
import com.premiumlab.galleryx.util.Fmt;

/**
 * Заметка к файлу: заголовок + текст любой длины. Полноэкранный нижний лист.
 * Чтение и запись идут в фоне (SQLite в корневой папке), интерфейс не ждёт диск.
 * Два режима: просмотр (без клавиатуры, если заметка уже есть) и редактирование
 * (сразу, если заметки нет). Переключатель — кнопка глаз/карандаш в шапке.
 * Лист не закрывается свайпом, чтобы длинный текст можно было прокручивать.
 */
public class NoteSheet extends BottomSheetDialogFragment {

    public interface Listener {
        /** Заметка сохранена или удалена; hasNote — осталась ли она у файла. */
        void onNoteChanged(String path, boolean hasNote);
    }

    private static final String ARG_PATH = "path";
    private static final String ARG_NAME = "name";

    private Listener listener;
    private String path;
    private EditText editTitle, editBody;
    private TextView txtMeta, btnSave, btnMode;
    private ImageView btnDelete;
    private NoteStore.Note loaded;
    private boolean dirty = false;
    private boolean saving = false;
    private boolean editing = false;
    private KeyListener titleKeys, bodyKeys;

    public static NoteSheet newInstance(String path, String displayName) {
        NoteSheet s = new NoteSheet();
        Bundle b = new Bundle();
        b.putString(ARG_PATH, path);
        b.putString(ARG_NAME, displayName);
        s.setArguments(b);
        return s;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_note, null, false);
        dialog.setContentView(v);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        path = getArguments() == null ? null : getArguments().getString(ARG_PATH);
        String name = getArguments() == null ? null : getArguments().getString(ARG_NAME);

        editTitle = v.findViewById(R.id.editNoteTitle);
        editBody = v.findViewById(R.id.editNoteBody);
        txtMeta = v.findViewById(R.id.txtNoteMeta);
        btnDelete = v.findViewById(R.id.btnNoteDelete);
        btnSave = v.findViewById(R.id.btnNoteSave);
        btnMode = v.findViewById(R.id.btnNoteMode);
        TextView txtFile = v.findViewById(R.id.txtNoteFile);
        txtFile.setText(name == null ? "" : name);
        titleKeys = editTitle.getKeyListener();
        bodyKeys = editBody.getKeyListener();

        v.findViewById(R.id.btnNoteClose).setOnClickListener(x -> closeSheet());
        btnSave.setOnClickListener(x -> save(true));
        btnDelete.setVisibility(View.INVISIBLE);
        btnDelete.setOnClickListener(x -> confirmDelete());
        btnMode.setOnClickListener(x -> setEditing(!editing));
        // Прокрутка длинного текста не должна уходить в нижний лист
        editBody.setOnTouchListener((view, ev) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                dirty = true;
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        editTitle.addTextChangedListener(watcher);
        editBody.addTextChangedListener(watcher);

        // Лист на всю высоту и сразу раскрыт — как отдельный экран
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                sheet.setLayoutParams(lp);
                BottomSheetBehavior<FrameLayout> b = BottomSheetBehavior.from(sheet);
                b.setSkipCollapsed(true);
                // Без перетаскивания: иначе прокрутка текста закрывает лист
                b.setDraggable(false);
                b.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        setEnabledInputs(false);
        txtMeta.setText(R.string.note_loading);
        NoteStore.load(path, note -> {
            if (!isAdded()) return;
            loaded = note;
            if (note != null) {
                editTitle.setText(note.title);
                editBody.setText(note.body);
                btnDelete.setVisibility(View.VISIBLE);
            }
            dirty = false;
            setEnabledInputs(true);
            updateMeta();
            // Есть заметка — открываем на чтение, нет — сразу печатаем
            setEditing(note == null);
        });
        return dialog;
    }

    /** Переключает режим просмотра/редактирования вместе с клавиатурой и кнопками. */
    private void setEditing(boolean on) {
        editing = on;
        editTitle.setKeyListener(on ? titleKeys : null);
        editBody.setKeyListener(on ? bodyKeys : null);
        editTitle.setCursorVisible(on);
        editBody.setCursorVisible(on);
        editTitle.setTextIsSelectable(true);
        editBody.setTextIsSelectable(true);
        // Внизу: в режиме правки — «Просмотр» + «Сохранить», в просмотре — «Редактировать»
        btnMode.setText(on ? R.string.note_mode_view : R.string.note_mode_edit);
        btnSave.setVisibility(on ? View.VISIBLE : View.GONE);
        // Корзина в шапке — только когда заметка существует
        btnDelete.setVisibility(loaded != null ? View.VISIBLE : View.INVISIBLE);
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (on) {
            // Курсор — в текст, если заголовок уже есть
            EditText target = editTitle.length() > 0 ? editBody : editTitle;
            target.requestFocus();
            target.setSelection(target.length());
            if (imm != null) imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        } else {
            editTitle.clearFocus();
            editBody.clearFocus();
            if (imm != null) imm.hideSoftInputFromWindow(editBody.getWindowToken(), 0);
        }
    }

    /** Закрытие кнопкой: набранное не теряем — сохраняем автоматически. */
    private void closeSheet() {
        if (dirty) save(true);
        else dismiss();
    }

    private void setEnabledInputs(boolean on) {
        editTitle.setEnabled(on);
        editBody.setEnabled(on);
    }

    private void updateMeta() {
        if (!isAdded()) return;
        if (loaded != null && loaded.updated > 0) {
            txtMeta.setText(getString(R.string.note_updated, Fmt.dateFull(loaded.updated)));
            txtMeta.setVisibility(View.VISIBLE);
        } else {
            txtMeta.setText("");
            txtMeta.setVisibility(View.GONE);
        }
    }

    private void save(boolean close) {
        if (saving || path == null) return;
        String title = editTitle.getText() == null ? "" : editTitle.getText().toString().trim();
        String body = editBody.getText() == null ? "" : editBody.getText().toString();
        boolean empty = title.isEmpty() && body.trim().isEmpty();
        saving = true;
        NoteStore.save(path, title, body, ok -> {
            saving = false;
            if (!isAdded()) return;
            dirty = false;
            if (ok) {
                Toasts.makeText(requireContext(), empty ? R.string.note_deleted
                        : R.string.note_saved, Toast.LENGTH_SHORT).show();
                if (listener != null) listener.onNoteChanged(path, !empty);
                if (close) dismiss();
            } else {
                Toasts.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.note_delete_title)
                .setMessage(R.string.note_delete_msg)
                .setPositiveButton(R.string.delete, (d, w) -> NoteStore.delete(path, ok -> {
                    if (!isAdded()) return;
                    Toasts.makeText(requireContext(), R.string.note_deleted, Toast.LENGTH_SHORT).show();
                    if (listener != null) listener.onNoteChanged(path, false);
                    dirty = false;
                    dismiss();
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        // Свайп вниз / тап мимо: не теряем набранное — сохраняем автоматически
        if (dirty) save(false);
        super.onCancel(dialog);
    }

}
