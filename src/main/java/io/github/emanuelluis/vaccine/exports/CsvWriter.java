package io.github.emanuelluis.vaccine.exports;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** CSV for Excel in pt-BR: UTF-8 with BOM, {@code ;} separator, decimal comma, CRLF line ends. */
final class CsvWriter {

    private static final String BOM = "﻿";

    private CsvWriter() {}

    /** Header plus rows; null cells are empty. */
    static byte[] write(List<String> header, List<List<Object>> rows) {
        var out = new StringBuilder(BOM).append(String.join(";", header)).append("\r\n");
        for (List<Object> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    out.append(';');
                }
                out.append(cell(row.get(i)));
            }
            out.append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString().replace('.', ',');
        }
        if (!(v instanceof CharSequence)) {
            return v.toString();
        }
        String s = v.toString();
        // A spreadsheet runs a text cell that starts with one of these as a formula.
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
