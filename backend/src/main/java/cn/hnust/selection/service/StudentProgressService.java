package cn.hnust.selection.service;

import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.StudentProgressVO;

public interface StudentProgressService {
    StudentProgressVO getOwnProgress(AccountPrincipal actor, Long batchId);
}
