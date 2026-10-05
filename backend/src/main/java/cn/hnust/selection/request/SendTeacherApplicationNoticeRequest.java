package cn.hnust.selection.request;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.util.List;

/** 通知只引用本人申请；接收者账号由服务端按引用解析。 */
public class SendTeacherApplicationNoticeRequest {
    @NotBlank @Size(max = 120)
    private String title;
    @NotBlank @Size(max = 4000)
    private String content;
    @NotEmpty @Size(max = 500) @Valid
    private List<ApplicationReference> applicationReferences;
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<ApplicationReference> getApplicationReferences() { return applicationReferences; }
    public void setApplicationReferences(List<ApplicationReference> references) { this.applicationReferences = references; }

    public static class ApplicationReference {
        @NotNull @Pattern(regexp = "ROUND|SUPPLEMENT")
        private String applicationType;
        @NotNull
        private Long applicationId;
        public String getApplicationType() { return applicationType; }
        public void setApplicationType(String applicationType) { this.applicationType = applicationType; }
        public Long getApplicationId() { return applicationId; }
        public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }
    }
}
