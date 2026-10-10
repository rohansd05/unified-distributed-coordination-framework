package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisplayTextTest {

    @Test
    @DisplayName("line breaks, tabs, backslashes and control characters become visible escapes; | and \u00e9 stay")
    void singleLineEscapes() {
        assertThat(DisplayText.singleLine("a\nb\rc\td\\e|f\u00e9"))
                .isEqualTo("a\\nb\\rc\\td\\\\e|f\u00e9");
        assertThat(DisplayText.singleLine("\u0000\u0085\u2028\u2029"))
                .isEqualTo("\\u0000\\u0085\\u2028\\u2029");
        assertThat(DisplayText.singleLine(null)).isNull();
    }

    @Test
    @DisplayName("isSingleLine is false exactly when a control character or line separator is present")
    void isSingleLine() {
        assertThat(DisplayText.isSingleLine("a|b \\n")).isTrue();
        assertThat(DisplayText.isSingleLine("a\nb")).isFalse();
        assertThat(DisplayText.isSingleLine("a\u2028b")).isFalse();
        assertThat(DisplayText.isSingleLine(DisplayText.singleLine("a\n\t\u0001"))).isTrue();
    }

    @Test
    @DisplayName("cut keeps short text, and ends long text with \u2026 at exactly the maximum length")
    void cut() {
        assertThat(DisplayText.cut("short", 10)).isEqualTo("short");
        assertThat(DisplayText.cut("exactly10!", 10)).isEqualTo("exactly10!");
        assertThat(DisplayText.cut("0123456789ABC", 10)).isEqualTo("012345678\u2026").hasSize(10);
        assertThat(DisplayText.cut("abc", 1)).isEqualTo("\u2026");
        assertThat(DisplayText.cut(null, 5)).isNull();
        assertThatThrownBy(() -> DisplayText.cut("abc", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cut never splits a surrogate pair")
    void cutKeepsSurrogatePairsWhole() {
        String emoji = "\ud83d\ude00";     // one code point, two chars
        String cut = DisplayText.cut("abc" + emoji + "def", 5);

        assertThat(cut).isEqualTo("abc\u2026");
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 2))).isFalse();
    }

    @Test
    @DisplayName("quoted shows the escaped text and the original length")
    void quoted() {
        assertThat(DisplayText.quoted("a\nb")).isEqualTo("\"a\\nb\" (3 characters)");
        assertThatThrownBy(() -> DisplayText.quoted(null)).isInstanceOf(NullPointerException.class);
    }
}
