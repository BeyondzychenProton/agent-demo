package com.beyondzy.demo.governance;

/** Snapshot {@code skills[].toolBindings[]} 中的一条交付绑定。 */
public record GovernanceSkillBinding(
        String skillToolBindingId, String modelName, String alias, String toolUri) {}
