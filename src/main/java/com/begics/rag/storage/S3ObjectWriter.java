package com.begics.rag.storage;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

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

  public java.util.List<StoredObject> list(String prefix) {
    var objects = new java.util.ArrayList<StoredObject>();
    String continuationToken = null;
    do {
      var request = ListObjectsV2Request.builder()
          .bucket(bucket)
          .prefix(prefix)
          .continuationToken(continuationToken)
          .build();
      var response = s3.listObjectsV2(request);
      response.contents().stream()
          .map(object -> new StoredObject(object.key(), object.size()))
          .forEach(objects::add);
      continuationToken = response.nextContinuationToken();
    } while (continuationToken != null);
    return java.util.List.copyOf(objects);
  }

  public void delete(String key) {
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  public record StoredObject(String key, long sizeBytes) {}
}
