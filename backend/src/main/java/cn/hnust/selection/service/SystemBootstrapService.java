package cn.hnust.selection.service;

import cn.hnust.selection.bootstrap.InitialAdminBootstrapResult;

/**
 * 首次部署时创建总管理员账号的受限服务接口。
 *
 * <p>此服务只由显式开启的一次性本机命令行入口调用，不提供免认证 HTTP 端点。Service 层负责
 * 单总管理员规则、学院核对、随机凭证生成、事务和审计；Repository 仅负责 SQL。</p>
 */
public interface SystemBootstrapService {

    /**
     * 创建系统首位 ADMIN 并授予其唯一的管理员账号管理能力。
     *
     * @param loginIdentifier 首次登录要输入的账号标识
     * @param collegeCode 学院代码；可为空，空值时生成并返回一个临时代码
     * @param collegeName 学院正式名称
     * @return 包含单次显示临时凭证的结果；调用方须仅在受控终端展示一次
     */
    InitialAdminBootstrapResult initializeInitialAdmin(
        String loginIdentifier, String collegeCode, String collegeName);
}
