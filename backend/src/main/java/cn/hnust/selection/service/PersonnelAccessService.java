package cn.hnust.selection.service;

import cn.hnust.selection.security.AccountPrincipal;

/**
 * 人员管理模块共用的操作者和学院范围校验契约。
 *
 * <p>各人员管理用例在读取或修改学院数据前调用该服务。它会从数据库刷新会话主体，
 * 再按当前账号授权校验学院范围，避免旧 Session 中缓存的授权状态继续生效。</p>
 */
public interface PersonnelAccessService {
    /** 返回数据库中的当前账号主体；账号不存在、停用或角色不符时抛出统一业务异常。 */
    AccountPrincipal refreshActor(AccountPrincipal actor);

    /** 校验操作者当前具有指定学院的人员管理能力，并返回刷新后的主体。 */
    AccountPrincipal requireCollege(AccountPrincipal actor, Long collegeId);
}
