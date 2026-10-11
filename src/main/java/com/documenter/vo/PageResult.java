package com.documenter.vo;

import lombok.Data;

import java.util.List;

/**
 * 分页结果（TODO 3.3 文件列表需要分页）。
 *
 * <p>不直接把 MyBatis-Plus 的 IPage 返回给前端：它的字段命名与含义会随版本变化，
 * 且会把内部查询条件一起序列化出去。
 *
 * <p>刻意不提供基于 IPage 的转换方法：MyBatis-Plus 的分页依赖
 * mybatis-plus-jsqlparser 提供的 PaginationInnerInterceptor，构件缺失时
 * selectPage 会静默返回全量数据。为避免被误用，这里只保留显式分页所需的字段，
 * 由调用方自己填 total。
 */
@Data
public class PageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;
    private long pages;

    /** 按 total 与每页条数计算总页数。 */
    public static long pagesOf(long total, long size) {
        if (size <= 0) {
            return 0;
        }
        return (total + size - 1) / size;
    }
}
