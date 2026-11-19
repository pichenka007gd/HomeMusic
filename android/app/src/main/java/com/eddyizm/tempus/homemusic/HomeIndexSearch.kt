package com.eddyizm.tempus.homemusic

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.eddyizm.tempus.subsonic.models.Artist
import java.text.Normalizer

/**
 * The field under a folder's title («Music Library»): narrows its artist list as you type.
 * The list is already on the phone, so it filters locally instead of asking Navidrome.
 */
object HomeIndexSearch {
    /**
     * Listens to [field] and hands every change to [onQuery]; the search key only hides the
     * keyboard, since the list is already filtered by then.
     */
    @JvmStatic
    fun attach(field: EditText, onQuery: (String) -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onQuery(s?.toString().orEmpty())
        })
        field.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            v.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            true
        }
    }

    /** The artists [query] matches, in the list's order; [empty] shows when a query matched none. */
    @JvmStatic
    fun filter(artists: List<Artist>, query: String, empty: View?): List<Artist> {
        val result = filter(artists, query)
        empty?.visibility = if (result.isEmpty() && query.isNotBlank() && artists.isNotEmpty()) View.VISIBLE else View.GONE
        return result
    }

    fun filter(artists: List<Artist>, query: String): List<Artist> {
        if (query.isBlank()) return artists
        return artists.filter { matches(it.name.orEmpty(), query) }
    }

    /**
     * Every word of [query] is somewhere in [name], ignoring case, accents and ё. Either side may
     * be typed in Latin letters: «kino» finds «Кино», since the keyboard is not always switched.
     */
    fun matches(name: String, query: String): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = normalize(name)
        val latin = transliterate(text)
        return words.all { it in text } || words.map(::transliterate).all { it in latin }
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    private fun normalize(s: String): String {
        // ё and й are kept whole: NFD would split them into е/и and a mark the regex then drops.
        val lower = s.lowercase().replace('ё', 'е').replace('й', '\u0001')
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace('\u0001', 'й')
            .replace(SEPARATORS, " ")
            .trim()
    }

    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sh", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /** Cyrillic to rough Latin; Latin and digits stay, so both sides of a match go through it. */
    private fun transliterate(s: String): String = buildString {
        for (c in s) append(CYRILLIC[c] ?: c)
    }
}
