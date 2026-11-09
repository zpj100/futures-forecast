package com.zpj.futures.data;

/** 三品种元数据，与 config.py 的 PRODUCTS 保持一致。 */
public class Products {
    public static final String[] CODES = {"AL", "AO", "AHD"};
    public static final String[] SHORT = {"沪铝", "氧化铝", "伦铝"};
    public static final String[] NAMES = {"电解铝（沪铝主力）", "氧化铝（沪氧化铝主力）", "伦铝（LME 3M）"};
    public static final String[] UNITS = {"元/吨", "元/吨", "美元/吨"};

    public static String shortOf(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return SHORT[i];
        return code;
    }

    public static String nameOf(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return NAMES[i];
        return code;
    }

    public static String unitOf(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return UNITS[i];
        return "";
    }
}
