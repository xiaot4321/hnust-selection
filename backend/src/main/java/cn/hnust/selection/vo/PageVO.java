package cn.hnust.selection.vo;

import java.util.List;

/** 普通分页查询的统一响应数据。 */
public class PageVO<T> {
    private final List<T> items;
    private final long total;
    private final int pageNo;
    private final int pageSize;

    public PageVO(List<T> items, long total, int pageNo, int pageSize) {
        this.items = items;
        this.total = total;
        this.pageNo = pageNo;
        this.pageSize = pageSize;
    }

    public List<T> getItems() {
        return items;
    }
    public long getTotal() {
        return total;
    }
    public int getPageNo() {
        return pageNo;
    }
    public int getPageSize() {
        return pageSize;
    }
}
