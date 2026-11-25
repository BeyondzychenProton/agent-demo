package com.beyondzy.demo.web.skill.req;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.service.skill.SkillImportService;
import java.util.List;

/** 整组覆盖导入批次里的依赖映射。源标识和是否必需由服务端保留。 */
@Schema(description = "整组覆盖导入批次里的依赖映射。")
public record ImportDependenciesRequest(@Schema(description = "乐观锁版本号") String revision, @Schema(description = "依赖映射") List<SkillImportService.DependencyInput> items) {}
