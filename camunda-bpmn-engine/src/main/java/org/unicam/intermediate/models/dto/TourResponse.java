package org.unicam.intermediate.models.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TourResponse {
    private List<String> targetPlaceIds;
    private List<String> navigationPath;
}
