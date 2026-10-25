package com.tharunbirla.librecuts.utils

import java.io.Serializable

/**
 * Natural order comparator for filenames and alphanumeric strings.
 * Accurately sorts files containing numbers by their numeric value:
 * e.g., "image_1.jpg", "image_2.jpg", "image_10.jpg", "image_11.jpg"
 * instead of standard alphabetical ASCII order where "image_11.jpg" precedes "image_2.jpg".
 */
object NaturalOrderComparator : Comparator<String>, Serializable {

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == null && s2 == null) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        var idx1 = 0
        var idx2 = 0
        val len1 = s1.length
        val len2 = s2.length

        while (idx1 < len1 && idx2 < len2) {
            val c1 = s1[idx1]
            val c2 = s2[idx2]

            if (c1.isDigit() && c2.isDigit()) {
                // Find complete numeric sequence in s1
                var end1 = idx1
                while (end1 < len1 && s1[end1].isDigit()) end1++
                // Find complete numeric sequence in s2
                var end2 = idx2
                while (end2 < len2 && s2[end2].isDigit()) end2++

                val chunk1 = s1.substring(idx1, end1)
                val chunk2 = s2.substring(idx2, end2)

                // Skip leading zeros for numeric comparison
                var start1 = 0
                while (start1 < chunk1.length && chunk1[start1] == '0') start1++
                var start2 = 0
                while (start2 < chunk2.length && chunk2[start2] == '0') start2++

                val num1 = chunk1.substring(start1)
                val num2 = chunk2.substring(start2)

                // If non-zero portions differ in length, the longer one is numerically larger
                var numCompare = num1.length - num2.length
                if (numCompare == 0) {
                    numCompare = num1.compareTo(num2)
                }
                // If numeric value is identical (e.g. "1" vs "01"), fewer leading zeros precedes
                if (numCompare == 0) {
                    numCompare = chunk1.length - chunk2.length
                }
                if (numCompare != 0) {
                    return numCompare
                }

                idx1 = end1
                idx2 = end2
            } else {
                val charCompare = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (charCompare != 0) {
                    return charCompare
                }
                idx1++
                idx2++
            }
        }

        return len1 - len2
    }
}
