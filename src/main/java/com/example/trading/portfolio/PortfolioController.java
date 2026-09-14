package com.example.trading.portfolio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Portfolio Goals & Allocation endpoints. See SPEC.md §5 and §16.
 */
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
@Slf4j
public class PortfolioController {

    private final AllocationService allocationService;

    /** GET /api/portfolio/profile — active profile with target weights. */
    @GetMapping("/profile")
    public ResponseEntity<AllocationDto.ProfileDto> getProfile() {
        return ResponseEntity.ok(buildProfileDto());
    }

    /** PUT /api/portfolio/profile — replace target weights and (optionally) profile metadata. */
    @PutMapping("/profile")
    public ResponseEntity<AllocationDto.ProfileDto> updateProfile(
            @RequestBody AllocationDto.UpdateWeightsRequest req) {
        PortfolioProfileEntity active = allocationService.getActiveProfile();

        if (req.description() != null || req.toleranceAbsolutePp() != null || req.toleranceRelative() != null) {
            allocationService.updateProfileMetadata(
                    active.getId(), req.description(), req.toleranceAbsolutePp(), req.toleranceRelative());
        }

        List<PortfolioTargetWeightEntity> newWeights = req.weights() == null ? List.of()
                : req.weights().stream()
                    .map(w -> PortfolioTargetWeightEntity.builder()
                            .profileId(active.getId())
                            .bucketType(w.bucketType())
                            .bucketKey(w.bucketKey())
                            .targetWeight(w.targetWeight())
                            .build())
                    .toList();
        allocationService.replaceTargetWeights(active.getId(), newWeights);
        return ResponseEntity.ok(buildProfileDto());
    }

    /** GET /api/portfolio/drift — actual vs target weights with alert levels. */
    @GetMapping("/drift")
    public ResponseEntity<AllocationDto.DriftResponse> getDrift() {
        return ResponseEntity.ok(allocationService.computeDrift());
    }

    /** GET /api/portfolio/sectors/mapping — how NSE industries map to simple sectors. */
    @GetMapping("/sectors/mapping")
    public ResponseEntity<java.util.Map<String, String>> getSectorMapping() {
        return ResponseEntity.ok(SectorMapping.defaults());
    }

    private AllocationDto.ProfileDto buildProfileDto() {
        PortfolioProfileEntity p = allocationService.getActiveProfile();
        List<AllocationDto.WeightEntry> weights = allocationService.getTargetWeights(p.getId()).stream()
                .map(w -> new AllocationDto.WeightEntry(w.getBucketType(), w.getBucketKey(), w.getTargetWeight()))
                .toList();
        return new AllocationDto.ProfileDto(
                p.getId(), p.getName(), p.getDescription(), p.isActive(),
                p.getToleranceAbsolutePp(), p.getToleranceRelative(), weights);
    }
}
