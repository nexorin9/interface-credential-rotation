package com.med.icr.tools;

import com.med.icr.attribution.AttributionBenchmark;

import java.nio.file.Path;

/** 对比基准可执行入口：产出 reports/attribution-benchmark.json（固定种子、可重跑）。 */
public final class BenchmarkRunner {
    private BenchmarkRunner() {}

    public static void main(String[] args) throws Exception {
        Path events = Path.of(args.length > 0 ? args[0] : "samples/failures-4w.jsonl");
        Path truth = Path.of(args.length > 1 ? args[1] : "samples/failures-4w-truth.jsonl");
        Path ledger = Path.of(args.length > 2 ? args[2] : "samples/credentials.json");
        Path out = Path.of(args.length > 3 ? args[3] : "reports/attribution-benchmark.json");
        var result = AttributionBenchmark.run(events, truth, ledger);
        AttributionBenchmark.writeReport(out, result);
        System.out.println("对比基准完成: 样本 " + result.sampleSize()
                + " 条，规则 precision=" + result.rule().precision() + " recall=" + result.rule().recall()
                + "，模型参与 precision=" + result.model().precision() + " recall=" + result.model().recall()
                + "，误聚案例 " + result.misclusterCases().size() + " 条，报告 " + out);
    }
}
