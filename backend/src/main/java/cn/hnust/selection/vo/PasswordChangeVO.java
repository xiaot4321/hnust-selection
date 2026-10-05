package cn.hnust.selection.vo;

/**
 * 改密端点成功后的状态回执。
 * changed 表示密码写入已完成；mustChangePassword 是服务端更新后的状态，成功设置正式密码后应为 false。
 */
public class PasswordChangeVO {
    private final boolean changed;
    private final boolean mustChangePassword;

    public PasswordChangeVO(boolean changed, boolean mustChangePassword) {
        this.changed = changed;
        this.mustChangePassword = mustChangePassword;
    }

    public boolean isChanged() {
        return changed;
    }
    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
}
