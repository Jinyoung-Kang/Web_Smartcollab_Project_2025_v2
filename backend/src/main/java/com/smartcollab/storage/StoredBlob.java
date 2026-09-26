package com.smartcollab.storage;

public record StoredBlob(String key, long size, String sha256) {
}
