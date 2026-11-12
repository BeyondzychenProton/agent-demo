package com.beyondzy.demo.governance;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Skill metadata and authorized reads for a single immutable Snapshot. */
public final class GovernanceSkillRepository implements AgentSkillRepository, LazyResourceCapable {
    private final GovernanceGateway gateway;
    private final String snapshotId;
    private final Map<String, GovernanceSkillView> skills = new LinkedHashMap<>();
    private final Map<String, String> toolNames;

    public GovernanceSkillRepository(GovernanceGateway gateway, GovernanceSnapshot snapshot,
            Map<String, String> toolNames) {
        this.gateway = gateway;
        this.snapshotId = snapshot.snapshotId();
        this.toolNames = Map.copyOf(toolNames);
        for (GovernanceSkillView skill : snapshot.skills()) {
            String id = skill.skillId();
            String name = id.matches("[a-z0-9][a-z0-9-]{0,63}") ? id
                    : "governed-" + UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
            if (skills.putIfAbsent(name, skill) != null) {
                throw new IllegalArgumentException("治理 Skill 名称重复：" + name);
            }
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        GovernanceSkillView skill = skills.get(name);
        if (skill == null) return null;
        String description = skill.description();
        if (description == null || description.isBlank()) description = name;
        return new AgentSkill(name, description, "Governed Skill", Map.of(), getSource()) {
            @Override
            public String getSkillContent() {
                String markdown = gateway.fetchInstructions(snapshotId, skill.skillId());
                String body = markdown.stripLeading().startsWith("---")
                        ? MarkdownSkillParser.parse(markdown).getContent() : markdown;
                StringBuilder output = new StringBuilder(body);
                for (GovernanceSkillBinding binding : skill.toolBindings()) {
                    String registered = toolNames.get(binding.toolUri());
                    if (registered == null) continue;
                    String alias = binding.alias();
                    if (alias == null || alias.isBlank()) alias = binding.modelName();
                    output.append("\n- 治理工具映射：").append(alias)
                            .append(" → `").append(registered).append("`");
                }
                return output.toString();
            }
        };
    }

    @Override
    public List<String> getAllSkillNames() { return List.copyOf(skills.keySet()); }

    @Override
    public List<AgentSkill> getAllSkills() { return skills.keySet().stream().map(this::getSkill).toList(); }

    @Override
    public SkillResources resourcesFor(String name, RuntimeContext context) {
        GovernanceSkillView skill = skills.get(name);
        if (skill == null) return SkillResources.empty();
        return new SkillResources() {
            @Override
            public Optional<String> read(String path) {
                if (!list().contains(path)) return Optional.empty();
                return Optional.of(gateway.fetchResource(snapshotId, skill.skillId(), path));
            }

            @Override
            public Optional<byte[]> readBinary(String path) { return Optional.empty(); }

            @Override
            public List<String> list() { return skill.resourcePaths(); }
        };
    }

    @Override
    public boolean save(List<AgentSkill> entries, boolean force) { return false; }

    @Override
    public boolean delete(String name) { return false; }

    @Override
    public boolean skillExists(String name) { return skills.containsKey(name); }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("governance", snapshotId, false);
    }

    @Override
    public String getSource() { return "governance"; }

    @Override
    public void setWriteable(boolean writeable) {
        if (writeable) throw new UnsupportedOperationException("治理 Skill 仓库只读");
    }

    @Override
    public boolean isWriteable() { return false; }
}
