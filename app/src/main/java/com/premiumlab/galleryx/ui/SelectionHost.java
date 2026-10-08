package com.premiumlab.galleryx.ui;

/**
 * Реализуется activity, которые хотят узнавать об изменении режима
 * множественного выделения у видимого фрагмента (например, MainActivity
 * прячет bottom bar и показывает ряд действий).
 */
public interface SelectionHost {

    /** Вызывается фрагментом при каждом изменении количества выбранных элементов. */
    void onSelectionChanged(int selectedCount);
}
