package com.premiumlab.galleryx.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.ui.view.InlineVideoView;
import com.premiumlab.galleryx.ui.view.ZoomableImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Страницы полноэкранного просмотрщика.
 *
 * Фото декодируются в максимальном качестве (до 4096 px по большей стороне —
 * предел GPU-текстуры; меньшие снимки открываются 1:1 без потерь). Загрузка
 * трёхслойная, как в GorillaGallery: внизу — миниатюра из сетки (уже лежит в
 * памяти Glide и рисуется мгновенно, чёрного кадра нет даже на огромных снимках),
 * поверх — превью экранного размера, затем оригинал. Видео проигрывается прямо
 * на странице встроенным плеером, страницу можно листать во время воспроизведения.
 */
public class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.VH> {

    /** Максимальный размер декода фото (px по большей стороне). */
    private static final int MAX_DECODE = 4096;
    /** Крошечное превью соседних страниц: декодируется за миллисекунды. */
    private static final int TINY = 320;
    /** Кадр-подложка видео: 512 px хватает за глаза и быстрее экранного. */
    private static final int FRAME = 512;

    /** События видео на текущей странице — для панели управления activity. */
    public interface VideoListener {
        void onVideoPrepared(VH page, int durationMs);

        void onVideoPlayState(VH page, boolean playing);

        void onVideoCompleted(VH page);
    }

    private final List<String> paths = new ArrayList<>();
    private final ZoomableImageView.TapListener tapListener;
    private final ZoomableImageView.DragListener dragListener;
    private final VideoListener videoListener;
    private final int previewSize;
    /** Открытый из сетки файл («герой»): его миниатюра уже в памяти Glide. */
    private String heroPath;
    private long heroSig;
    private int heroSize;
    /** Явный список путей-видео (сейф хранит файлы под именами .bin). */
    private java.util.Set<String> videoPaths;
    /** Видео стартуют без звука, пока пользователь не включит его кнопкой. */
    private boolean muted = !com.premiumlab.galleryx.data.Prefs.videoSoundDefault();

    public PhotoPagerAdapter(Context ctx, ZoomableImageView.TapListener tapListener,
                             ZoomableImageView.DragListener dragListener,
                             VideoListener videoListener) {
        this.tapListener = tapListener;
        this.dragListener = dragListener;
        this.videoListener = videoListener;
        this.previewSize = Math.max(ctx.getResources().getDisplayMetrics().widthPixels,
                ctx.getResources().getDisplayMetrics().heightPixels);
    }

    public void setVideoPaths(java.util.Set<String> paths) {
        this.videoPaths = paths;
    }

    private ZoomableImageView.RotateListener rotateListener;

    /** Жест «кручение двумя пальцами» для страниц-фото. */
    public void setRotateListener(ZoomableImageView.RotateListener l) {
        this.rotateListener = l;
    }

    /** Параметры миниатюры сетки — для мгновенного первого кадра. */
    public void setHero(String path, long sig, int size) {
        heroPath = path;
        heroSig = sig;
        heroSize = size;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean isVideo(String path) {
        if (path == null) return false;
        if (videoPaths != null && videoPaths.contains(path)) return true;
        return MediaEngine.isVideoName(path);
    }

    public void submit(List<String> list) {
        paths.clear();
        paths.addAll(list);
        notifyDataSetChanged();
    }

    public void removeAt(int index) {
        if (index >= 0 && index < paths.size()) {
            paths.remove(index);
            notifyItemRemoved(index);
        }
    }

    public String getPath(int index) {
        if (index < 0 || index >= paths.size()) return null;
        return paths.get(index);
    }

    /** Вставляет новый файл (например, сохранённую копию из редактора) после указанной позиции. */
    public void insertAfter(int index, String path) {
        int at = Math.max(0, Math.min(paths.size(), index + 1));
        paths.add(at, path);
        notifyItemInserted(at);
    }

    /** Перерисовывает страницу после изменения файла на диске. */
    public void refreshAt(int index) {
        if (index >= 0 && index < paths.size()) notifyItemChanged(index);
    }

    /** Файл переместили в другую папку: страница показывает его с нового места. */
    public void replacePath(int index, String newPath) {
        if (index >= 0 && index < paths.size() && newPath != null) {
            paths.set(index, newPath);
            notifyItemChanged(index);
        }
    }

    public int indexOf(String path) {
        return paths.indexOf(path);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_viewer_page, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(paths.get(position));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position,
                                 @NonNull List<Object> payloads) {
        holder.bind(paths.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        holder.stopVideo();
        super.onViewRecycled(holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull VH holder) {
        holder.stopVideo();
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public int getItemCount() {
        return paths.size();
    }

    public class VH extends RecyclerView.ViewHolder {
        public final View page;
        public final ZoomableImageView zoom;
        public final InlineVideoView video;
        final ImageView btnPlay;
        final ProgressBar buffer;
        String path;
        boolean isVideoPage;

        VH(@NonNull View itemView) {
            super(itemView);
            page = itemView.findViewById(R.id.pageRoot);
            zoom = itemView.findViewById(R.id.zoomImage);
            video = itemView.findViewById(R.id.pageVideo);
            btnPlay = itemView.findViewById(R.id.btnPagePlay);
            buffer = itemView.findViewById(R.id.pageBuffer);
            btnPlay.setOnClickListener(v -> togglePlay());
            video.setListener(new InlineVideoView.Listener() {
                @Override
                public void onPrepared(int durationMs, int videoW, int videoH) {
                    buffer.setVisibility(View.GONE);
                    video.setVisibility(View.VISIBLE);
                    android.graphics.RectF r = new android.graphics.RectF();
                    video.setDisplayRect(zoom.displayedRect(r) ? r : null);
                    if (videoListener != null) videoListener.onVideoPrepared(VH.this, durationMs);
                }

                @Override
                public void onPlayStateChanged(boolean playing) {
                    updatePlayButton(playing);
                    if (videoListener != null) videoListener.onVideoPlayState(VH.this, playing);
                }

                @Override
                public void onCompletion() {
                    if (com.premiumlab.galleryx.data.Prefs.loopVideos()) {
                        // Настройка «Зацикливать видео»
                        video.seekTo(0);
                        video.play();
                        return;
                    }
                    updatePlayButton(false);
                    if (videoListener != null) videoListener.onVideoCompleted(VH.this);
                }

                @Override
                public void onError() {
                    buffer.setVisibility(View.GONE);
                    video.setVisibility(View.GONE);
                    updatePlayButton(false);
                }
            });
        }

        void bind(String p) {
            path = p;
            isVideoPage = isVideo(p);
            // Страница могла остаться «улетевшей» после отменённого свайпа
            page.setTranslationX(0f);
            page.setTranslationY(0f);
            page.setScaleX(1f);
            page.setScaleY(1f);
            page.setAlpha(1f);
            zoom.reset();
            zoom.setTranslationX(0f);
            zoom.setTranslationY(0f);
            zoom.setScaleX(1f);
            zoom.setScaleY(1f);
            zoom.setAlpha(1f);
            zoom.setupGestureDetectors(itemView.getContext());
            zoom.setTapListener(tapListener);
            zoom.setDragListener(dragListener);
            // Кручение двумя пальцами — только для фото (не для видео)
            zoom.setRotateListener(isVideoPage ? null : rotateListener);
            // Видео масштабируется и панорамируется вместе с кадром-подложкой (как фото)
            video.setDisplayRect(null);
            zoom.setMatrixListener(() -> {
                if (!isVideoPage) return;
                android.graphics.RectF r = new android.graphics.RectF();
                video.setDisplayRect(zoom.displayedRect(r) ? r : null);
            });

            stopVideo();
            buffer.setVisibility(View.GONE);

            if (isVideoPage) {
                // Кадр-подложка до старта плеера и во время буферизации.
                // У «героя» нижним слоем идёт миниатюра сетки прямо из памяти.
                com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> frame =
                        Glide.with(itemView)
                                .load(new File(p))
                                .override(FRAME, FRAME)
                                .downsample(DownsampleStrategy.CENTER_INSIDE)
                                .dontTransform();
                if (p.equals(heroPath) && heroSize > 0) {
                    frame.thumbnail(Glide.with(itemView)
                            .load(new File(p))
                            .signature(new com.bumptech.glide.signature.ObjectKey(heroSig))
                            .override(heroSize, heroSize)
                            .centerCrop());
                }
                frame.into(zoom);
                btnPlay.setVisibility(View.VISIBLE);
                btnPlay.setImageResource(R.drawable.ic_play);
                btnPlay.setAlpha(1f);
            } else {
                btnPlay.setVisibility(View.GONE);
                // Три слоя: миниатюра сетки / крошечное превью → экранное превью
                // → оригинал (до 4096 px, без апскейла). Первый слой у «героя» —
                // запрос 1-в-1 как в сетке, поэтому бьёт в кэш памяти Glide.
                File file = new File(p);
                com.bumptech.glide.signature.ObjectKey sig =
                        new com.bumptech.glide.signature.ObjectKey(file.lastModified());
                com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> low;
                if (p.equals(heroPath) && heroSize > 0) {
                    low = Glide.with(itemView)
                            .load(file)
                            .signature(new com.bumptech.glide.signature.ObjectKey(heroSig))
                            .override(heroSize, heroSize)
                            .centerCrop();
                } else {
                    low = Glide.with(itemView)
                            .load(file)
                            .signature(sig)
                            .override(TINY, TINY)
                            .downsample(DownsampleStrategy.CENTER_INSIDE)
                            .dontTransform();
                }
                com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> mid =
                        Glide.with(itemView)
                                .load(file)
                                .signature(sig)
                                .override(previewSize, previewSize)
                                .downsample(DownsampleStrategy.CENTER_INSIDE)
                                .dontTransform()
                                .thumbnail(low);
                Glide.with(itemView)
                        .load(file)
                        .signature(sig)
                        .override(MAX_DECODE, MAX_DECODE)
                        .downsample(DownsampleStrategy.CENTER_INSIDE)
                        .format(DecodeFormat.PREFER_ARGB_8888)
                        .dontTransform()
                        .thumbnail(mid)
                        .into(zoom);
            }
        }

        public boolean isVideoPage() {
            return isVideoPage;
        }

        public String getPath() {
            return path;
        }

        /** Запускает воспроизведение (страница стала текущей). */
        public void startVideo() {
            if (!isVideoPage) return;
            if (!video.isPrepared()) buffer.setVisibility(View.VISIBLE);
            // TextureView получает поверхность только будучи видимой;
            // до первого кадра она прозрачна — под ней остаётся кадр-превью
            video.setVisibility(View.VISIBLE);
            video.setMuted(muted);
            video.setSource(path);
            video.play();
        }

        public void pauseVideo() {
            if (isVideoPage) video.pause();
        }

        public void resumeVideo() {
            if (isVideoPage) video.play();
        }

        public void togglePlay() {
            if (!isVideoPage) return;
            if (video.isPlaying()) {
                video.pause();
            } else {
                startVideo();
            }
        }

        /** Останавливает и освобождает плеер (страница ушла с экрана). */
        public void stopVideo() {
            video.release();
            video.setVisibility(View.GONE);
            buffer.setVisibility(View.GONE);
            if (isVideoPage) updatePlayButton(false);
        }

        void updatePlayButton(boolean playing) {
            if (!isVideoPage) return;
            btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            btnPlay.setVisibility(View.VISIBLE);
            // Во время воспроизведения кнопка почти прозрачна, на паузе — яркая
            btnPlay.animate().alpha(playing ? 0.35f : 1f).setDuration(200).start();
        }

        /** Скрыть/показать кнопку вместе с панелями просмотрщика. */
        public void setOverlayVisible(boolean visible) {
            if (!isVideoPage) return;
            if (visible || !video.isPlaying()) {
                btnPlay.setVisibility(View.VISIBLE);
            } else {
                btnPlay.setVisibility(View.GONE);
            }
        }
    }
}
