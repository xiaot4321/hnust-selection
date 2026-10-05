package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.repository.MajorDirectoryRepository;
import cn.hnust.selection.service.MajorDirectoryService;
import cn.hnust.selection.vo.MajorOptionVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MajorDirectoryServiceImpl implements MajorDirectoryService {
    private final MajorDirectoryRepository repository;

    public MajorDirectoryServiceImpl(MajorDirectoryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MajorOptionVO> listMajors(Long collegeId, boolean active, int pageNo, int pageSize) {
        return new PageResult<MajorOptionVO>(repository.findMajors(collegeId, active, pageNo, pageSize),
            repository.countMajors(collegeId, active), pageNo, pageSize);
    }
}
