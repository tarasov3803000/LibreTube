package com.github.libretube.helpers

import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.constants.PreferenceKeys

/**
 * Наша сборка: выбранная категория трендов. В нашей сборке нет «Игр» (они же были по умолчанию),
 * поэтому сохранённое «GAMING» или пустое значение — на «Трансляции» либо первую доступную.
 */
object TrendingCategoryHelper {
    fun current(): TrendingCategory {
        val available = MediaServiceRepository.instance.getTrendingCategories()
        val stored = PreferenceHelper.getString(PreferenceKeys.TRENDING_CATEGORY, "")
        return available.firstOrNull { it.name == stored }
            ?: available.firstOrNull { it == TrendingCategory.LIVE }
            ?: available.first()
    }
}
