package com.begics.rag.storage;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3ObjectWriter {
  private final S3Client s3;
  private final String bucket;

  public S3ObjectWriter(S3Client s3, @Value("${app.s3.bucket}") String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  public void write(String key, String contentType, MultipartFile file) {
    try (var input = file.getInputStream()) {
      s3.putObject(PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType(contentType)
              .build(),
          RequestBody.fromInputStream(input, file.getSize()));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to upload file to S3", e);
    }
  }
}
