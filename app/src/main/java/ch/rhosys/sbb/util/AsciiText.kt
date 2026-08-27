package ch.rhosys.sbb.util

// Deliberately ASCII-only: only folds 'A'-'Z' to lowercase. Station names carry real
// German/French/Italian diacritics (e.g. "Zürich", "Genève") — a locale-aware lowercase
// (or any diacritic-folding) would treat "Zürich" and "Zurich" as the same key, which is
// wrong for deduplicating distinct station names. 'ü', 'ß', etc. are left untouched.
fun String.lowercaseAscii(): String {
    val chars = CharArray(length)
    for (i in indices) {
        val c = this[i]
        chars[i] = if (c in 'A'..'Z') c + 32 else c
    }
    return String(chars)
}
