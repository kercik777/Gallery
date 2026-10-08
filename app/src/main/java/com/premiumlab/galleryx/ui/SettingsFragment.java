package com.premiumlab.galleryx.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.premiumlab.galleryx.MaskingSettingsActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.RootPickerActivity;
import com.premiumlab.galleryx.SafeActivity;
import com.premiumlab.galleryx.TrashActivity;
import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.data.TrashStore;
import com.premiumlab.galleryx.ui.dialog.AboutDialog;
import com.premiumlab.galleryx.ui.dialog.CreateFolderDialog;
import com.premiumlab.galleryx.ui.sheet.GridSheet;
import com.premiumlab.galleryx.ui.sheet.ThemeSheet;
import com.premiumlab.galleryx.util.Perms;

/**
 * Экран настроек приложения.
 */
public class SettingsFragment extends Fragment {

    private static final int REQ_ROOT = 503;

    private TextView txtMaskingStatus, txtThemeCurrent, txtGridCurrent;
    private TextView txtRootCurrent, txtTrashCount, txtSafeCount, txtPermsStatus, txtVersion;
    private View rowSafe;
    private Runnable pendingAfterRoot;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        txtMaskingStatus = v.findViewById(R.id.txtMaskingStatus);
        txtThemeCurrent = v.findViewById(R.id.txtThemeCurrent);
        txtGridCurrent = v.findViewById(R.id.txtGridCurrent);
        txtRootCurrent = v.findViewById(R.id.txtRootCurrent);
        txtTrashCount = v.findViewById(R.id.txtTrashCount);
        txtSafeCount = v.findViewById(R.id.txtSafeCount);
        txtPermsStatus = v.findViewById(R.id.txtPermsStatus);
        txtVersion = v.findViewById(R.id.txtVersion);
        rowSafe = v.findViewById(R.id.rowSafe);

        v.findViewById(R.id.rowMasking).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), MaskingSettingsActivity.class)));

        v.findViewById(R.id.rowTheme).setOnClickListener(x -> {
            ThemeSheet sheet = new ThemeSheet();
            sheet.setListener(() -> refresh());
            sheet.show(getParentFragmentManager(), "theme");
        });

        v.findViewById(R.id.rowGrid).setOnClickListener(x -> {
            GridSheet sheet = new GridSheet();
            sheet.setListener(columns -> refresh());
            sheet.show(getParentFragmentManager(), "grid");
        });

        v.findViewById(R.id.rowRoot).setOnClickListener(x ->
                startActivityForResult(new Intent(requireContext(), RootPickerActivity.class),
                        REQ_ROOT));

        v.findViewById(R.id.rowNewFolder).setOnClickListener(x ->
                CreateFolderDialog.show(requireActivity(), () -> {
                    pendingAfterRoot = () ->
                            Toast.makeText(requireContext(), R.string.root_selected,
                                    Toast.LENGTH_SHORT).show();
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                            requireActivity())
                            .setTitle(R.string.root_needed_title)
                            .setMessage(R.string.root_needed_desc)
                            .setPositiveButton(R.string.continue_btn, (d, w) ->
                                    startActivityForResult(
                                            new Intent(requireContext(),
                                                    RootPickerActivity.class), REQ_ROOT))
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                }, folder -> {
                }));

        v.findViewById(R.id.rowTrash).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), TrashActivity.class)));

        v.findViewById(R.id.rowSafe).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), SafeActivity.class)));

        v.findViewById(R.id.rowPerms).setOnClickListener(x -> {
            FragmentActivity act = requireActivity();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Perms.requestAllFiles(act);
            } else {
                Perms.requestLegacy(act);
            }
        });

        v.findViewById(R.id.rowAbout).setOnClickListener(x -> AboutDialog.show(requireContext()));

        refresh();
    }

    private void refresh() {
        if (!isAdded()) return;
        txtMaskingStatus.setText(Prefs.masking()
                ? R.string.masking_status_on : R.string.masking_status_off);

        // При включённой маскировке пункт «Сейф» скрывается из настроек
        if (rowSafe != null) {
            rowSafe.setVisibility(Prefs.masking() ? View.GONE : View.VISIBLE);
        }

        int theme = Prefs.theme();
        txtThemeCurrent.setText(theme == Prefs.THEME_LIGHT
                ? R.string.settings_theme_light
                : theme == Prefs.THEME_DARK
                ? R.string.settings_theme_dark
                : R.string.settings_theme_system);

        txtGridCurrent.setText(getString(R.string.grid_value, Prefs.columns()));

        String root = Prefs.rootPath();
        txtRootCurrent.setText(root == null ? getString(R.string.root_not_set) : root);

        int trash = TrashStore.get().count();
        int safe = SafeStore.get().count();
        int fav = FavStore.count();
        txtTrashCount.setText(getString(R.string.trash_subtitle) + " · "
                + com.premiumlab.galleryx.util.Fmt.plural(requireContext(),
                R.plurals.items_count, trash));
        txtSafeCount.setText(getString(R.string.safe_subtitle) + " · "
                + com.premiumlab.galleryx.util.Fmt.plural(requireContext(),
                R.plurals.items_count, safe));

        txtPermsStatus.setText(Perms.ok(requireContext())
                ? R.string.permissions_granted : R.string.permissions_denied);

        try {
            String version = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            txtVersion.setText(getString(R.string.settings_version, version));
        } catch (Exception e) {
            txtVersion.setText(getString(R.string.settings_version, "1.0"));
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROOT) {
            refresh();
            Runnable r = pendingAfterRoot;
            pendingAfterRoot = null;
            if (r != null && resultCode == FragmentActivity.RESULT_OK) r.run();
        }
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
