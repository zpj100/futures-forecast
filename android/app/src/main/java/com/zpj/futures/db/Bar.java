package com.zpj.futures.db;

/** 一根 K 线。ts 为毫秒时间戳。 */
public class Bar {
    public long ts;
    public double o, h, l, c, v;

    public Bar() {
    }

    public Bar(long ts, double o, double h, double l, double c, double v) {
        this.ts = ts;
        this.o = o;
        this.h = h;
        this.l = l;
        this.c = c;
        this.v = v;
    }
}
