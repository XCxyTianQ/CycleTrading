package com.cycletrading.util;

/** 金额/价格/利率统一格式化。 */
public final class Money {

    private Money() {
    }

    /** 千分位整数。 */
    public static String fmt(long n) {
        return String.format("%,d", n);
    }

    /** 毫绿宝石 → 展示（整数显示整数，否则 3 位小数）。 */
    public static String fmtPrice(long milli) {
        if (milli % 1000 == 0) {
            return fmt(milli / 1000);
        }
        return String.format("%.3f", milli / 1000.0);
    }

    /** 基点利率 → 百分比（两位小数）。 */
    public static String fmtRate(int bp) {
        return String.format("%.2f", bp / 100.0) + "%";
    }

    /** 倍率 → x.xxx×。 */
    public static String fmtMultiplier(double m) {
        return String.format("%.3f", m) + "×";
    }
}
