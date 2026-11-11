package com.example.clouddisk.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页返回结构
 */
@Data
public class PageResult<T> implements Serializable {

    /** 当前页数据 */
    private List<T> records;
    /** 总条数 */
    private long total;
    /** 当前页码（从 1 开始） */
    private long page;
    /** 每页条数 */
    private long size;
    /** 总页数 */
    private long pages;

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> r = new PageResult<>();
        r.records = records;
        r.total = total;
        r.page = page;
        r.size = size;
        r.pages = size <= 0 ? 0 : (total + size - 1) / size;
        return r;
    }
}
