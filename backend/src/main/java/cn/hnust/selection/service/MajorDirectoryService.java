package cn.hnust.selection.service;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.vo.MajorOptionVO;

public interface MajorDirectoryService {
    PageResult<MajorOptionVO> listMajors(Long collegeId, boolean active, int pageNo, int pageSize);
}
