package cn.hnust.selection.request;

import javax.validation.constraints.Size;

/** 学生本人资料的白名单局部更新请求。身份分类、账号状态和附件引用不接受客户端修改。 */
public class UpdateStudentProfileRequest {
    private String biography;
    @Size(max = 255)
    private String contact;

    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
}
