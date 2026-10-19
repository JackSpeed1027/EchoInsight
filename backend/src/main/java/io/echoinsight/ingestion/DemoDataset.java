package io.echoinsight.ingestion;

public record DemoDataset(
    String id, String title, int commentCount, int transcriptSegmentCount, boolean synthetic) {}
