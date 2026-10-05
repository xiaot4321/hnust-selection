package cn.hnust.selection.request;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.util.List;

public class BulkDecideTeacherApplicationsRequest {
    @NotEmpty @Size(max = 500) @Valid
    private List<Item> items;
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
    public static class Item {
        @NotNull @Positive private Long applicationId;
        @NotNull @Pattern(regexp = "ADMIT|NOT_ADMITTED") private String decision;
        public Long getApplicationId() { return applicationId; }
        public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }
        public String getDecision() { return decision; }
        public void setDecision(String decision) { this.decision = decision; }
    }
}
