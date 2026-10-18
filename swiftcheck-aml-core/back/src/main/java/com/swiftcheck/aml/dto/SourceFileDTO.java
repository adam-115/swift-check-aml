package com.swiftcheck.aml.dto;

import java.time.Instant;

import com.swiftcheck.aml.entities.SourceFile;

/** Fichier source signé et archivé, en lecture seule. */
public record SourceFileDTO(long id, String origin, String sha256, long sizeBytes, String archivePath,
                            Instant fetchedAt) {

    public static SourceFileDTO from(SourceFile f) {
        return new SourceFileDTO(f.getId(), f.getOrigin(), f.getSha256(), f.getSizeBytes(), f.getArchivePath(),
                f.getFetchedAt());
    }
}
