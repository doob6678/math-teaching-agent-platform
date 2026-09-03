package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService.CompileAttempt;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService.RenderedCompileResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 编译错误回喂重写闭环（2026-08-31）：用脚本化 compileOnce 替代真实 XeLaTeX，
 * 验证轮数上限、错误摘录传递与失败回退语义。
 */
class ModelLatexRepairLoopTest {

    /** 脚本化编译替身：按队列回放结果并记录每轮实际提交的源文本。 */
    private static final class Scripted extends TeachingHandoutPdfExportService {
        final List<String> attemptedSources = new ArrayList<>();
        final Deque<CompileAttempt> outcomes = new ArrayDeque<>();

        Scripted(CompileAttempt... scripted) {
            outcomes.addAll(List.of(scripted));
        }

        @Override
        CompileAttempt compileOnce(String taskId, String latexSource) {
            attemptedSources.add(latexSource);
            return outcomes.poll();
        }
    }

    private static CompileAttempt failure(String error) {
        return new CompileAttempt(Optional.empty(), true, error);
    }

    private static CompileAttempt success() {
        return new CompileAttempt(Optional.of(new byte[] {'%', 'P', 'D', 'F'}), true, "");
    }

    @Test
    void firstSuccessfulCompileSkipsTheRepairLoopEntirely() {
        Scripted service = new Scripted(success());
        java.util.concurrent.atomic.AtomicInteger repairCalls = new java.util.concurrent.atomic.AtomicInteger();
        service.configureLatexRepair((runId, latex, error, turn) -> {
            repairCalls.incrementAndGet();
            return Optional.of("unused");
        }, 2);

        RenderedCompileResult result = service.compileWithModelRepair("task-a", "ORIGINAL");

        assertThat(result.renderer()).isEqualTo("xelatex");
        assertThat(result.repairRounds()).isZero();
        assertThat(result.pdf()).isPresent();
        assertThat(repairCalls).hasValue(0);
    }

    @Test
    void failingCompileIsRewrittenByModelAndRecompiled() {
        Scripted service = new Scripted(failure("! Missing $ inserted.\n"), success());
        List<String> seenErrors = new ArrayList<>();
        List<Integer> seenRounds = new ArrayList<>();
        service.configureLatexRepair((runId, latex, error, turn) -> {
            seenErrors.add(error);
            seenRounds.add(turn);
            return Optional.of("REPAIRED-" + turn);
        }, 2);

        RenderedCompileResult result = service.compileWithModelRepair("task-a", "ORIGINAL");

        assertThat(result.renderer()).isEqualTo("xelatex-model-repair");
        assertThat(result.repairRounds()).isEqualTo(1);
        assertThat(result.pdf()).isPresent();
        assertThat(service.attemptedSources).containsExactly("ORIGINAL", "REPAIRED-1");
        assertThat(seenErrors).allSatisfy(error -> assertThat(error).startsWith("! Missing $ inserted."));
        assertThat(seenRounds).containsExactly(1);
    }

    @Test
    void exhaustedRoundsFallBackToEmptyForTheRecoveryStub() {
        Scripted service = new Scripted(
                failure("E1"), failure("E2"), failure("E3"), failure("E4"));
        List<String> fedSources = new ArrayList<>();
        service.configureLatexRepair((runId, latex, error, turn) -> {
            fedSources.add(latex);
            return Optional.of("REPAIRED-" + turn);
        }, 3);

        RenderedCompileResult result = service.compileWithModelRepair("task-a", "ORIGINAL");

        assertThat(result.pdf()).isEmpty();
        // 每轮修复后都真实重编译；第 3 轮失败后不再花第 4 次模型钱。
        assertThat(service.attemptedSources).containsExactly("ORIGINAL", "REPAIRED-1", "REPAIRED-2", "REPAIRED-3");
        // 第 N 轮发给模型的失败源是第 N-1 轮的修复产物；第 3 轮失败后直接回退，不再请求修复。
        assertThat(fedSources).containsExactly("ORIGINAL", "REPAIRED-1", "REPAIRED-2");
        assertThat(result.renderer()).isEqualTo("xelatex-model-repair");
        assertThat(result.repairRounds()).isEqualTo(3);
    }

    @Test
    void repairRefusalOrUnconfiguredClientKeepsTheOldBehavior() {
        Scripted refusing = new Scripted(failure("! Undefined control sequence."));
        refusing.configureLatexRepair((runId, latex, error, turn) -> Optional.empty(), 2);
        assertThat(refusing.compileWithModelRepair("task-a", "ORIGINAL").pdf()).isEmpty();
        assertThat(refusing.attemptedSources).containsExactly("ORIGINAL");

        Scripted noClient = new Scripted(failure("! Missing $ inserted."));
        assertThat(noClient.compileWithModelRepair("task-a", "ORIGINAL").pdf()).isEmpty();
        assertThat(noClient.attemptedSources).containsExactly("ORIGINAL");
    }

    @Test
    void unavailableEngineNeverEntersTheRepairLoop() {
        // 引擎缺失时修复毫无意义：错误不来自文档内容，也不得把模型 token 花在这种环境故障上。
        Scripted missingEngine = new Scripted(new CompileAttempt(Optional.empty(), false, ""));
        java.util.concurrent.atomic.AtomicInteger repairCalls = new java.util.concurrent.atomic.AtomicInteger();
        missingEngine.configureLatexRepair((runId, latex, error, turn) -> {
            repairCalls.incrementAndGet();
            return Optional.of("x");
        }, 1);
        RenderedCompileResult result = missingEngine.compileWithModelRepair("task-a", "ORIGINAL");
        assertThat(result.renderer()).isEqualTo("xelatex");
        assertThat(repairCalls).hasValue(0);
    }

    @Test
    void compilerErrorExcerptKeepsTheFirstBangBlockAndFallsBackToTail() {
        String log = "This is XeTeX.\n(./handout.tex\n! Missing $ inserted.\n<inserted text> \n                $ \nl.42 ...\n! Undefined control sequence.\n";
        String excerpt = TeachingHandoutPdfExportService.compilerErrorExcerpt(log);
        assertThat(excerpt).startsWith("! Missing $ inserted.");
        assertThat(excerpt).doesNotContain("! Undefined control sequence.");

        assertThat(TeachingHandoutPdfExportService.compilerErrorExcerpt("no error line here"))
                .isEqualTo("no error line here");
        assertThat(TeachingHandoutPdfExportService.compilerErrorExcerpt("")).isEmpty();
    }
}
