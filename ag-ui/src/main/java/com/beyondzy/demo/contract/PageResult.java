package com.beyondzy.demo.contract;

import java.util.List;

/** 偏移分页结果。页码从 1 开始，JSON 字段保持 items、total、page、size。 */
public record PageResult<T>(List<T> items, long total, int page, int size) {
    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 内存列表按同一套页码切片，不访问数据库。 */
    public static <T> PageResult<T> slice(List<T> rows, int page, int size) {
        List<T> source = rows == null ? List.of() : rows;
        int from = (int) Math.min((long) (page - 1) * size, source.size());
        int to = (int) Math.min((long) from + size, source.size());
        return new PageResult<>(source.subList(from, to), source.size(), page, size);
    }
}
