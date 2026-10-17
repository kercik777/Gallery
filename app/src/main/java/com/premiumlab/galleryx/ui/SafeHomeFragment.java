package com.premiumlab.galleryx.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.SafeActivity;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;

import java.util.Locale;

/**
 * Главный экран сейфа: два раздела — «Изображения» и «Видео». Файлы здесь
 * не показываются, только счётчики; внутри раздела — папки сейфа.
 */
public class SafeHomeFragment extends Fragment {

    private TextView txtPhotos, txtVideos;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_safe_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        txtPhotos = v.findViewById(R.id.txtSafePhotosCount);
        txtVideos = v.findViewById(R.id.txtSafeVideosCount);
        View back = v.findViewById(R.id.btnBack);
        Press.attach(back);
        back.setOnClickListener(x -> requireActivity().finish());

        View photos = v.findViewById(R.id.cardSafePhotos);
        View videos = v.findViewById(R.id.cardSafeVideos);
        Press.attachCard(photos);
        Press.attachCard(videos);
        photos.setOnClickListener(x -> open(false));
        videos.setOnClickListener(x -> open(true));
    }

    private void open(boolean video) {
        if (getActivity() instanceof SafeActivity) {
            ((SafeActivity) getActivity()).openKind(video);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!isAdded()) return;
        txtPhotos.setText(countLabel(SafeStore.get().count(false), SafeStore.get().folders(false).size() + 1));
        txtVideos.setText(countLabel(SafeStore.get().count(true), SafeStore.get().folders(true).size() + 1));
    }

    private String countLabel(int files, int folders) {
        String f = Fmt.plural(requireContext(), R.plurals.items_count, files)
                .toLowerCase(Locale.getDefault());
        String d = Fmt.plural(requireContext(), R.plurals.folders_count, folders)
                .toLowerCase(Locale.getDefault());
        return f + " · " + d;
    }
}
