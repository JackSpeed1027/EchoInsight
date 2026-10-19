package io.echoinsight.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportService {
  private final ImportValidator validator;
  private final ImportMapper mapper;
  private final ObjectMapper json;

  public ImportService(ImportValidator validator, ImportMapper mapper, ObjectMapper json) {
    this.validator = validator;
    this.mapper = mapper;
    this.json = json;
  }

  @Transactional
  public ImportReceipt ingest(ImportDocument raw) {
    var data = validator.normalize(raw);
    String canonical = encode(data);
    String fingerprint = hash(canonical);
    mapper.ensureContent(
        UUID.randomUUID().toString(),
        data.content().sourceType(),
        data.content().sourceContentId());
    String contentId =
        mapper.lockContent(data.content().sourceType(), data.content().sourceContentId());
    String snapshotId = mapper.findSnapshot(contentId, fingerprint);
    boolean reused = snapshotId != null;
    int created = 0;
    if (!reused) {
      Map<String, String> sourceIds = new HashMap<>();
      data.comments().forEach(c -> sourceIds.put(c.id(), c.sourceCommentId()));
      for (var c : data.comments()) {
        String payload =
            encode(new RevisionPayload(c.text(), sourceIds.get(c.parentId()), c.occurredAt()));
        String revisionHash = hash(payload);
        if (mapper.revisionExists(contentId, c.sourceCommentId(), revisionHash) == 0) {
          mapper.insertRevision(
              UUID.randomUUID().toString(), contentId, c.sourceCommentId(), revisionHash, payload);
          created++;
        }
      }
      snapshotId = UUID.randomUUID().toString();
      mapper.insertSnapshot(snapshotId, contentId, fingerprint, canonical);
    }
    var result =
        new ImportReceipt(
            UUID.randomUUID().toString(),
            contentId,
            snapshotId,
            "SUCCEEDED",
            raw.comments().size(),
            data.comments().size(),
            raw.comments().size() - data.comments().size(),
            created,
            reused);
    mapper.insertReceipt(result);
    return result;
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode validated import", e);
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record RevisionPayload(String text, String parentSourceId, OffsetDateTime occurredAt) {}
}
