package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** Different endpoints validate their required fields explicitly, without accepting actor IDs. */
public class EmailVerificationRequest {
    @Size(max=128) private String loginIdentifier;
    @Size(max=254) private String email;
    @Size(max=128) private String currentPassword;
    @NotBlank(groups=Confirm.class) @Size(max=64) private String challengeId;
    @Size(max=6) private String code;
    @Size(max=128) private String newPassword;
    public interface Confirm { }
    public String getLoginIdentifier() { return loginIdentifier; }
    public void setLoginIdentifier(String value) { loginIdentifier=value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email=value; }
    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String value) { currentPassword=value; }
    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String value) { challengeId=value; }
    public String getCode() { return code; }
    public void setCode(String value) { code=value; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String value) { newPassword=value; }
}
