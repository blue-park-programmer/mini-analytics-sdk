package io.github.dduddupark.minianalytics.internal.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NameValidatorTest {

    @Test
    fun `valid names`() {
        assertEquals(NameValidator.Result.VALID, NameValidator.validate("Button Clicked"))
        assertEquals(NameValidator.Result.VALID, NameValidator.validate("x".repeat(256)))
    }

    @Test
    fun `null empty and blank names are rejected`() {
        listOf(null, "", "   ", "\t\n").forEach {
            assertEquals(NameValidator.Result.BLANK, NameValidator.validate(it))
        }
    }

    @Test
    fun `length is measured in utf-16 code units`() {
        // 이모지(서로게이트 쌍)는 2로 센다: 128개 = 256 code unit
        assertEquals(NameValidator.Result.VALID, NameValidator.validate("😀".repeat(128)))
        assertEquals(NameValidator.Result.TOO_LONG, NameValidator.validate("😀".repeat(128) + "a"))
        // 한글은 1로 센다
        assertEquals(NameValidator.Result.VALID, NameValidator.validate("가".repeat(256)))
    }

    @Test
    fun `surrounding whitespace is kept and counted, not trimmed`() {
        assertEquals(NameValidator.Result.VALID, NameValidator.validate(" user-1 "))
        assertEquals(NameValidator.Result.TOO_LONG, NameValidator.validate(" " + "x".repeat(256)))
    }

    @Test
    fun `unicode whitespace only is blank`() {
        // U+3000 전각 공백, U+2003 em space
        assertEquals(NameValidator.Result.BLANK, NameValidator.validate("\u3000\u2003"))
    }

    @Test
    fun `names longer than 256 chars are rejected not truncated`() {
        assertEquals(NameValidator.Result.TOO_LONG, NameValidator.validate("x".repeat(257)))
    }
}
