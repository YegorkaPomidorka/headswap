// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.desktop

import java.text.Collator
import java.util.Locale

/** Launchable components, not packages: one package can expose more than one real app entry. */
internal object DesktopAppCatalog {
    data class Entry(
        val packageName: String,
        val className: String,
        val label: String,
        val enabled: Boolean = true,
        val exported: Boolean = true,
    ) {
        val key: String get() = "$packageName/$className"
    }

    fun select(entries: List<Entry>, ownPackage: String, locale: Locale = Locale.getDefault()): List<Entry> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return entries.asSequence()
            .filter { it.enabled && it.exported && it.packageName.isNotBlank() && it.className.isNotBlank() && it.packageName != ownPackage }
            .map { it.copy(label = it.label.trim().ifEmpty { it.packageName }) }
            .distinctBy { it.key }
            .sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.key.compareTo(b.key) }
            .toList()
    }

    /** Never launch onto the phone, an unrelated external display, or an already retired display. */
    fun ownsDisplay(currentDisplayId: Int, activeDisplayId: Int): Boolean =
        currentDisplayId > 0 && currentDisplayId == activeDisplayId
}
