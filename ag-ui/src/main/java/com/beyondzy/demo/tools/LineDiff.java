package com.beyondzy.demo.tools;

import java.util.ArrayList;
import java.util.List;

/** 行级 diff 统计，只用于文件类工具卡片上的 {@code +新增 -删除}。 */
final class LineDiff {

    private static final int MAX_CELLS = 4_000_000;

    private LineDiff() {}

    /** @return {@code [added, deleted]} */
    static int[] stats(String oldText, String newText) {
        List<String> before = lines(oldText);
        List<String> after = lines(newText);
        if ((long) before.size() * after.size() > MAX_CELLS) {
            return new int[] {after.size(), before.size()};
        }
        int[][] lcs = new int[before.size() + 1][after.size() + 1];
        for (int i = before.size() - 1; i >= 0; i--) {
            for (int j = after.size() - 1; j >= 0; j--) {
                lcs[i][j] =
                        before.get(i).equals(after.get(j))
                                ? lcs[i + 1][j + 1] + 1
                                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int common = lcs[0][0];
        return new int[] {after.size() - common, before.size() - common};
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> values = new ArrayList<>(List.of(text.split("\\R", -1)));
        if (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) {
            values.remove(values.size() - 1);
        }
        return values;
    }
}
