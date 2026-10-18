package com.swiftcheck.aml.dto;

import java.time.Instant;

import com.swiftcheck.aml.entities.RetainedEntity;

/**
 * Entité gardée par la rétention, en lecture seule.
 *
 * @param expiresAt date à partir de laquelle elle ne sera plus publiée
 */
public record RetainedEntityDTO(
        long id,
        String entityId,
        String entitySchema,
        String caption,
        Instant removedAt,
        Instant expiresAt) {

    public static RetainedEntityDTO from(RetainedEntity e, Instant expiresAt) {
        return new RetainedEntityDTO(e.getId(), e.getEntityId(), e.getEntitySchema(), e.getCaption(),
                e.getRemovedAt(), expiresAt);
    }
}
