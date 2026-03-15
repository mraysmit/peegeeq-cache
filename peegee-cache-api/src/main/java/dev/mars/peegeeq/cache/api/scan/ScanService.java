package dev.mars.peegeeq.cache.api.scan;

import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import io.vertx.core.Future;

public interface ScanService {

    Future<ScanResult> scan(ScanRequest request);
}
