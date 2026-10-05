package cn.hnust.selection.vo;

import java.util.List;

public class TeacherBulkOperationVO {
    private Long operationId;
    private String status;
    private List<Item> items;
    public Long getOperationId() { return operationId; }
    public void setOperationId(Long operationId) { this.operationId = operationId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
    public static class Item {
        private Long applicationId;
        private Integer executionOrder;
        private String itemResult;
        private String code;
        private String message;
        private Long relationId;
        public Long getApplicationId() { return applicationId; }
        public void setApplicationId(Long value) { this.applicationId = value; }
        public Integer getExecutionOrder() { return executionOrder; }
        public void setExecutionOrder(Integer value) { this.executionOrder = value; }
        public String getItemResult() { return itemResult; }
        public void setItemResult(String value) { this.itemResult = value; }
        public String getCode() { return code; }
        public void setCode(String value) { this.code = value; }
        public String getMessage() { return message; }
        public void setMessage(String value) { this.message = value; }
        public Long getRelationId() { return relationId; }
        public void setRelationId(Long value) { this.relationId = value; }
    }
}
