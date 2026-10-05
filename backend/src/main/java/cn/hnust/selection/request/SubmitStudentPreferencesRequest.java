package cn.hnust.selection.request;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.util.List;

public class SubmitStudentPreferencesRequest {
    @Positive
    private Integer identityClassificationVersion;
    @NotEmpty
    @Size(min = 1, max = 3)
    @Valid
    private List<PreferenceItemRequest> items;

    public Integer getIdentityClassificationVersion() { return identityClassificationVersion; }
    public void setIdentityClassificationVersion(Integer identityClassificationVersion) { this.identityClassificationVersion = identityClassificationVersion; }
    public List<PreferenceItemRequest> getItems() { return items; }
    public void setItems(List<PreferenceItemRequest> items) { this.items = items; }

    public static class PreferenceItemRequest {
        @Positive
        private Long teacherId;
        @Positive
        private Integer preferenceOrder;
        public Long getTeacherId() { return teacherId; }
        public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
        public Integer getPreferenceOrder() { return preferenceOrder; }
        public void setPreferenceOrder(Integer preferenceOrder) { this.preferenceOrder = preferenceOrder; }
    }
}
