package com.cognizant.storeops.programmes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/projects}.
 *
 * @param name        programme name
 * @param description optional detail
 * @param storeId     store the programme runs in
 * @param regionId    region the store belongs to
 * @param ownerId     staff member accountable for the programme
 */
public record CreateProjectRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 200, message = "must be at most 200 characters")
        String name,

        @Size(max = 2000, message = "must be at most 2000 characters")
        String description,

        @NotBlank(message = "must not be blank")
        String storeId,

        String regionId,

        @NotBlank(message = "must not be blank")
        String ownerId) {
}
