package io.github.emanuelluis.vaccine.campaigns;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strong entity tags of campaigns: the row {@code version} in double quotes. */
final class ETags {

    private static final Pattern STRONG = Pattern.compile("\\s*\"(\\d{1,9})\"\\s*");

    private ETags() {}

    static String format(int version) {
        return "\"" + version + "\"";
    }

    /** The version named by an {@code If-Match} value, or -1 when it is not one of our tags (never matches). */
    static int parse(String ifMatch) {
        Matcher m = STRONG.matcher(ifMatch);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }
}
