package com.beyondzy.demo.contract;

/** 列表页码。从 1 开始，每页最多 100 条。各模块仍抛自己的异常。 */
public final class Pages {
    public static final int MAX_SIZE = 100;

    private Pages() {}

    public static boolean valid(int page, int size) {
        return page >= 1 && size >= 1 && size <= MAX_SIZE;
    }
}
