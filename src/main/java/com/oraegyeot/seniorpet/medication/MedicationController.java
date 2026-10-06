package com.oraegyeot.seniorpet.medication;

import com.oraegyeot.seniorpet.medication.MedicationDtos.MedicationRequest;
import com.oraegyeot.seniorpet.medication.MedicationDtos.MedicationResponse;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 투약 일정 API (docs/api-today.md 2절). */
@RestController
public class MedicationController {

    private final MedicationService medicationService;

    public MedicationController(MedicationService medicationService) {
        this.medicationService = medicationService;
    }

    @GetMapping("/api/pets/{petId}/medications")
    public List<MedicationResponse> list(@CurrentUserId UUID userId, @PathVariable UUID petId) {
        return medicationService.listActive(userId, petId).stream().map(MedicationResponse::from).toList();
    }

    @PostMapping("/api/pets/{petId}/medications")
    @ResponseStatus(HttpStatus.CREATED)
    public MedicationResponse create(@CurrentUserId UUID userId, @PathVariable UUID petId,
                                     @Valid @RequestBody MedicationRequest req) {
        return MedicationResponse.from(medicationService.create(userId, petId, req));
    }

    @PutMapping("/api/medications/{id}")
    public MedicationResponse update(@CurrentUserId UUID userId, @PathVariable UUID id,
                                     @Valid @RequestBody MedicationRequest req) {
        return MedicationResponse.from(medicationService.update(userId, id, req));
    }

    @DeleteMapping("/api/medications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        medicationService.deactivate(userId, id);
    }
}
