package com.premiumlab.galleryx.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.RootPickerActivity;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.adapter.DestAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Нижний лист выбора папки-назначения для копирования/перемещения.
 *
 * Содержит: «Новая папка…» (создаётся в текущей папке или в корне), корневую папку,
 * все пользовательские папки (любой вложенности), папки устройства с медиа и пункт
 * «Другая папка на устройстве…» для выбора произвольного каталога.
 */
public class DestSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onDestPicked(File dir);
    }

    private static final int REQ_BROWSE = 701;

    private DestAdapter adapter;
    private Listener listener;
    private File newFolderParent;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext())
                .inflate(R.layout.sheet_dest, null, false);
        dialog.setContentView(v);

        // Пока маскировка «закрыта», корневой папки как будто нет
        String rootPath = MaskGuard.hidden() ? null : Prefs.rootPath();
        String ctxPath = requireArguments().getString("parent");
        if (ctxPath != null && MaskGuard.hidden() && MaskGuard.isHiddenPath(ctxPath)) {
            ctxPath = null;
        }
        newFolderParent = ctxPath != null && new File(ctxPath).isDirectory()
                ? new File(ctxPath)
                : (rootPath != null ? new File(rootPath) : null);

        adapter = new DestAdapter(new DestAdapter.Listener() {
            @Override
            public void onAction(int action) {
                handleAction(action);
            }

            @Override
            public void onDestPicked(File dir) {
                deliver(dir);
            }
        });

        RecyclerView recycler = v.findViewById(R.id.recyclerDest);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        recycler.setAdapter(adapter);

        boolean copy = requireArguments().getBoolean("copy", false);
        TextView title = v.findViewById(R.id.txtDestTitle);
        title.setText(copy ? R.string.dest_copy_title : R.string.dest_move_title);

        buildRows(new ArrayList<>());
        loadDeviceFolders();
        return dialog;
    }

    private void handleAction(int action) {
        Activity act = getActivity();
        if (act == null) return;
        switch (action) {
            case DestAdapter.ACTION_NEW:
                if (newFolderParent == null) return;
                CreateFolderDialog.showIn(act, newFolderParent, this::deliver);
                break;
            case DestAdapter.ACTION_ROOT:
                if (Prefs.rootPath() != null && !MaskGuard.hidden()) {
                    deliver(new File(Prefs.rootPath()));
                }
                break;
            case DestAdapter.ACTION_BROWSE:
                Intent i = new Intent(act, RootPickerActivity.class);
                i.putExtra(RootPickerActivity.EXTRA_PICK_ANY, true);
                if (newFolderParent != null) {
                    i.putExtra(RootPickerActivity.EXTRA_START, newFolderParent.getAbsolutePath());
                }
                startActivityForResult(i, REQ_BROWSE);
                break;
            default:
                break;
        }
    }

    private void deliver(File dir) {
        if (listener != null) listener.onDestPicked(dir);
        dismissAllowingStateLoss();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BROWSE && resultCode == Activity.RESULT_OK && data != null) {
            String path = data.getStringExtra(RootPickerActivity.EXTRA_PATH);
            if (path != null) deliver(new File(path));
        }
    }

    private void buildRows(List<File> deviceFolders) {
        Context ctx = getContext();
        if (ctx == null) return;
        List<DestAdapter.Row> rows = new ArrayList<>();
        String rootPath = MaskGuard.hidden() ? null : Prefs.rootPath();

        if (newFolderParent != null) {
            rows.add(DestAdapter.Row.action(DestAdapter.ACTION_NEW,
                    ctx.getString(R.string.dest_new_folder),
                    ctx.getString(R.string.dest_new_subfolder, newFolderParent.getName()),
                    R.drawable.ic_folder_plus));
        }
        rows.add(DestAdapter.Row.action(DestAdapter.ACTION_BROWSE,
                ctx.getString(R.string.dest_other_folder),
                ctx.getString(R.string.dest_pick_desc_short),
                R.drawable.ic_folder_open));

        if (rootPath != null) {
            rows.add(DestAdapter.Row.section(ctx.getString(R.string.dest_section_mine)));
            rows.add(DestAdapter.Row.action(DestAdapter.ACTION_ROOT,
                    ctx.getString(R.string.dest_root_folder), rootPath, R.drawable.ic_sd));
            for (File f : MediaEngine.userFoldersRecursive(new File(rootPath))) {
                rows.add(DestAdapter.Row.folder(f, DestAdapter.subtitleFor(f)));
            }
        }
        if (!deviceFolders.isEmpty()) {
            rows.add(DestAdapter.Row.section(ctx.getString(R.string.dest_section_device)));
            for (File f : deviceFolders) {
                rows.add(DestAdapter.Row.folder(f, f.getParent()));
            }
        }
        adapter.submit(rows);
    }

    private void loadDeviceFolders() {
        Context app = getContext() == null ? null : getContext().getApplicationContext();
        if (app == null) return;
        new Thread(() -> {
            List<File> list = MediaEngine.deviceFolders(app);
            Activity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                if (isAdded() && adapter != null) buildRows(list);
            });
        }).start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    public static DestSheet newInstance(boolean copy) {
        return newInstance(copy, null);
    }

    /**
     * @param parentPath папка, в которой создаётся «Новая папка…» (null — корневая)
     */
    public static DestSheet newInstance(boolean copy, @Nullable String parentPath) {
        DestSheet sheet = new DestSheet();
        Bundle args = new Bundle();
        args.putBoolean("copy", copy);
        if (parentPath != null) args.putString("parent", parentPath);
        sheet.setArguments(args);
        return sheet;
    }
}
