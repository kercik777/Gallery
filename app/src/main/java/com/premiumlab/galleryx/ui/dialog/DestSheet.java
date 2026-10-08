package com.premiumlab.galleryx.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.adapter.DestAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Нижний лист выбора папки-назначения для копирования/перемещения.
 */
public class DestSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onDestPicked(File dir);

        void onNewFolderRequested();
    }

    private DestAdapter adapter;
    private Listener listener;

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

        adapter = new DestAdapter(new DestAdapter.Listener() {
            @Override
            public void onNewFolder() {
                dismiss();
                if (listener != null) listener.onNewFolderRequested();
            }

            @Override
            public void onDestPicked(File dir) {
                dismiss();
                if (listener != null) listener.onDestPicked(dir);
            }
        });

        RecyclerView recycler = v.findViewById(R.id.recyclerDest);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        recycler.setAdapter(adapter);

        boolean copy = requireArguments().getBoolean("copy", false);
        TextView title = v.findViewById(R.id.txtDestTitle);
        title.setText(copy ? R.string.dest_copy_title : R.string.dest_move_title);

        reloadFolders();
        return dialog;
    }

    private void reloadFolders() {
        List<File> folders = new ArrayList<>();
        String rootPath = Prefs.rootPath();
        if (rootPath != null) {
            File root = new File(rootPath);
            folders.addAll(MediaEngine.userFoldersRecursive(root));
        }
        adapter.submit(folders);
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
        DestSheet sheet = new DestSheet();
        Bundle args = new Bundle();
        args.putBoolean("copy", copy);
        sheet.setArguments(args);
        return sheet;
    }
}
