package com.premiumlab.galleryx.ui;

/**
 * Фрагменты, которые могут обработать нажатие «Назад» сами
 * (например, сначала отменить выделение).
 */
public interface BackHandler {

    /** @return true, если нажатие обработано (activity не должна закрываться). */
    boolean onBackPressedHandled();
}
