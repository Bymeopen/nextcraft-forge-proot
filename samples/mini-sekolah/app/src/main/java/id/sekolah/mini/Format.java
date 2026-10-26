package id.sekolah.mini;

import android.content.Context;

import java.util.Locale;

/** Small helpers for showing averages the same way on every screen. */
final class Format {
    private Format() {}

    static String average(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.forLanguageTag("id-ID"), "%.1f", value);
    }

    /** Green from 75, red below 60, amber in between; grey when there is no grade. */
    static int scoreColor(Context context, double value) {
        int res;
        if (Double.isNaN(value)) res = R.color.muted;
        else if (value >= 75) res = R.color.good;
        else if (value < 60) res = R.color.bad;
        else res = R.color.warn;
        return context.getColor(res);
    }
}
