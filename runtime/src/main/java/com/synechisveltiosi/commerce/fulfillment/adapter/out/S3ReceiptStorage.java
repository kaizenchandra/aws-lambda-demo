package com.synechisveltiosi.commerce.fulfillment.adapter.out;

import com.synechisveltiosi.commerce.fulfillment.application.port.out.ReceiptStorage;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3ReceiptStorage implements ReceiptStorage {
  private final S3Client s3;
  private final String bucket;

  public S3ReceiptStorage(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  public String putImmutable(UUID order, String content) {
    var key = "receipts/" + order + ".txt";
    var bytes = content.getBytes(StandardCharsets.UTF_8);
    var hash = hash(bytes);
    try {
      s3.putObject(
          r ->
              r.bucket(bucket)
                  .key(key)
                  .contentType("text/plain; charset=utf-8")
                  .ifNoneMatch("*")
                  .metadata(Map.of("sha256", hash)),
          RequestBody.fromBytes(bytes));
    } catch (S3Exception e) {
      if (e.statusCode() != 412) throw e;
      var existing = s3.headObject(r -> r.bucket(bucket).key(key));
      if (!hash.equals(existing.metadata().get("sha256")))
        throw new IllegalStateException("Conflicting immutable receipt", e);
    }
    return key;
  }

  private String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
