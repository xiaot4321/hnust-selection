package cn.hnust.selection.common;

import java.util.List;

/** 通用分页响应，字段与 API 约定一致。 */
public class PageResult<T> {
    private List<T> items;
    private long total;
    private int pageNo;
    private int pageSize;

    public PageResult() { }
    public PageResult(List<T> items, long total, int pageNo, int pageSize) {
        this.items = items;
        this.total = total;
        this.pageNo = pageNo;
        this.pageSize = pageSize;
    }
    public List<T> getItems() { return items; }
    public void setItems(List<T> items) { this.items = items; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public int getPageNo() { return pageNo; }
    public void setPageNo(int pageNo) { this.pageNo = pageNo; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
