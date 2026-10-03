package cn.hnust.selection.service;

import cn.hnust.selection.security.AccountPrincipal;
import org.springframework.security.core.Authentication;

/**
 * 管理员能力以及学院、批次数据范围判断的服务契约。
 * 管理员业务代码应使用带目标范围的重载；具体授权匹配规则由 service.impl.AccountAuthorizationServiceImpl 负责。
 */
public interface AccountAuthorizationService {
    /**
     * 判断认证主体是否具备某项能力代码，不校验具体学院或批次。
     * 只能用于确实不关联具体业务对象的检查；保护具体记录时必须调用带范围的重载。
     */
    boolean hasCapability(Authentication authentication, String capabilityCode);

    /**
     * 判断认证主体在给定学院/批次范围内是否具备指定能力。
     * batchId 为空代表检查学院级授权；批次专属授权不能替代学院级授权。
     */
    boolean hasCapability(Authentication authentication, String capabilityCode, Long collegeId, Long batchId);

    /** 对已解析的本系统认证主体执行角色、能力和数据范围判断。 */
    boolean hasCapability(AccountPrincipal principal, String capabilityCode, Long collegeId, Long batchId);

    /** 若主体缺少指定能力或范围不匹配，则抛出统一的 SCOPE_FORBIDDEN 异常。 */
    void requireCapability(AccountPrincipal principal, String capabilityCode, Long collegeId, Long batchId);
}
