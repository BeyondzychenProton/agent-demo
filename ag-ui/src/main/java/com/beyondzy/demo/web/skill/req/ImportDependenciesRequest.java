package com.beyondzy.demo.web.skill.req;

import com.beyondzy.demo.service.skill.SkillImportService;
import java.util.List;

/** 整组覆盖导入批次里的依赖映射。源标识和是否必需由服务端保留。 */
public record ImportDependenciesRequest(String revision, List<SkillImportService.DependencyInput> items) {}
