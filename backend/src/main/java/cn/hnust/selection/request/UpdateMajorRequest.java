package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/** 修改专业名称、有效期或启用状态；学院和专业代码保持不变以保护历史引用。 */
public class UpdateMajorRequest {
    @NotBlank @Size(max = 128) private String name;
    @NotNull private Boolean active;
    @Size(max = 10) private String validFrom;
    @Size(max = 10) private String validTo;
    @NotBlank @Size(max = 2000) private String changeBasis;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public String getValidFrom() { return validFrom; }
    public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
    public String getValidTo() { return validTo; }
    public void setValidTo(String validTo) { this.validTo = validTo; }
    public String getChangeBasis() { return changeBasis; }
    public void setChangeBasis(String changeBasis) { this.changeBasis = changeBasis; }
}
