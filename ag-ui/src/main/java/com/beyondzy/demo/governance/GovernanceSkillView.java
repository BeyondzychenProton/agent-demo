package com.beyondzy.demo.governance;

import java.util.List;

/** Snapshot {@code skills[]} 中的一项。instructions 与 instructionsRef 互斥。 */
public record GovernanceSkillView(
        String skillId,
        String name,
        String description,
        String version,
        String instructions,
        String instructionsRef,
        String contentDigest,
        Integer byteSize,
        List<GovernanceSkillBinding> toolBindings,
        List<String> resourcePaths) {

    public GovernanceSkillView(String skillId, String name, String description, String version,
            String instructions, String instructionsRef, String contentDigest, Integer byteSize,
            List<GovernanceSkillBinding> toolBindings) {
        this(skillId, name, description, version, instructions, instructionsRef, contentDigest,
                byteSize, toolBindings, List.of());
    }

    public GovernanceSkillView {
        resourcePaths = resourcePaths == null ? List.of() : List.copyOf(resourcePaths);
        toolBindings = toolBindings == null ? List.of() : List.copyOf(toolBindings);
    }
}
