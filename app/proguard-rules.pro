# ============================================================================
# GalleryX — боевой конфиг R8: сильная обфускация + оптимизация + анти-анализ.
# Включается в app/build.gradle (release): minifyEnabled true.
# ВАЖНО: после включения обязательно проверьте релизную сборку на устройстве!
# Файл app/build/outputs/mapping/release/mapping.txt — СОХРАНЯЙТЕ для каждой
# версии: без него нельзя расшифровать обфусцированные стектрейсы крашей.
# ============================================================================

# ---------- Агрессивная обфускация ----------
# Все классы приложения сваливаются в один корневой пакет — структура
# проекта (data/, ui/, util/...) полностью исчезает из APK.
-repackageclasses ''
# R8 можно расширять доступы (private -> public): сильнее инлайн и слияние классов
-allowaccessmodification
# Несколько проходов оптимизатора
-optimizationpasses 5
# Поле/методы с одинаковыми именами, но разной сигнатурой — разрешено:
# декомпилятор показывает кашу из перегруженных a(), b(), c()...
-overloadaggressively

# Исходные имена файлов в стектрейсах маскируются под "GX", номера строк
# остаются — краш-лог приложения расшифровывается через mapping.txt.
-renamesourcefileattribute GX
-keepattributes SourceFile,LineNumberTable

# ---------- Анти-анализ: все вызовы Log.* вырезаются из релиза ----------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(java.lang.String, int);
}

# ---------- Обязательно оставляем (иначе приложение упадёт) ----------
# Точки входа Android: активности, сервисы, ресиверы, Application —
# имена берутся из AndroidManifest, R8 сам их не тронет благодаря
# стандартному proguard-android-optimize.txt, но дублируем для надёжности.
-keep class com.premiumlab.galleryx.App { *; }

# Parcelable: CREATOR ищется по имени через рефлексию
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Enum: values()/valueOf() вызываются по имени
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Нативные методы (JNI ищет их по имени)
-keepclasseswithmembernames class * {
    native <methods>;
}

# View-конструкторы из XML-разметок (inflate по имени класса)
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------- Библиотеки ----------
# Glide: модули и декодеры ищутся рефлексией
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**
-keep class * implements com.bumptech.glide.module.GlideModule { *; }
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }

# Media3 (ExoPlayer/Transformer): свои правила поставляются с библиотекой,
# здесь только глушим предупреждения о недоступных опциональных классах
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**

# AndroidX / Material / Biometric: поставляют consumer rules в AAR,
# ничего дополнительно не трогаем, только предупреждения
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ---------- Оптимизация ----------
# Внутренние интерфейсы и синтетика — можно смело оптимизировать
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
# Примечание: при редких падениях релиза первым делом отключите строку
# выше и -optimizationpasses, затем включайте обратно по частям.

# Не включать в финальный APK служебные метаданные обфускации
# (имена исходных классов и т.п.) сверх необходимого.
-keepattributes !LocalVariableTable,!LocalVariableTypeTable
