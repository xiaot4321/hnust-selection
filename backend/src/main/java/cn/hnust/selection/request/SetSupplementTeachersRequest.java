package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

/** 完整替换本批次获得补选许可的导师集合。 */
public class SetSupplementTeachersRequest {
    @NotNull private List<@NotNull Long> teacherIds;
    @NotBlank @Size(max = 4000) private String reason;
    public List<Long> getTeacherIds() { return teacherIds; }
    public void setTeacherIds(List<Long> teacherIds) { this.teacherIds = teacherIds; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
