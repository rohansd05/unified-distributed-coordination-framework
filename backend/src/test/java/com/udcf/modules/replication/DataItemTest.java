package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataItemTest {

    private static DataItem item(String key, String value) {
        return new DataItem(key, value, 1, 1, 1);
    }

    @Test
    @DisplayName("a null key is an IllegalArgumentException")
    void rejectsNullKey() {
        assertThatThrownBy(() -> item(null, "v"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key must not be null");
    }

    @Test
    @DisplayName("an empty or whitespace-only key is rejected")
    void rejectsBlankKey() {
        assertThatThrownBy(() -> item("", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item("   ", "v"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
    }

    @Test
    @DisplayName("a null value is an IllegalArgumentException")
    void rejectsNullValue() {
        assertThatThrownBy(() -> item("k", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("an empty value is allowed")
    void acceptsEmptyValue() {
        assertThat(item("k", "").value()).isEmpty();
    }

    @Test
    @DisplayName("a negative Lamport time is rejected; 0 is allowed")
    void rejectsNegativeLamport() {
        assertThatThrownBy(() -> new DataItem("k", "v", -1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lamportTime");
        assertThat(new DataItem("k", "v", 0, 1, 1).lamportTime()).isZero();
    }

    @Test
    @DisplayName("an origin node below 1 is rejected")
    void rejectsOriginBelowOne() {
        assertThatThrownBy(() -> new DataItem("k", "v", 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("originNode");
    }

    @Test
    @DisplayName("an epoch below 1 is rejected")
    void rejectsEpochBelowOne() {
        assertThatThrownBy(() -> new DataItem("k", "v", 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("epoch");
    }

    @Test
    @DisplayName("a control character in the key is rejected")
    void rejectsControlCharInKey() {
        for (String key : List.of("a\u0000b", "a\tb", "a\u001Fb", "a\u007Fb", "a\u0085b")) {
            assertThatThrownBy(() -> item(key, "v")).as("key %s", key)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key must not contain");
        }
    }

    @Test
    @DisplayName("a control character in the value is rejected")
    void rejectsControlCharInValue() {
        for (String value : List.of("a\u0000b", "a\tb", "a\u001Bb", "a\u009Fb")) {
            assertThatThrownBy(() -> item("k", value)).as("value %s", value)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("value must not contain");
        }
    }

    @Test
    @DisplayName("newlines and the Unicode line and paragraph separators are rejected in key and value")
    void rejectsNewlineAndLineSeparators() {
        for (String bad : List.of("a\nb", "a\rb", "a\r\nb", "a b", "a b")) {
            assertThatThrownBy(() -> item(bad, "v")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item("k", bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a key one character over MAX_KEY_LENGTH is rejected")
    void rejectsKeyOverMax() {
        String key = "k".repeat(DataItem.MAX_KEY_LENGTH + 1);
        assertThatThrownBy(() -> item(key, "v"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 64");
    }

    @Test
    @DisplayName("a key of exactly MAX_KEY_LENGTH (64) is accepted")
    void acceptsKeyAtMax() {
        assertThat(DataItem.MAX_KEY_LENGTH).isEqualTo(64);
        assertThat(item("k".repeat(64), "v").key()).hasSize(64);
    }

    @Test
    @DisplayName("a value one character over MAX_VALUE_LENGTH is rejected")
    void rejectsValueOverMax() {
        String value = "v".repeat(DataItem.MAX_VALUE_LENGTH + 1);
        assertThatThrownBy(() -> item("k", value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 1024");
    }

    @Test
    @DisplayName("a value of exactly MAX_VALUE_LENGTH (1024) is accepted")
    void acceptsValueAtMax() {
        assertThat(DataItem.MAX_VALUE_LENGTH).isEqualTo(1024);
        assertThat(item("k", "v".repeat(1024)).value()).hasSize(1024);
    }

    @Test
    @DisplayName("';' and '~' (the legacy wire separators) are accepted, so E5b must encode safely")
    void acceptsSemicolonAndTildeWhichE5bMustEncode() {
        DataItem tricky = new DataItem("a;b~c", "x;y~z;;~~", 4, 2, 1);
        assertThat(tricky.key()).isEqualTo("a;b~c");
        assertThat(tricky.value()).isEqualTo("x;y~z;;~~");

        DataStore store = new DataStore();
        assertThat(store.apply(tricky)).isEqualTo(ApplyResult.APPLIED);
        assertThat(store.get("a;b~c")).contains(tricky);
    }

    @Test
    @DisplayName("a higher Lamport time wins within an epoch")
    void higherLamportWins() {
        DataItem older = new DataItem("k", "a", 4, 3, 1);
        DataItem newer = new DataItem("k", "b", 5, 1, 1);
        assertThat(newer.isNewerThan(older)).isTrue();
        assertThat(older.isNewerThan(newer)).isFalse();
    }

    @Test
    @DisplayName("a higher epoch beats a higher Lamport time and a higher origin")
    void higherEpochBeatsHigherLamport() {
        DataItem oldTerm = new DataItem("k", "a", 900, 5, 1);
        DataItem newTerm = new DataItem("k", "b", 1, 1, 2);
        assertThat(newTerm.isNewerThan(oldTerm)).isTrue();
        assertThat(oldTerm.isNewerThan(newTerm)).isFalse();
    }

    @Test
    @DisplayName("anything is newer than an absent item")
    void nullIsOlder() {
        assertThat(item("k", "v").isNewerThan(null)).isTrue();
    }

    @Test
    @DisplayName("an item is never newer than itself or than a same-version item with another value")
    void neverNewerThanItself() {
        DataItem a = new DataItem("k", "a", 7, 2, 1);
        DataItem sameVersionOtherValue = new DataItem("k", "b", 7, 2, 1);
        assertThat(a.isNewerThan(a)).isFalse();
        assertThat(a.isNewerThan(sameVersionOtherValue)).isFalse();
        assertThat(sameVersionOtherValue.isNewerThan(a)).isFalse();
    }

    @Test
    @DisplayName("over a grid of versions, exactly one of every distinct pair is newer (a total order)")
    void orderIsTotalOverGrid() {
        List<DataItem> grid = new ArrayList<>();
        for (long epoch = 1; epoch <= 3; epoch++) {
            for (long lamport = 0; lamport <= 3; lamport++) {
                for (int origin = 1; origin <= 3; origin++) {
                    grid.add(new DataItem("k", "v", lamport, origin, epoch));
                }
            }
        }
        for (DataItem a : grid) {
            for (DataItem b : grid) {
                if (a.equals(b)) {
                    assertThat(a.isNewerThan(b)).isFalse();
                } else {
                    assertThat(a.isNewerThan(b) ^ b.isNewerThan(a)).as("%s vs %s", a, b).isTrue();
                }
                for (DataItem c : grid) {
                    if (a.isNewerThan(b) && b.isNewerThan(c)) {
                        assertThat(a.isNewerThan(c)).as("transitive %s %s %s", a, b, c).isTrue();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("sameVersionAs compares epoch, Lamport time and origin, and ignores the value")
    void sameVersionAsIgnoresValue() {
        DataItem a = new DataItem("k", "a", 7, 2, 1);
        assertThat(a.sameVersionAs(new DataItem("k", "zzz", 7, 2, 1))).isTrue();
        assertThat(a.sameVersionAs(new DataItem("k", "a", 8, 2, 1))).isFalse();
        assertThat(a.sameVersionAs(new DataItem("k", "a", 7, 3, 1))).isFalse();
        assertThat(a.sameVersionAs(new DataItem("k", "a", 7, 2, 2))).isFalse();
        assertThat(a.sameVersionAs(null)).isFalse();
    }
}
