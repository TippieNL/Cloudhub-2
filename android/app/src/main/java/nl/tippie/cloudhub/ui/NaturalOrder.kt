package nl.tippie.cloudhub.ui

/**
 * Names in the order a person counts them: "File 2" before "File 10".
 *
 * The Name sort compares names a character at a time, which puts "File 10"
 * between "File 1" and "File 2" -- correct for the characters, wrong for
 * anybody reading the numbers. This compares the same way except that a run
 * of digits is read as one whole number, so "1.2.3" comes before "1.2.10" and
 * "Image 2.jpg" before "Image 10.jpg". Letters compare without regard to case,
 * as they do under Name, and folders and files follow the same rules.
 *
 * Three rules settle everything else:
 *
 *  - **The name before its extension is compared first.** Without that, the
 *    character after "photo" decides between "photo.jpg" and its copy -- a
 *    space or a hyphen sorts before the dot, an underscore after it -- so the
 *    original would land on either side of "photo 2.jpg" depending on how the
 *    copy was named. With it, "photo" is a prefix of "photo 2" and comes
 *    first every time. Only something that reads as an extension counts: the
 *    letters and digits after the last dot, at least one of them a letter, so
 *    "1.2.10" and "Backup.2024" are compared whole.
 *  - **A name that runs out first comes first**: "Folder" before "Folder 2".
 *  - **Names that read the same are still never equal.** "File 1" comes
 *    before "File 01" (fewer leading zeros first), and then upper case before
 *    lower, so the order never depends on what the server happened to list
 *    first -- and two names are equal only when they are the same name.
 *
 * Digits are compared as text of any length rather than parsed, so a
 * 30-digit camera serial number cannot overflow, and nothing is allocated
 * per comparison: sorting a large folder costs the comparisons and no more.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val baseA = baseEnd(a)
        val baseB = baseEnd(b)
        val byBase = compareRange(a, 0, baseA, b, 0, baseB)
        if (byBase != 0) return byBase
        // The extension, dot included, by the same rules: "notes.md" before
        // "notes.txt", and a name without one before the same name with one.
        val byExtension = compareRange(a, baseA, a.length, b, baseB, b.length)
        if (byExtension != 0) return byExtension
        return tieBreak(a, b)
    }

    /** Where the extension starts: the last dot, or the end when there is none. */
    private fun baseEnd(name: String): Int {
        val dot = name.lastIndexOf('.')
        // No dot, a dotfile like ".nomedia", or a name ending in a dot.
        if (dot <= 0 || dot == name.length - 1) return name.length
        var letter = false
        for (k in dot + 1 until name.length) {
            val c = name[k]
            if (c.isLetter()) letter = true
            else if (!c.isAsciiDigit()) return name.length
        }
        return if (letter) dot else name.length
    }

    /**
     * Two stretches of name, digit runs read as numbers and everything else
     * compared a character at a time, ignoring case.
     *
     * A digit facing a letter or a symbol compares as the character it is, so
     * numbers sit exactly where they do under Name: after a space, a hyphen or
     * a dot, and before any letter.
     */
    private fun compareRange(a: String, aFrom: Int, aTo: Int, b: String, bFrom: Int, bTo: Int): Int {
        var i = aFrom
        var j = bFrom
        while (i < aTo && j < bTo) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val numberEndA = digitsEnd(a, i, aTo)
                val numberEndB = digitsEnd(b, j, bTo)
                val byValue = compareNumbers(a, i, numberEndA, b, j, numberEndB)
                if (byValue != 0) return byValue
                i = numberEndA
                j = numberEndB
            } else {
                val byChar = compareIgnoringCase(a[i], b[j])
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        // Whichever ran out first is the prefix of the other, and comes first.
        return (aTo - i).compareTo(bTo - j)
    }

    /** Two digit runs by value: leading zeros dropped, then length, then digit by digit. */
    private fun compareNumbers(a: String, aFrom: Int, aTo: Int, b: String, bFrom: Int, bTo: Int): Int {
        var i = aFrom
        var j = bFrom
        while (i < aTo && a[i] == '0') i++
        while (j < bTo && b[j] == '0') j++
        val lengths = (aTo - i).compareTo(bTo - j)
        if (lengths != 0) return lengths
        while (i < aTo) {
            val digit = a[i].compareTo(b[j])
            if (digit != 0) return digit
            i++
            j++
        }
        return 0
    }

    /**
     * Names equal but for leading zeros or case. Only reached when every
     * number and letter has already matched, so both names have their digit
     * runs in the same places and can be walked side by side.
     */
    private fun tieBreak(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val numberEndA = digitsEnd(a, i, a.length)
                val numberEndB = digitsEnd(b, j, b.length)
                val zeros = (numberEndA - i).compareTo(numberEndB - j)
                if (zeros != 0) return zeros
                i = numberEndA
                j = numberEndB
            } else {
                i++
                j++
            }
        }
        // Upper case before lower: "File 1" before "file 1".
        return a.compareTo(b)
    }

    private fun digitsEnd(s: String, from: Int, to: Int): Int {
        var k = from
        while (k < to && s[k].isAsciiDigit()) k++
        return k
    }

    /** As String.compareTo(ignoreCase = true) compares one character, which is what Name uses. */
    private fun compareIgnoringCase(x: Char, y: Char): Int {
        if (x == y) return 0
        val upperX = x.uppercaseChar()
        val upperY = y.uppercaseChar()
        if (upperX == upperY) return 0
        return upperX.lowercaseChar().compareTo(upperY.lowercaseChar())
    }

    /** ASCII only: other scripts' digits stay text rather than mixing with these. */
    private fun Char.isAsciiDigit() = this in '0'..'9'
}
