package com.obliterateharness.harness;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Line diff between two drafts (LCS), compacted with a little context around changes. */
public final class TextDiff {
    private TextDiff() {}

    /** @param op "=" unchanged, "+" added, "-" removed, "…" skipped unchanged lines */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String op, String text) {}

    public record Result(int added, int removed, List<Line> lines) {}

    public static Result diff(String a, String b, int context) {
        String[] x = split(a), y = split(b);
        int n = x.length, m = y.length;
        if ((long) n * m > 25_000_000L) {
            // too big for LCS: report a full replacement
            List<Line> out = new ArrayList<>();
            for (String s : x) out.add(new Line("-", s));
            for (String s : y) out.add(new Line("+", s));
            return new Result(m, n, out);
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x[i].equals(y[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Line> raw = new ArrayList<>();
        int i = 0, j = 0, added = 0, removed = 0;
        while (i < n && j < m) {
            if (x[i].equals(y[j])) {
                raw.add(new Line("=", x[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                raw.add(new Line("-", x[i++]));
                removed++;
            } else {
                raw.add(new Line("+", y[j++]));
                added++;
            }
        }
        while (i < n) {
            raw.add(new Line("-", x[i++]));
            removed++;
        }
        while (j < m) {
            raw.add(new Line("+", y[j++]));
            added++;
        }
        return new Result(added, removed, compact(raw, context));
    }

    private static List<Line> compact(List<Line> raw, int context) {
        boolean[] keep = new boolean[raw.size()];
        for (int k = 0; k < raw.size(); k++) {
            if (!raw.get(k).op().equals("=")) {
                for (int c = Math.max(0, k - context); c <= Math.min(raw.size() - 1, k + context); c++) keep[c] = true;
            }
        }
        List<Line> out = new ArrayList<>();
        int skipped = 0;
        for (int k = 0; k < raw.size(); k++) {
            if (keep[k]) {
                if (skipped > 0) {
                    out.add(new Line("…", skipped + com.obliterateharness.i18n.I18n.t(" unchanged lines", " linhas inalteradas")));
                    skipped = 0;
                }
                out.add(raw.get(k));
            } else {
                skipped++;
            }
        }
        if (skipped > 0) out.add(new Line("…", skipped + com.obliterateharness.i18n.I18n.t(" unchanged lines", " linhas inalteradas")));
        return out;
    }

    private static String[] split(String s) {
        return s == null || s.isEmpty() ? new String[0] : s.split("\\r?\\n", -1);
    }
}
