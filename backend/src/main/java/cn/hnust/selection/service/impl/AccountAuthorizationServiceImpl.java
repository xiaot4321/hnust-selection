package cn.hnust.selection.service.impl;

import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.enums.AdminCapabilityCode;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.security.AccountAuthorization;
import cn.hnust.selection.security.AccountPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 管理员能力与学院/批次数据范围的具体判断实现。
 *
 * <p>普通 ADMIN 只是允许进入管理员 API 的粗粒度角色，仍须在 account_authorization 中拥有所需能力，
 * 且请求目标学院和批次必须落在授权范围内。持有保留能力 ADMIN_ACCOUNT_MANAGER 的总管理员
 * 自动拥有全部已登记管理员能力和全系统范围；它仍须通过领域 Service 执行业务状态和对象校验。
 * COLLEGE_ADMIN 是学院级人员、专业和年度资格管理能力；BATCH_AUDIT 仍可按学院或单个批次缩小范围。</p>
 *
 * <p>Bean 名称固定为 {@code accountAuthorization}，可供 Spring Security 的方法表达式调用。
 * 管理员业务 Service 应传入目标数据真实所属的学院和批次，不能只用不带范围的重载保护数据操作。</p>
 */
@Service("accountAuthorization")
public class AccountAuthorizationServiceImpl implements AccountAuthorizationService {
    private static final String ADMIN_ACCOUNT_MANAGER = "ADMIN_ACCOUNT_MANAGER";

    /**
     * 检查认证对象是否有指定能力，但不限定目标学院或批次。
     * 适用于判断某项全局能力是否存在；修改具体业务数据时应使用带范围的重载。
     */
    @Override
    public boolean hasCapability(Authentication authentication, String capabilityCode) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal)) return false;
        AccountPrincipal principal = (AccountPrincipal) authentication.getPrincipal();
        return hasCapability(principal, capabilityCode, null, null);
    }

    /**
     * 从 Spring Security Authentication 取出本系统主体，再执行带范围的授权检查。
     * 非本系统主体、空认证或匿名认证一律无权，避免把其他认证类型误当作 ADMIN。
     */
    @Override
    public boolean hasCapability(Authentication authentication, String capabilityCode,
                                 Long collegeId, Long batchId) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal)) return false;
        return hasCapability((AccountPrincipal) authentication.getPrincipal(), capabilityCode, collegeId, batchId);
    }

    /**
     * 按主体上的有效授权列表匹配能力与数据范围。
     *
     * <p>普通管理员的授权 collegeId 必须与目标学院一致。授权 batchId 为空表示该学院下不限定批次；
     * 授权指定 batchId 时，只能访问同一个批次。调用方若不提供目标批次，则不能拿批次专属授权
     * 代替学院级授权，因为无法证明目标记录属于被授权批次。持有保留能力
     * {@code ADMIN_ACCOUNT_MANAGER} 的总管理员则自动拥有能力目录中所有管理员业务能力和全系统数据范围。</p>
     *
     * @param principal 当前请求的主体
     * @param capabilityCode 所需能力代码
     * @param collegeId 目标学院；为空时只匹配能力及批次层级
     * @param batchId 目标批次；为空时要求对应授权不被限制在某个批次
     * @return 存在一条同时满足角色、能力和范围约束的授权时返回 {@code true}
     */
    @Override
    public boolean hasCapability(AccountPrincipal principal, String capabilityCode,
                                 Long collegeId, Long batchId) {
        // 普通 ADMIN 角色不会自动附带管理能力；其能力和学院/批次范围仍来自 account_authorization。
        if (principal == null || principal.getRole() != AccountRole.ADMIN) return false;
        // 总管理员是唯一的系统级管理员：其保留管理能力隐含所有已登记 ADMIN 能力和全学院/批次范围。
        // 学生与导师端点仍由 ROLE_STUDENT / ROLE_TEACHER 门禁保护，业务状态和审计校验也不会被绕过。
        if (isRegisteredAdminCapability(capabilityCode) && hasAccountManagerCapability(principal)) return true;
        for (AccountAuthorization authorization : principal.getAuthorizations()) {
            // 一条授权必须同时匹配能力代码和数据范围，不能把不同授权的字段拼接起来。
            if (!capabilityCode.equals(authorization.getCapabilityCode())) continue;
            // 明确目标学院时必须相同；不能仅凭 capability 名称跨学院访问。
            if (collegeId != null && !collegeId.equals(authorization.getCollegeId())) continue;
            // batch_id 为空的授权覆盖该学院全部批次；限定到批次的授权不能扩展到其他批次。
            if (batchId != null && authorization.getBatchId() != null
                && !batchId.equals(authorization.getBatchId())) continue;
            if (batchId == null && authorization.getBatchId() != null) continue;
            return true;
        }
        return false;
    }

    /** 只把服务端能力目录中已登记的 ADMIN 能力视为“全部管理权限”，拼错的能力名仍拒绝。 */
    private boolean isRegisteredAdminCapability(String capabilityCode) {
        if (ADMIN_ACCOUNT_MANAGER.equals(capabilityCode)) return true;
        if (capabilityCode == null) return false;
        try {
            AdminCapabilityCode.valueOf(capabilityCode);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 总管理员身份来自有效的保留能力记录，而不是登录名或客户端提交字段。 */
    private boolean hasAccountManagerCapability(AccountPrincipal principal) {
        for (AccountAuthorization authorization : principal.getAuthorizations()) {
            if (ADMIN_ACCOUNT_MANAGER.equals(authorization.getCapabilityCode())) return true;
        }
        return false;
    }

    /**
     * 强制执行授权检查；将布尔判断转换为统一业务异常，供业务 Service 在写操作入口调用。
     */
    @Override
    public void requireCapability(AccountPrincipal principal, String capabilityCode,
                                  Long collegeId, Long batchId) {
        if (!hasCapability(principal, capabilityCode, collegeId, batchId)) {
            throw new ApiException("SCOPE_FORBIDDEN", "当前账号无权访问该学院或批次范围", HttpStatus.FORBIDDEN);
        }
    }
}
