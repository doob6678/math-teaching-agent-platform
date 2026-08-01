package com.doob.mathagent.teaching.service.rendering;

import com.doob.mathagent.teaching.service.rendering.standard.StandardHandoutTemplateStrategy;
import com.doob.mathagent.teaching.service.rendering.zhao.ZhaoHandoutTemplateStrategy;
import java.util.List;

/** Selects exactly one renderer-owned visual strategy for a backend template identity. */
public final class HandoutTemplateStrategies {
    /**
     * Registration order is intentional: narrowly scoped visual families are evaluated before the standard family.
     * Adding a template means adding one strategy package and one registry entry, never editing the exporter.
     */
    private static final List<HandoutTemplateStrategy> STRATEGIES = List.of(
            new ZhaoHandoutTemplateStrategy(),
            new StandardHandoutTemplateStrategy());

    private HandoutTemplateStrategies() {
    }

    /** Resolves the one visual family that owns the persisted template identity. */
    public static HandoutTemplateStrategy forTemplate(String templateName) {
        return STRATEGIES.stream()
                .filter(strategy -> strategy.supports(templateName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有讲义渲染策略可处理模板：" + templateName));
    }
}
