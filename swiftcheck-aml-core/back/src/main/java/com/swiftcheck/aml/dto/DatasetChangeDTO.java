package com.swiftcheck.aml.dto;

import com.swiftcheck.aml.entities.ChangeType;
import com.swiftcheck.aml.entities.DatasetChange;

/** Une entité ajoutée, retirée ou modifiée lors d'une publication, en lecture seule. */
public record DatasetChangeDTO(String entityId, String entitySchema, String caption, ChangeType changeType,
                               String changedProperties) {

    public static DatasetChangeDTO from(DatasetChange c) {
        return new DatasetChangeDTO(c.getEntityId(), c.getEntitySchema(), c.getCaption(), c.getChangeType(),
                c.getChangedProperties());
    }
}
